package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une preuve photo versée sur un dossier groupe.
 *
 * L'image est sur le disque, la base ne garde que le chemin et l'empreinte. Le
 * SHA-256 est calculé par le serveur à l'écriture : c'est lui qui permet de dire
 * que le fichier n'a pas changé depuis. L'heure du fichier et celle du versement
 * restent distinctes, comme pour les preuves des sorties.
 */
@Entity
@Table(name = "mission_group_photos")
@Getter @Setter @NoArgsConstructor
public class MissionGroupPhoto {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "dossier_id", nullable = false, length = 50)
    private String dossierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MissionGroupPhotoAngle angle;

    /** La ligne de bus que le cliché montre, pour les preuves avant / après. */
    @Column(name = "bus_id", length = 50)
    private String busId;

    @Column(name = "file_path", nullable = false, length = 400)
    private String filePath;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", length = 60)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "taken_at")
    private LocalDateTime takenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "taken_at_source", length = 20)
    private PhotoTimeSource takenAtSource;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(length = 500)
    private String note;
}
