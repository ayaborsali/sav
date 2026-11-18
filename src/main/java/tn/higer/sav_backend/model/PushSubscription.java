package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Un téléphone qui accepte d'être réveillé : l'adresse que le service de poussée
 * du navigateur a donnée, et les deux clés qui permettent de chiffrer pour lui.
 *
 * L'adresse est unique dans toute la table : le même téléphone ne s'abonne
 * qu'une fois. Si un autre compte se connecte dessus, la ligne change de
 * propriétaire ({@code user_id}) au lieu d'en créer une seconde — sinon
 * l'ancien compte continuerait de faire sonner le téléphone du nouveau.
 *
 * {@code last_seen_at} est l'heure du dernier réabonnement, pas celle du dernier
 * envoi : une ligne vivante est celle que le téléphone a redonnée récemment.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter @Setter @NoArgsConstructor
public class PushSubscription {

    @Id
    @Column(length = 50)
    private String id;

    /** Le compte du portail à qui ce téléphone appartient (AppUser.id, en texte). */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    @Column(nullable = false, length = 255)
    private String p256dh;

    @Column(nullable = false, length = 255)
    private String auth;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
