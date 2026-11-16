package tn.higer.sav_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.AttachmentResponse;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Les pièces jointes d'un document de maintenance : le BL signé et scanné, la liste
 * de prix envoyée par le client, le plan d'implantation. Ce sont elles qui permettent
 * de redire, six mois après, ce qui avait été accepté par qui.
 *
 * Les règles viennent du module Dépannage ({@link InterventionPhotoService}), et
 * pour les mêmes raisons :
 * 1. le fichier est sur le disque, la base ne garde que le chemin, l'empreinte, la
 *    taille et le type — un PDF scanné en base alourdirait chaque liste de BL ;
 * 2. l'empreinte SHA-256 est calculée ici, sur les octets reçus : le navigateur ne
 *    peut pas la fournir, donc elle prouve que le fichier n'a pas été changé ;
 * 3. le chemin de stockage est écrit par le serveur, jamais par le client, et il est
 *    relu sous contrôle : un « ../../ » fourni par l'écran ne se lit pas.
 *
 * Une règle de plus ici, parce qu'une pièce jointe arrive de l'extérieur du portail :
 * le type est vérifié sur les PREMIERS OCTETS du fichier, pas sur son extension.
 * Renommer un tableur en « .pdf » ne trompe personne ; un fichier qui n'est ni une
 * photo ni un PDF est refusé, même s'il annonce le bon Content-Type.
 */
