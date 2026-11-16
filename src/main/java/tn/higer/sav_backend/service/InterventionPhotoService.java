package tn.higer.sav_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PhotoDtos.PhotoResponse;
import tn.higer.sav_backend.dto.PhotoDtos.ProofStatusDto;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionEventSource;
import tn.higer.sav_backend.model.InterventionEventType;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.PhotoTimeSource;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.security.Access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Les preuves photo d'une sortie : ce qui transforme un récit en dossier contrôlable.
 *
 * Trois règles, toutes volontaires :
 * 1. l'image part sur le disque, la base ne garde que le chemin, l'empreinte et les
 *    heures. Un JPEG en base alourdirait chaque liste de sorties, et la liste est
 *    précisément l'outil du chef ;
 * 2. l'empreinte SHA-256 est calculée ici, à partir des octets reçus : le navigateur
 *    ne peut pas la fournir, donc elle prouve que le fichier n'a pas été modifié ;
 * 3. deux heures coexistent — l'heure de l'appareil (EXIF, parfois absente ou fausse)
 *    et l'heure du serveur au versement. C'est l'écart entre les deux qui répond à
 *    « ce cliché a-t-il été pris pendant la sortie ? ».
 *
 * Le module reste utilisable sans téléphone sur le terrain : rien n'exige une photo
 * pour avancer d'une étape. Ce que l'absence de preuves bloque, c'est la validation
 * de la prime (D3) — et la clôture exige alors une raison écrite.
 */
@Service
public class InterventionPhotoService {

    private static final Logger log = LoggerFactory.getLogger(InterventionPhotoService.class);

    /** Les formats que l'on accepte : ce que produit un appareil photo, pas un dossier bureautique. */
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /** Ce qu'il faut avoir vu pour dire qu'une sortie est prouvée. */
    private static final List<PhotoAngle> REQUIRED_ANGLES = List.of(PhotoAngle.BEFORE, PhotoAngle.AFTER);

    private final InterventionPhotoRepository photos;
    private final InterventionService interventions;
    private final PhotoExifService exif;
    private final AuditService audit;
    private final Path storageDir;
    private final long maxBytes;

