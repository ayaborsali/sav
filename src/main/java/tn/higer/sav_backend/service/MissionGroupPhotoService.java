package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MissionGroupDtos.PhotoResponse;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.model.MissionGroupPhoto;
import tn.higer.sav_backend.model.MissionGroupPhotoAngle;
import tn.higer.sav_backend.model.PhotoTimeSource;
import tn.higer.sav_backend.repository.MissionGroupBusRepository;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.repository.MissionGroupPhotoRepository;
import tn.higer.sav_backend.security.Access;

import java.io.IOException;
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
 * Les deux preuves photo d'un dossier groupe : au départ de l'atelier et au retour.
 *
 * Les règles sont celles des preuves de sortie, sans exception :
 * 1. l'image part sur le disque, la base ne garde que le chemin et l'empreinte ;
 * 2. le SHA-256 est calculé ici, à partir des octets reçus — le navigateur ne peut
 *    pas le fournir, donc il prouve que le fichier n'a pas été modifié ;
 * 3. l'heure du fichier (EXIF) et celle du versement restent distinctes : c'est
 *    leur écart qui dit si le cliché a été pris pendant le déplacement.
 *
 * Une même image ne se verse pas deux fois sur le même dossier : deux fois le même
 * octet ne fait pas deux preuves.
 */
@Service
public class MissionGroupPhotoService {

    /** Ce que produit un appareil photo, pas un dossier bureautique. */
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final MissionGroupPhotoRepository photos;
    private final MissionGroupDossierRepository dossiers;
    private final MissionGroupBusRepository buses;
    private final PhotoExifService exif;
    private final AuditService audit;
    private final Path storageDir;
    private final long maxBytes;

