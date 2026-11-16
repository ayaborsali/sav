package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "maintenance_items")
@Getter @Setter @NoArgsConstructor
public class MaintenanceItem {

    @Id
    @Column(length = 50)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaintenanceType type;

    @Column(nullable = false, unique = true)
    private String number;

    @Column(nullable = false)
    private String vehicleId;

    private String clientId;

    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MaintenanceStatus status;

    /**
     * Les trois dates de l'accord du client et le motif de son refus — écrites par le
     * serveur, au clic qui envoie le devis, enregistre l'accord ou enregistre le refus
     * ({@code DevisService}). Le poste ne les tape pas : une date d'accord saisie à la
     * main est une date qu'on peut reculer quand le prix dérange.
     *
     * Le scan signé que le client a renvoyé n'est pas ici : c'est une pièce jointe du
     * document, posée sur le disque, comme le reste des preuves.
     */
    @Column(name = "sent_on")
    private LocalDate sentOn;

    @Column(name = "accepted_on")
    private LocalDate acceptedOn;

    @Column(name = "refused_on")
    private LocalDate refusedOn;

    @Column(name = "refusal_reason", columnDefinition = "TEXT")
    private String refusalReason;

    /**
     * La clôture de l'ordre de travail : la date du clic (« Clôturer l'ordre »), ce qui
     * a été <b>constaté</b> sur le bus pendant le travail, et la raison écrite qui
     * tient lieu de preuve quand le cliché « après » ou la fiche d'atelier signée
     * n'existe pas.
     *
     * Les trois s'écrivent par {@code OrdreService}, jamais par le formulaire. Une
     * date de fin tapée à la main est une date qu'on rapproche quand la prime se
     * discute, et une anomalie tue le jour du travail est une anomalie qui ne sera
     * jamais devisée chez le client.
     */
    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(columnDefinition = "TEXT")
    private String anomalies;

    @Column(name = "closure_reason", columnDefinition = "TEXT")
    private String closureReason;

    /**
     * La réception du bus par le client, sur le bon de livraison : qui a signé et à quel
     * titre, ce qu'il a constaté en reprenant le bus, si les pièces remplacées sont
     * reparties avec lui, et le compteur lu à la restitution.
     *
     * Le nom et la qualité sont <b>saisis</b> : le portail n'a pas de compte client, et
     * le chauffeur qui signe ne vaut pas le directeur qui engage. {@code signedOn} est
     * posé par le serveur au clic sur « Marquer signé » ({@code BonLivraisonService}),
     * jamais tapé — une date de restitution saisie à la main est une date qu'on recule
     * quand la garantie se discute.
     *
     * {@code mileageAtDelivery} n'est pas une copie décorative de la fiche du bus : la
     * valeur rejoint {@code vehicles.mileage} par la porte unique du compteur
     * ({@code VehicleMileageService}), qui refuse une baisse sans motif écrit. C'est
     * elle qui fait avancer le rappel du palier suivant.
     */
    @Column(name = "signataire_nom")
    private String signataireNom;

    @Column(name = "signataire_qualite")
    private String signataireQualite;

    @Column(name = "signed_on")
    private LocalDate signedOn;

    @Column(columnDefinition = "TEXT")
    private String reserves;

    @Column(name = "parts_returned")
    private Boolean partsReturned;

    @Column(name = "mileage_at_delivery")
    private Long mileageAtDelivery;

    /**
     * Le forfait vendu auquel ce document se rattache, et le palier qu'il exécute.
     * Les deux sont facultatifs : un devis ou un BL rendu hors forfait reste un
     * document complet. Quand ils sont remplis, SoldPlanService vérifie que le bus
     * du document est bien celui du forfait et que le palier appartient à ce
     * forfait — et fait avancer le suivi du palier avec le statut du document.
     */
    @Column(name = "sold_plan_id", length = 50)
    private String soldPlanId;

    /** L'identifiant du palier du catalogue (MaintenancePlanStep), pas celui de la ligne de suivi. */
    @Column(name = "plan_step_id", length = 50)
    private String planStepId;

    /**
     * La demande (mission MT-) qui a fait ouvrir ce papier, et le papier dont il est
     * né. Les deux s'écrivent côté serveur, au clic qui crée le suivant : un ordre
     * né d'un devis, un bon de livraison né d'un ordre. Facultatifs — la plupart des
     * documents restent des points de départ.
     */
    @Column(name = "mission_id", length = 50)
    private String missionId;

    @Column(name = "source_item_id", length = 50)
    private String sourceItemId;

    /**
     * L'argent du papier. Le devis le porte — c'est son usage. Depuis V38, l'ordre de
     * travail ne le chiffre plus : il décrit le travail, il ne le facture pas, et un
     * prix recopié du devis sur l'ordre est une double facture en préparation. Les
     * montants posés avant ce jour restent en base et se lisent : le portail n'efface
     * pas ce qu'il a enregistré, il cesse d'en écrire.
     */
    private Double totalHT;
    private Double totalTTC;

    private String technician;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "maintenance_details", joinColumns = @JoinColumn(name = "maintenance_id"))
    @OrderColumn(name = "line_order")
    private List<MaintenanceDetail> details = new ArrayList<>();
}