    public InterventionPhotoService(InterventionPhotoRepository photos,
                                    InterventionService interventions,
                                    PhotoExifService exif,
                                    AuditService audit,
                                    @Value("${app.depannage.photos-dir:./data/photos}") String photosDir,
                                    @Value("${app.depannage.max-photo-bytes:8000000}") long maxBytes) {
        this.photos = photos;
        this.interventions = interventions;
        this.exif = exif;
        this.audit = audit;
        this.storageDir = Path.of(photosDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    // --------------------------------------------------------------------- écriture

    @Transactional
    public PhotoResponse upload(String interventionId, MultipartFile file, String angle,
                                String note, Jwt jwt, Authentication auth) {
        Intervention i = interventions.requireVisible(interventionId, auth, jwt);
        PhotoAngle shot = parseAngle(angle);
        requireUploadAllowed(i, auth);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucune image reçue : choisis un fichier dans l'écran, puis valide l'envoi.");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Format refusé (« " + (type.isBlank() ? "inconnu" : type) + " ») : "
                            + "seules les photos JPEG, PNG ou WebP servent de preuve.");
        }
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image trop lourde (" + file.getSize() + " octets, limite " + maxBytes + "). "
                            + "Reprends le cliché recadré sur ce qu'il doit montrer.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le fichier n'a pas pu être lu pendant l'envoi. Réessaie.");
        }
        String sha256 = sha256(bytes);
        if (photos.findByInterventionIdOrderByUploadedAtAsc(interventionId).stream()
                .anyMatch(p -> p.getSha256().equals(sha256))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce cliché est déjà versé sur cette sortie (même contenu, octet pour octets). "
                            + "Verser deux fois la même photo ne compte pas comme deux preuves.");
        }

        Path target = store(i.getId(), bytes, sha256, type, safeName(file.getOriginalFilename()));
        PhotoExifService.Exif read = exif.read(target);

        InterventionPhoto p = new InterventionPhoto();
        p.setId("pht-" + UUID.randomUUID());
        p.setInterventionId(i.getId());
        p.setAngle(shot);
        p.setFilePath(storageDir.relativize(target).toString().replace('\\', '/'));
        p.setOriginalName(truncate(safeName(file.getOriginalFilename()), 255));
        p.setContentType(type);
        p.setSizeBytes(file.getSize());
        p.setSha256(sha256);
        p.setTakenAt(read.takenAt());
        p.setTakenAtSource(read.takenAt() != null ? PhotoTimeSource.EXIF : PhotoTimeSource.UPLOAD);
        p.setUploadedAt(LocalDateTime.now());
        p.setActorEmail(jwt == null ? null : jwt.getClaimAsString("email"));
        p.setNote(truncate(note, 500));
        photos.save(p);

        interventions.event(i.getId(), InterventionEventType.PHOTO, InterventionEventSource.MANAGED, jwt,
                "Photo « " + shot.label() + " » versée" + describeGap(p));
        audit.record("UPLOAD", "INTERVENTION_PHOTO", p.getId(),
                "Versement d'une preuve photo (" + shot.label() + ") sur " + i.getNumber(), jwt);
        return PhotoResponse.from(p);
    }

    /** Après clôture, la fiche devient immuable : seul l'ADMIN peut encore ajouter, et le journal le garde. */
    private void requireUploadAllowed(Intervention i, Authentication auth) {
        if (i.getStatus() == InterventionStatus.CLOSED && !Access.isAdmin(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La sortie " + i.getNumber() + " est clôturée : ses preuves sont figées. "
                            + "Un administrateur seul peut encore verser un cliché, et la trace en reste au journal.");
        }
    }

    private Path store(String interventionId, byte[] bytes, String sha256, String type, String nameHint) {
        Path dir = storageDir.resolve(sanitize(interventionId));
        Path target = dir.resolve(sha256.substring(0, 12) + "-" + nameHint + extension(type));
        try {
            Files.createDirectories(dir);
            Files.write(target, bytes);
        } catch (IOException e) {
            log.warn("Écriture de la photo refusée par le disque ({})", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le serveur n'a pas pu enregistrer l'image sur le disque. "
                            + "Vérifie que le dossier « " + storageDir + " » est accessible en écriture.");
        }
        return target;
    }

    // ---------------------------------------------------------------------- lecture

    @Transactional(readOnly = true)
    public List<PhotoResponse> list(String interventionId, Authentication auth, Jwt jwt) {
        interventions.requireVisible(interventionId, auth, jwt);
        return photos.findByInterventionIdOrderByUploadedAtAsc(interventionId)
                .stream().map(PhotoResponse::from).toList();
    }

    /** Ce qu'il manque comme preuve, en français, pour l'écran de clôture. */
    @Transactional(readOnly = true)
    public ProofStatusDto proof(String interventionId) {
        List<PhotoAngle> missing = REQUIRED_ANGLES.stream()
                .filter(a -> !photos.existsByInterventionIdAndAngle(interventionId, a))
                .toList();
        List<String> codes = missing.stream().map(a -> a.name().toLowerCase()).toList();
        String message = missing.isEmpty()
                ? "Les preuves photo avant et après sont versées."
                : "Il manque : " + String.join(", ", missing.stream().map(PhotoAngle::label).toList())
                        + ". Sans ces clichés, la prime reste à l'état de brouillon.";
        return new ProofStatusDto(missing.isEmpty(), codes, message);
    }

    /** L'image elle-même, avec sa vraie nature de contenu. */
    @Transactional(readOnly = true)
    public Stored content(String photoId, Authentication auth, Jwt jwt) {
        InterventionPhoto p = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo introuvable."));
        interventions.requireVisible(p.getInterventionId(), auth, jwt);
        Path file = resolveStored(p.getFilePath());
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Le fichier de cette photo est absent du disque du serveur. "
                            + "Le lien de la base pointe vers « " + p.getFilePath() + " ».");
        }
        try {
            return new Stored(Files.readAllBytes(file), p.getContentType(), p.getOriginalName());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "La photo n'a pas pu être relue sur le disque.");
        }
    }

    public record Stored(byte[] bytes, String contentType, String fileName) {}

    /**
     * Le chemin vient de la base, donc il est relu sous contrôle : il doit rester dans le
     * dossier de stockage. Une valeur trafiquée (« ..\\..\\windows\\… ») ne se lit pas.
     */
    private Path resolveStored(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path candidate = storageDir.resolve(relative).normalize();
        return candidate.startsWith(storageDir) && Files.isRegularFile(candidate) ? candidate : null;
    }

    // ------------------------------------------------------------------ mise en forme

    private static PhotoAngle parseAngle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il faut dire ce que la photo montre : avant, pendant, après, jauge de carburant, compteur ou annexe.");
        }
        try {
            return PhotoAngle.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Angle de photo inconnu (« " + raw + " »). Les angles sont « before », « during », « after », "
                            + "« fuel », « mileage », « other ».");
        }
    }

    /** L'écart entre l'heure de l'appareil et l'heure du serveur, écrit dans le journal. */
    private static String describeGap(InterventionPhoto p) {
        if (p.getTakenAt() == null) {
            return " — heure de l'appareil absente du fichier";
        }
        long hours = Duration.between(p.getTakenAt(), p.getUploadedAt()).toHours();
        if (Math.abs(hours) <= 48) {
            return "";
        }
        return " — prise " + (hours < 0 ? Math.abs(hours) / 24 + " j après" : hours / 24 + " j avant")
                + " le versement";
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

    private static String extension(String type) {
        return switch (type) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    /** Le nom d'origine reste lisible sur l'écran ; il ne doit pas pouvoir contenir de chemin. */
    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "photo";
        }
        String base = Path.of(raw).getFileName().toString();
        String cleaned = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        return cleaned.isBlank() ? "photo" : cleaned;
    }

    /** Un identifiant vient du serveur, mais il traverse la base : on ne l'envoie pas tel quel dans un chemin. */
    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