@Service
public class MaintenanceAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceAttachmentService.class);

    /** Les trois natures acceptées, reconnues à leur entête réel. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46}; // « %PDF »

    private final MaintenanceRepository documents;
    private final MaintenanceAttachmentRepository attachments;
    private final AuditService audit;
    private final Path storageDir;
    private final long maxBytes;

    public MaintenanceAttachmentService(MaintenanceRepository documents,
                                       MaintenanceAttachmentRepository attachments,
                                       AuditService audit,
                                       @Value("${app.storage.dir:./data}") String storageDir,
                                       @Value("${app.maintenance.max-attachment-bytes:10000000}") long maxBytes) {
        this.documents = documents;
        this.attachments = attachments;
        this.audit = audit;
        // Le dossier des pièces est sous la racine de stockage commune, comme les
        // photos de dépannage sont sous la leur : un seul chemin à monter dans
        // Docker, un seul endroit où chercher en cas de doute.
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize().resolve("attachments");
        this.maxBytes = maxBytes;
    }

    // --------------------------------------------------------------------- écriture

    @Transactional
    public AttachmentResponse upload(String maintenanceItemId, MultipartFile file, Jwt jwt) {
        MaintenanceItem document = requireDocument(maintenanceItemId);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucun fichier reçu : choisis la pièce dans l'écran, puis valide l'envoi.");
        }
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Fichier trop lourd (" + file.getSize() + " octets, limite " + maxBytes + "). "
                            + "Scanne la page en noir et blanc ou en résolution moindre : "
                            + "ce qui compte est ce qu'on lit dessus, pas la netteté.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le fichier n'a pas pu être lu pendant l'envoi. Réessaie.");
        }

        String nature = natureReelle(bytes);
        if (nature == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + safeName(file.getOriginalFilename()) + " » n'est ni une photo JPEG, ni une image PNG, "
                            + "ni un PDF : ses premiers octets ne correspondent à aucun de ces trois formats. "
                            + "Une pièce jointe se scanne ou s'enregistre dans un de ces formats.");
        }
        String annonce = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!annonce.isBlank() && !annonce.equals(nature)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + safeName(file.getOriginalFilename()) + " » annonce « " + annonce + " » alors que son "
                            + "contenu est « " + nature + " ». Le fichier n'est pas enregistré : "
                            + "renomme-le depuis son vrai format, ou joins le fichier d'origine.");
        }

        String sha256 = sha256(bytes);
        if (attachments.existsByMaintenanceItemIdAndSha256(document.getId(), sha256)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce fichier est déjà joint à ce document (mêmes octets, empreinte identique). "
                            + "Joindre deux fois la même pièce ne compte pas comme deux preuves.");
        }

        Path target = store(document.getId(), bytes, sha256, nature, safeName(file.getOriginalFilename()));

        MaintenanceAttachment a = new MaintenanceAttachment();
        a.setId("att-" + UUID.randomUUID());
        a.setMaintenanceItemId(document.getId());
        a.setOriginalName(truncate(safeName(file.getOriginalFilename()), 255));
        a.setContentType(nature);
        a.setSizeBytes((long) bytes.length);
        a.setStoragePath(storageDir.relativize(target).toString().replace('\\', '/'));
        a.setSha256(sha256);
        a.setUploadedBy(jwt == null ? null : jwt.getClaimAsString("email"));
        a.setUploadedAt(LocalDateTime.now());
        attachments.save(a);

        audit.record("UPLOAD", "MAINTENANCE_ATTACHMENT", a.getId(),
                "Pièce jointe « " + a.getOriginalName() + " » (" + a.getSizeBytes() + " octets) au document "
                        + document.getNumber(), jwt);
        return AttachmentResponse.from(a);
    }

    /**
     * Le document part du portail : ses pièces partent avec, sur le disque et en
     * base. Retourner le nombre de fichiers enlevés sert à la ligne d'audit — une
     * suppression de BL qui laisse trois scans orphelins n'est pas une suppression
     * propre.
     */
    @Transactional
    public int supprimerAvecDocument(String maintenanceItemId) {
        List<MaintenanceAttachment> files =
                attachments.findByMaintenanceItemIdOrderByUploadedAtAsc(maintenanceItemId);
        for (MaintenanceAttachment a : files) {
            effacerFichier(a);
        }
        attachments.deleteByMaintenanceItemId(maintenanceItemId);
        return files.size();
    }

    /**
     * Le chemin porte le document ET la pièce : les deux doivent correspondre.
     * Autrement, une URL correctement formée mais recopiée d'un autre document
     * effacerait une preuve qui n'a rien à voir avec l'écran ouvert.
     */
    @Transactional
    public void delete(String maintenanceItemId, String attachmentId, Jwt jwt) {
        MaintenanceAttachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cette pièce jointe est introuvable."));
        if (!a.getMaintenanceItemId().equals(maintenanceItemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Cette pièce n'est pas jointe à ce document : elle reste où elle est.");
        }
        effacerFichier(a);
        attachments.delete(a);
        String numero = documents.findById(a.getMaintenanceItemId())
                .map(MaintenanceItem::getNumber).orElse(a.getMaintenanceItemId());
        audit.record("DELETE", "MAINTENANCE_ATTACHMENT", attachmentId,
                "Suppression de la pièce « " + a.getOriginalName() + " » du document " + numero, jwt);
    }

    /**
     * Le fichier lui-même. Une pièce jointe est une preuve : si l'écriture du disque
     * a été perdue entre-temps, l'écran doit le lire clairement plutôt que de
     * télécharger n'importe quoi.
     */
    @Transactional(readOnly = true)
    public Content content(String attachmentId) {
        MaintenanceAttachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cette pièce jointe est introuvable."));
        Path file = resolveStored(a.getStoragePath());
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Le fichier de cette pièce est absent du disque du serveur. "
                            + "La base pointe vers « " + a.getStoragePath() + " ».");
        }
        try {
            return new Content(Files.readAllBytes(file), a.getContentType(), a.getOriginalName());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "La pièce jointe n'a pas pu être relue sur le disque.");
        }
    }

    public record Content(byte[] bytes, String contentType, String fileName) {}

    // ---------------------------------------------------------------------- lecture

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(String maintenanceItemId) {
        requireDocument(maintenanceItemId);
        return attachments.findByMaintenanceItemIdOrderByUploadedAtAsc(maintenanceItemId).stream()
                .map(AttachmentResponse::from).toList();
    }

    // ------------------------------------------------------------------ mise en forme

    private MaintenanceItem requireDocument(String maintenanceItemId) {
        return documents.findById(maintenanceItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ce document de maintenance est introuvable : une pièce jointe se pose sur un "
                                + "devis, un BL ou un ordre de travail déjà enregistré."));
    }

    private Path store(String documentId, byte[] bytes, String sha256, String nature, String nameHint) {
        Path dir = storageDir.resolve(sanitize(documentId));
        Path target = dir.resolve(sha256.substring(0, 12) + "-" + nameHint + extension(nature));
        try {
            Files.createDirectories(dir);
            Files.write(target, bytes);
        } catch (IOException e) {
            log.warn("Écriture de la pièce jointe refusée par le disque ({})", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le serveur n'a pas pu enregistrer le fichier sur le disque. Vérifie que le dossier « "
                            + storageDir + " » est accessible en écriture.");
        }
        return target;
    }

    private void effacerFichier(MaintenanceAttachment a) {
        Path file = resolveStored(a.getStoragePath());
        if (file == null) {
            return;
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            // La ligne de base part, le fichier reste : mieux qu'un écran bloqué.
            // Le dossier est signalé, l'humain pourra faire le ménage.
            log.warn("La pièce jointe {} est restée sur le disque ({})", a.getStoragePath(), e.getMessage());
        }
    }

    /**
     * Le chemin vient de la base, donc il est relu sous contrôle : il doit rester
     * dans le dossier de stockage. Une valeur trafiquée (« ..\\..\\windows\\… ») ne
     * se lit pas.
     */
    private Path resolveStored(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path candidate = storageDir.resolve(relative).normalize();
        return candidate.startsWith(storageDir) && Files.isRegularFile(candidate) ? candidate : null;
    }

    /** La nature réelle, lue sur les premiers octets. Ni l'extension, ni le Content-Type n'y changent rien. */
    private static String natureReelle(byte[] bytes) {
        if (startsWith(bytes, JPEG)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, PNG)) {
            return "image/png";
        }
        if (startsWith(bytes, PDF)) {
            return "application/pdf";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] header) {
        if (bytes.length < header.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (bytes[i] != header[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String nature) {
        return switch (nature) {
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            default -> ".jpg";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le serveur ne peut pas calculer l'empreinte des fichiers (SHA-256 indisponible).");
        }
    }

    /** Le nom d'origine reste lisible sur l'écran ; il ne doit pas pouvoir contenir de chemin. */
    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "piece";
        }
        String base = Path.of(raw).getFileName().toString();
        String cleaned = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        return cleaned.isBlank() ? "piece" : cleaned;
    }

    /** Un identifiant vient du serveur, mais il traverse la base : on ne l'envoie pas tel quel dans un chemin. */
    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
