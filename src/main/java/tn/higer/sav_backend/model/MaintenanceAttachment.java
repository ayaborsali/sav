package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une pièce jointe d'un document de maintenance : le BL signé scanné, la liste de
 * prix du client, le plan d'implantation envoyé à l'atelier.
 *
 * Le fichier lui-même est sur le disque du serveur, jamais dans la base. La base
 * ne garde que de quoi le retrouver et le contrôler : chemin relatif, nom, type,
 * taille, empreinte SHA-256. C'est la règle déjà posée pour les photos de
 * dépannage ({@link InterventionPhoto}) — un document scanné en base alourdirait
 * chaque liste de BL, et la liste est l'écran sur lequel l'atelier travaille.
 *
 * Trois colonnes portent la sécurité, et aucune ne vient du navigateur :
 * {@code storagePath} est écrit par le serveur (donc aucun « ../ » ne peut être
 * fourni), {@code contentType} est vérifié sur les premiers octets du fichier
 * (pas sur son extension, que n'importe qui peut changer), {@code sha256} est
 * calculé sur les octets reçus (donc le fichier n'a pas été modifié après coup).
 */
@Entity
@Table(name = "maintenance_attachments")
@Getter @Setter @NoArgsConstructor
public class MaintenanceAttachment {

    @Id
    @Column(length = 50)
    private String id;

    /** Le document de maintenance (devis, BL, ordre de travail) qui porte la pièce. */
    @Column(nullable = false, length = 50)
    private String maintenanceItemId;

    /** Le nom tel que l'expéditeur l'a écrit, nettoyé : c'est ce que l'écran affiche. */
    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private Long sizeBytes;

    /** Chemin relatif au dossier de stockage : le poste de fichiers peut être déménagé. */
    @Column(nullable = false, length = 500)
    private String storagePath;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** L'adresse e-mail du compte qui a versé la pièce, reprise du jeton. */
    @Column(length = 255)
    private String uploadedBy;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;
}
