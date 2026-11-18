package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Ce qui a déjà sonné : la mémoire courte des notifications régulières.
 *
 * Une seule règle en a besoin — « ton départ prévu est passé ». Le scan la
 * repose toutes les dix minutes tant que le départ n'est pas pointé ; sans cette
 * ligne, le téléphone sonnerait six fois par heure pour la même chose. La
 * référence est la valeur qui rend le fait unique : l'heure du départ prévu,
 * pour qu'un départ reporté par l'atelier soit une nouvelle annonce.
 *
 * Une ligne ne s'écrit que si l'envoi a atteint au moins un téléphone : un
 * technicien qui s'abonne ce soir doit encore recevoir l'alerte de la journée.
 */
@Entity
@Table(name = "push_sent")
@Getter @Setter @NoArgsConstructor
public class PushSent {

    @Id
    @Column(length = 50)
    private String id;

    /** Le genre d'annonce : DEPART pour l'instant, la colonne en laisse la place. */
    @Column(nullable = false, length = 40)
    private String kind;

    /** Le dossier ou la sortie concernée. */
    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
