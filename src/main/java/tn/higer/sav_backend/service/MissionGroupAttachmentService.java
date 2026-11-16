package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MissionGroupDtos.AttachmentResponse;
import tn.higer.sav_backend.model.MissionGroupAttachment;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.repository.MissionGroupAttachmentRepository;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.security.Access;

import java.io.IOException;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * La fiche atelier jointe à un dossier groupe — le papier ou le PDF d'origine
 * des tâches à faire.
 *
 * Les règles sont celles des photos, avec un seul geste en moins :
 * 1. le fichier part sur le disque, la base ne garde que le chemin et l'empreinte ;
 * 2. le SHA-256 est calculé ici, à partir des octets reçus — il prouve que la
 *    fiche versée n'a pas été modifiée depuis ;
 * 3. le versement est réservé à l'atelier : c'est lui qui détient la fiche. Le
 *    technicien la lit et coche la liste, il ne verse rien.
 *
 * Une même fiche ne se verse pas deux fois sur le même dossier : deux fois le
 * même octet ne fait pas deux pièces.
 */
@Service
public class MissionGroupAttachmentService {

    /** La fiche est un papier scanné ou un PDF, pas n'importe quel fichier. */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");

    private final MissionGroupAttachmentRepository attachments;
    private final MissionGroupDossierRepository dossiers;
    private final AuditService audit;
    private final Path storageDir;
    private final long maxBytes;

    public MissionGroupAttachmentService(MissionGroupAttachmentRepository attachments,
                                         MissionGroupDossierRepository dossiers,
                                         AuditService audit,
                                         @Value("${app.depannage.photos-dir:./data/photos}") String photosDir,
                                         @Value("${app.mission-group.max-attachment-bytes:10000000}") long maxBytes) {
        this.attachments = attachments;
        this.dossiers = dossiers;
        this.audit = audit;
        this.storageDir = Path.of(photosDir).toAbsolutePath().normalize();
        this.maxBytes = Math.min(maxBytes, 10_000_000L);
    }

    public List<AttachmentResponse> list(String dossierId, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = dossier(dossierId);
        requireReaderOf(dossier, jwt, auth);
        return attachments.findByDossierIdOrderByUploadedAtAsc(dossierId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    /**
     * Jointer la fiche. Réservé à l'atelier ; le technicien affecté lit la fiche
     * et coche la liste, le versement n'est pas son geste.
     */
    @Transactional
    public AttachmentResponse upload(String dossierId, MultipartFile file, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = dossier(dossierId);
        if (!Access.usesMissionGroupe(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La fiche atelier se joint par l'atelier — le technicien la lit et coche la liste.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucune fiche reçue : choisissez un fichier, puis validez l'envoi.");
        }
        String excelType = MissionGroupExcelReader.contentType(file);
        String type = excelType != null ? excelType
                : file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (excelType == null && !ALLOWED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Format refusé : la fiche se joint en PDF, en Excel (.xlsx, .xls) ou en photo (JPEG, PNG, WebP).");
        }
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Fichier trop lourd (" + file.getSize() + " octets, limite " + maxBytes + "). "
                            + "Reprenez la fiche compressée ou scannée plus bas.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le fichier n'a pas pu être lu pendant l'envoi. Réessayez.");
        }
        String sha256 = sha256(bytes);
        if (attachments.existsByDossierIdAndSha256(dossierId, sha256)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette fiche est déjà versée sur ce dossier (même contenu, octet pour octet).");
        }

        Path target = store(dossierId, bytes, sha256, type);

        MissionGroupAttachment piece = new MissionGroupAttachment();
        piece.setId("mga-" + UUID.randomUUID());
        piece.setDossierId(dossierId);
        piece.setFilePath(storageDir.relativize(target).toString().replace('\\', '/'));
        piece.setOriginalName(truncate(safeName(file.getOriginalFilename()), 255));
        piece.setContentType(type);
        piece.setSizeBytes(file.getSize());
        piece.setSha256(sha256);
        piece.setUploadedAt(LocalDateTime.now());
        piece.setActorEmail(jwt == null ? null : jwt.getClaimAsString("email"));
        attachments.save(piece);

        audit.record("UPLOAD", "MISSION_GROUP_ATTACHMENT", piece.getId(),
                "Fiche atelier jointe au dossier " + dossier.getNumber(), jwt);
        return AttachmentResponse.from(piece);
    }

    /** Le fichier lui-même, avec le jeton déjà vérifié par la couche de sécurité. */
    public Stored content(String attachmentId, Jwt jwt, Authentication auth) {
        MissionGroupAttachment piece = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cette pièce jointe n'existe pas."));
        requireReaderOf(dossier(piece.getDossierId()), jwt, auth);
        Path file = storageDir.resolve(piece.getFilePath()).normalize();
        if (!file.startsWith(storageDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Le fichier de cette pièce jointe est introuvable sur le disque.");
        }
        try {
            return new Stored(Files.readAllBytes(file), piece.getContentType(),
                    piece.getOriginalName());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le fichier de cette pièce jointe n'a pas pu être lu.");
        }
    }

    private MissionGroupDossier dossier(String id) {
        return dossiers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ce dossier n'existe pas."));
    }

    /**
     * L'atelier lit tous les dossiers ; le technicien ne lit que ceux où il est
     * l'homme affecté. Un technicien qui tenterait un autre dossier reçoit le
     * même « n'existe pas » que pour un dossier absent.
     */
    private void requireReaderOf(MissionGroupDossier dossier, Jwt jwt, Authentication auth) {
        if (Access.usesMissionGroupe(auth)) {
            return;
        }
        if (Access.isTechnician(auth)
                && Objects.equals(jwt == null ? null : jwt.getSubject(),
                dossier.getTechnicianUserId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ce dossier n'existe pas.");
    }

    private Path store(String dossierId, byte[] bytes, String sha256, String type) {
        String extension = switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        try {
            Path dir = storageDir.resolve("mg-" + dossierId);
            Files.createDirectories(dir);
            Path target = dir.resolve("fiche-" + sha256 + extension);
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "La fiche n'a pas pu être enregistrée sur le disque du serveur.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "fiche";
        }
        String normalized = name.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "_");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Stored(byte[] bytes, String contentType, String fileName) {}
}
