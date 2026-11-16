package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.DocumentDtos.DocumentRequest;
import tn.higer.sav_backend.dto.DocumentDtos.DocumentResponse;
import tn.higer.sav_backend.dto.DocumentDtos.DocumentSummary;
import tn.higer.sav_backend.model.Document;
import tn.higer.sav_backend.repository.DocumentRepository;
import tn.higer.sav_backend.service.AuditService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * CRUD des documents (garanties, cartes grises, manuels, rapports d'expertise…).
 * Lecture : tous les rôles connectés (règle générique GET /api/**).
 * Écriture : admin + gestionnaire_sav (règle générique /api/** — consultation bloquée).
 * fileData contient le fichier encodé en base64 (colonne TEXT).
 * La liste paginée renvoie une projection sans fileData ; le binaire reste accessible
 * via GET /api/documents/{id} (DTO complet) et GET /api/documents/{id}/content (octets bruts).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentRepository documents;
    private final AuditService audit;

    public DocumentController(DocumentRepository documents, AuditService audit) {
        this.documents = documents;
        this.audit = audit;
    }

    /** Liste paginée allégée : sans le fichier base64. */
    @GetMapping
    public Page<DocumentSummary> list(
            @PageableDefault(size = 20, sort = "uploadedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return documents.findAll(pageable).map(DocumentSummary::from);
    }

    /** Détail complet d'un document, fichier base64 inclus. */
    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable String id) {
        Document d = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable."));
        return DocumentResponse.from(d);
    }

    /** Téléchargement du fichier brut (octets), et non plus le base64 dans un JSON. */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id) {
        Document d = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable."));
        byte[] bytes = decodeFileData(d.getFileData());
        MediaType mediaType = resolveMediaType(d.getFileType());
        String filename = d.getTitle() != null ? d.getTitle() : d.getId();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse create(@Valid @RequestBody DocumentRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        Document d = new Document();
        apply(d, request);
        d.setId("doc-" + UUID.randomUUID());
        if (d.getUploadedAt() == null) {
            d.setUploadedAt(LocalDate.now());
        }
        documents.save(d);
        audit.record("CREATE", "DOCUMENT", d.getId(), "Création du document « " + d.getTitle() + " »", jwt);
        return DocumentResponse.from(d);
    }

    @PutMapping("/{id}")
    public DocumentResponse update(@PathVariable String id, @Valid @RequestBody DocumentRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        Document d = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable."));
        apply(d, request);
        documents.save(d);
        audit.record("UPDATE", "DOCUMENT", id, "Mise à jour du document « " + d.getTitle() + " »", jwt);
        return DocumentResponse.from(d);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Document d = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable."));
        documents.deleteById(id);
        audit.record("DELETE", "DOCUMENT", id, "Suppression du document « " + d.getTitle() + " »", jwt);
    }

    private void apply(Document d, DocumentRequest r) {
        d.setTitle(r.title().trim());
        d.setCategory(r.category());
        d.setFileType(r.fileType());
        d.setFileSize(r.fileSize());
        d.setVehicleId(r.vehicleId());
        d.setClientId(r.clientId());
        d.setUploadedBy(r.uploadedBy());
        d.setFileData(r.fileData());
        if (r.uploadedAt() != null) {
            d.setUploadedAt(r.uploadedAt());
        }
    }

    /**
     * Décode le contenu stocké. Le front téléverse via FileReader.readAsDataURL,
     * ce qui préfixe le base64 par « data:<mime>;base64, » ; on retire ce préfixe.
     */
    private static byte[] decodeFileData(String fileData) {
        if (fileData == null || fileData.isBlank()) {
            return new byte[0];
        }
        String payload = fileData;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma >= 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le contenu du document n'est pas un fichier base64 valide.");
        }
    }

    /** Le fileType du front est une extension (« PDF ») ; on retombe sur octet-stream. */
    private static MediaType resolveMediaType(String fileType) {
        if (fileType != null && fileType.contains("/")) {
            try {
                return MediaType.parseMediaType(fileType);
            } catch (RuntimeException ignored) {
                // type MIME invalide : on utilise le flux binaire par défaut.
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
