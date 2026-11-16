package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une pièce que l'homme dehors réclame : « ce bus ne repartira pas sans ça ».
 *
 * Ce n'est pas {@link ItemPart} (V21), qui range une pièce déjà montée pendant un
 * papier d'entretien. Ici la pièce n'est pas là : elle est vue sur place, commandée
 * au guichet, reçue au magasin, puis montée par un second passage. Chaque case
 * d'avancement correspond à un geste précis, et le portail n'en écrit aucune à la
 * place de l'humain — sauf {@link #state}, qui ne se saisit jamais : il se déduit
 * des événements ci-dessous.
 *
 * Les quatre pointeurs de la demande sont posés par le serveur et redemandés à
 * personne : {@link #interventionId} est la sortie où la pièce est née,
 * {@link #missionId} la demande d'atelier qui a fait partir la voiture (relu sur
 * {@link Mission}, seule à tenir ce lien), {@link #savCaseId} et {@link #vehicleId}
 * repris de la sortie. Sans eux, la commande ne serait rattachable ni à la
 * réclamation ni au bus — et l'atelier retomberait dans le commentaire libre où le
 * scénario s'est cassé.
 *
 * <p>{@link #fittingMissionId} vient après, posé par le serveur au moment où la
 * réception rend la ligne « Livrée » : la pièce arrivée ouvre elle-même la mission
 * qui va la monter (V32). Un seul geste d'écran n'écrit jamais deux cases — la
 * réception écrit la quantité, l'état et cette mission.</p>
 */
@Entity
@Table(name = "intervention_part_requests")
@Getter @Setter @NoArgsConstructor
public class InterventionPartRequest {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "intervention_id", nullable = false, length = 50)
    private String interventionId;

    @Column(name = "mission_id", length = 50)
    private String missionId;

    @Column(name = "sav_case_id", length = 50)
    private String savCaseId;

    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    /** Le code constructeur : il vient de Higer, ou la case reste vide. */
    @Column(length = 120)
    private String reference;

    @Column(columnDefinition = "TEXT")
    private String designation;

    /** Pourquoi cette pièce, dans les mots de l'homme sur place. */
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "quantity_ordered", nullable = false)
    private Integer quantityOrdered;

    /** Jamais saisi : déduit des événements par {@code InterventionPartService}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartRequestState state;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "ordered_by", length = 120)
    private String orderedBy;

    @Column(name = "quantity_received")
    private Integer quantityReceived;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "received_by", length = 120)
    private String receivedBy;

    /**
     * La mission « changer la pièce » que la livraison a fait naître, posée par le
     * serveur. Elle porte le geste du second passage : c'est par elle qu'une sortie
     * qui se clôt retrouve les lignes à marquer « Posée », et c'est elle qui prouve
     * qu'une même pièce n'a pas ouvert deux demandes.
     */
    @Column(name = "fitting_mission_id", length = 50)
    private String fittingMissionId;

    @Column(name = "fitted_at")
    private LocalDateTime fittedAt;

    /** La seconde sortie qui a monté la pièce. */
    @Column(name = "fitted_intervention_id", length = 50)
    private String fittedInterventionId;

    /** Le compte qui a clôturé la sortie de pose — le nom qui écrit « Posée ». */
    @Column(name = "fitted_by", length = 120)
    private String fittedBy;

    /** Qui a demandé la pièce (l'adresse du compte, comme au journal de la sortie). */
    @Column(name = "demanded_by", length = 120)
    private String demandedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