    public MissionGroupPhotoService(MissionGroupPhotoRepository photos,
                                    MissionGroupDossierRepository dossiers,
                                    MissionGroupBusRepository buses,
                                    PhotoExifService exif,
                                    AuditService audit,
                                    @Value("${app.depannage.photos-dir:./data/photos}") String photosDir,
                                    @Value("${app.depannage.max-photo-bytes:8000000}") long maxBytes) {
        this.photos = photos;
        this.dossiers = dossiers;
        this.buses = buses;
        this.exif = exif;
        this.audit = audit;
        this.storageDir = Path.of(photosDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public List<PhotoResponse> list(String dossierId, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = dossier(dossierId);
        requireReaderOf(dossier, jwt, auth);
        return photos.findByDossierIdOrderByUploadedAtAsc(dossierId).stream()
                .map(PhotoResponse::from)
                .toList();
    }

    @Transactional
    public PhotoResponse upload(String dossierId, MultipartFile file, String angle, String busId,
                                String note, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = dossier(dossierId);
        requireOperatorOf(dossier, jwt, auth);
        if (dossier.getCancelledAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est annulé : "
                            + "plus aucune photo ne s'y verse.");
        }
        MissionGroupPhotoAngle shot;
        try {
            shot = MissionGroupPhotoAngle.parse(angle);
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unknown.getMessage());
        }
        String ligne = null;
        if (shot.surUnBus()) {
            ligne = validateBus(dossierId, busId, dossier.getNumber());
        } else if (busId != null && !busId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette photo ne se rattache pas à un bus : « " + shot.label()
                            + " » montre la voiture de déplacement.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucune image reçue : choisissez un fichier, puis validez l'envoi.");
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
                            + "Reprenez le cliché recadré sur ce qu'il doit montrer.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le fichier n'a pas pu être lu pendant l'envoi. Réessayez.");
        }
        String sha256 = sha256(bytes);
        if (photos.existsByDossierIdAndSha256(dossierId, sha256)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce cliché est déjà versé sur ce dossier (même contenu, octet pour octet). "
                            + "Verser deux fois la même photo ne compte pas comme deux preuves.");
        }

        Path target = store(dossierId, bytes, sha256, type);
        PhotoExifService.Exif read = exif.read(target);

        MissionGroupPhoto photo = new MissionGroupPhoto();
        photo.setId("mgp-" + UUID.randomUUID());
        photo.setDossierId(dossierId);
        photo.setAngle(shot);
        photo.setBusId(ligne);
        photo.setFilePath(storageDir.relativize(target).toString().replace('\\', '/'));
        photo.setOriginalName(truncate(safeName(file.getOriginalFilename()), 255));
        photo.setContentType(type);
        photo.setSizeBytes(file.getSize());
        photo.setSha256(sha256);
        photo.setTakenAt(read.takenAt());
        photo.setTakenAtSource(read.takenAt() != null ? PhotoTimeSource.EXIF : PhotoTimeSource.UPLOAD);
        photo.setUploadedAt(LocalDateTime.now());
        photo.setActorEmail(jwt == null ? null : jwt.getClaimAsString("email"));
        photo.setNote(truncate(note, 500));
        photos.save(photo);

        audit.record("UPLOAD", "MISSION_GROUP_PHOTO", photo.getId(),
                "Photo « " + shot.label() + " » versée sur le dossier " + dossier.getNumber(), jwt);
        return PhotoResponse.from(photo);
    }

    /** L'image elle-même, avec le jeton déjà vérifié par la couche de sécurité. */
    public Stored content(String photoId, Jwt jwt, Authentication auth) {
        MissionGroupPhoto photo = photos.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cette photo n'existe pas."));
        requireReaderOf(dossier(photo.getDossierId()), jwt, auth);
        Path file = storageDir.resolve(photo.getFilePath()).normalize();
        if (!file.startsWith(storageDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Le fichier de cette photo est introuvable sur le disque.");
        }
        try {
            return new Stored(Files.readAllBytes(file), photo.getContentType(),
                    photo.getOriginalName());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le fichier de cette photo n'a pas pu être lu.");
        }
    }

    private MissionGroupDossier dossier(String id) {
        return dossiers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ce dossier n'existe pas."));
    }

    /**
     * La photo avant / après se rattache à une ligne de bus du dossier : un
     * dossier à deux bus ne mélange pas les clichés.
     */
    private String validateBus(String dossierId, String busId, String numero) {
        if (busId == null || busId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette photo montre un bus : précisez lequel sur le dossier " + numero + ".");
        }
        return buses.findByDossierIdOrderByPositionAsc(dossierId).stream()
                .filter(b -> Objects.equals(b.getId(), busId))
                .map(b -> b.getId())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce bus n'appartient pas au dossier " + numero + "."));
    }

    /**
     * L'atelier lit toutes les preuves ; le technicien ne lit que celles du dossier
     * où il est l'homme affecté. Un technicien qui tenterait un autre dossier reçoit
     * le même « n'existe pas » que pour un dossier absent.
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

    /**
     * Verser une preuve : l'atelier, ou le technicien affecté à ce dossier. Les
     * photos sont celles de la voiture de déplacement, prises par l'homme qui la
     * conduit — un autre compte n'a rien à y déposer.
     */
    private void requireOperatorOf(MissionGroupDossier dossier, Jwt jwt, Authentication auth) {
        if (Access.usesMissionGroupe(auth)) {
            return;
        }
        if (Access.isTechnician(auth)
                && Objects.equals(jwt == null ? null : jwt.getSubject(),
                dossier.getTechnicianUserId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Les preuves de ce dossier sont versées par le technicien affecté — "
                        + "ou par l'atelier en son nom.");
    }

    private Path store(String dossierId, byte[] bytes, String sha256, String type) {
        String extension = switch (type) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        try {
            Path dir = storageDir.resolve("mg-" + dossierId);
            Files.createDirectories(dir);
            Path target = dir.resolve(sha256 + extension);
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "La photo n'a pas pu être enregistrée sur le disque du serveur.");
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
            return "photo";
        }
        return Path.of(name).getFileName().toString().replaceAll("[\\r\\n\"]", "_");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Stored(byte[] bytes, String contentType, String fileName) {}
}
