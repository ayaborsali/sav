package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une preuve photo versée sur une sortie de dépannage.
 *
 * L'image est sur le disque, la base n'en garde que le chemin et l'empreinte :
 * un JPEG stocké en base alourdirait chaque lecture de liste, et la liste est
 * justement l'écran du chef. Le SHA-256 est calculé par le serveur à l'écriture,
 * jamais transmis par le navigateur : c'est ce qui permet de dire que le fichier
 * n'a pas été changé depuis.
 *
 * Les deux heures ne disent pas la même chose : {@code takenAt} vient du fichier
 * (EXIF, horloge de l'appareil), {@code uploadedAt} du serveur au moment du
 * versement. Un cliché pris la semaine d'avant se voit ici, pas ailleurs.
 */
@Entity
@Table(name = "intervention_photos")
@Getter @Setter @NoArgsConstructor
public class InterventionPhoto {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 50)
    private String interventionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotoAngle angle;

    /** Chemin relatif au dossier de stockage : le poste peut être déménagé. */
    @Column(nullable = false, length = 400)
    private String filePath;

    @Column(length = 255)
    private String originalName;

    @Column(length = 60)
    private String contentType;

    private Long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    private LocalDateTime takenAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PhotoTimeSource takenAtSource;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column(length = 255)
    private String actorEmail;

    @Column(length = 500)
    private String note;
}
