package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * La fiche atelier jointe à un dossier groupe — le papier ou le PDF d'origine.
 *
 * Même règle que les photos : le fichier est sur le disque, la base ne garde
 * que le chemin et l'empreinte. Le SHA-256 est calculé par le serveur à
 * l'écriture : c'est lui qui permet de dire que la fiche versée n'a pas changé.
 */
@Entity
@Table(name = "mission_group_attachments")
@Getter @Setter @NoArgsConstructor
public class MissionGroupAttachment {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "dossier_id", nullable = false, length = 50)
    private String dossierId;

    @Column(name = "file_path", nullable = false, length = 400)
    private String filePath;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;
}
