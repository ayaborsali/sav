package tn.higer.sav_backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.higer.sav_backend.dto.MaintenanceDtos.AttachmentResponse;
import tn.higer.sav_backend.service.MaintenanceAttachmentService;

import java.util.List;

/**
 * Les pièces jointes d'un document de maintenance : le BL signé scanné, la liste de
 * prix du client, le plan.
 *
 * L'envoi est en multipart, pas en base64 dans un champ JSON : une image encodée
 * grossit d'un tiers et transite par la couche JSON, ce qui ne dirait rien de sa
 * nature réelle. Le serveur garde les octets tels qu'ils arrivent, les reconnaît à
 * leur entête, et calcule l'empreinte dessus.
 *
 * Droits : la règle générique de l'application, sans exception nouvelle — lecture
 * pour tout compte connecté, écriture pour admin et gestionnaire SAV. Un fichier
 * n'est jamais servi à quelqu'un que la fiche du document ne peut pas voir.
 */
@RestController
@RequestMapping("/api/maintenance/{itemId}/attachments")
public class MaintenanceAttachmentController {

    private final MaintenanceAttachmentService pieces;

    public MaintenanceAttachmentController(MaintenanceAttachmentService pieces) {
        this.pieces = pieces;
    }

    @GetMapping
    public List<AttachmentResponse> list(@PathVariable String itemId) {
        return pieces.list(itemId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@PathVariable String itemId,
                                     @RequestParam("file") MultipartFile file,
                                     @AuthenticationPrincipal Jwt jwt) {
        return pieces.upload(itemId, file, jwt);
    }

    /** Le fichier lui-même, avec sa vraie nature de contenu — et sans mise en cache. */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String attachmentId) {
        MaintenanceAttachmentService.Content stored = pieces.content(attachmentId);
        MediaType type = stored.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(stored.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + stored.fileName() + "\"")
                // Une preuve remplace une autre : l'écran doit montrer le fichier du jour.
                .cacheControl(CacheControl.noStore())
                .body(stored.bytes());
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String itemId, @PathVariable String attachmentId,
                       @AuthenticationPrincipal Jwt jwt) {
        pieces.delete(itemId, attachmentId, jwt);
    }
}
