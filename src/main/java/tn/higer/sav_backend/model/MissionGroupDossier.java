package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Le dossier d'un déplacement de l'atelier : un client, une voiture, un homme,
 * un départ prévu puis un départ réel, enfin un retour.
 *
 * Il ne porte ni le problème ni le diagnostic : ces deux mots appartiennent au
 * bus travaillé, donc à {@link MissionGroupBus}. Un dossier peut couvrir un seul
 * bus ou plusieurs, mais chaque mission reste attachée à son bus.
 *
 * L'atelier prépare le papier (client, bus, homme, voiture, départ prévu) ; le
 * technicien affecté pointe le départ réel, le retour et les photos de la
 * voiture. Le kilométrage est celui de la voiture de déplacement, pas celui des
 * bus : un seul compteur pour un seul véhicule qui fait la route.
 *
 * « En déplacement » et « rentré » ne sont pas des colonnes : le premier se lit
 * sur {@link #departedAt}, le second sur {@link #returnedAt}. Écrire un état en
 * plus des dates donnerait deux vérités à tenir à jour.
 */
@Entity
@Table(name = "mission_group_dossiers")
@Getter @Setter @NoArgsConstructor
public class MissionGroupDossier {

    @Id
    @Column(length = 50)
    private String id;

    /** Proposé par le serveur, modifiable à la saisie pour recopier le registre papier. */
    @Column(nullable = false, unique = true, length = 40)
    private String number;

    /** Le client du portefeuille ; {@link #clientName} en est la recopie du jour du papier. */
    @Column(name = "client_id", length = 64)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 255)
    private String clientName;

    /** Par où l'appel est arrivé : téléphone, WhatsApp, email, sur place ou autre. */
    @Enumerated(EnumType.STRING)
    @Column(name = "call_channel", length = 40)
    private MissionCallChannel callChannel;

    /** L'heure que l'atelier prévoit (« demain 8 h ») ; le départ réel est pointé par le technicien. */
    @Column(name = "planned_departure_at")
    private LocalDateTime plannedDepartureAt;

    /**
     * L'heure de retour que l'atelier prévoit. Avec le départ prévu, elle forme la
     * période qui dit si un homme ou une voiture est déjà pris.
     */
    @Column(name = "planned_return_at")
    private LocalDateTime plannedReturnAt;

    /** Le kilométrage annoncé de la voiture au départ et au retour estimés. */
    @Column(name = "planned_departure_mileage")
    private Long plannedDepartureMileage;

    @Column(name = "planned_return_mileage")
    private Long plannedReturnMileage;

    /** Null tant que le technicien n'a pas pointé le départ réel. */
    @Column(name = "departed_at")
    private LocalDateTime departedAt;

    /** Kilométrage de la voiture de déplacement au départ réel. */
    @Column(name = "departure_mileage")
    private Long departureMileage;

    /** L'homme affecté au dossier (compte du portail) et son nom recopié. */
    @Column(name = "technician_user_id", length = 64)
    private String technicianUserId;

    @Column(name = "technician_name", length = 255)
    private String technicianName;

    /** La voiture de déplacement affectée et son immatriculation recopiée. */
    @Column(name = "service_vehicle_id", length = 64)
    private String serviceVehicleId;

    @Column(name = "service_vehicle_registration", length = 120)
    private String serviceVehicleRegistration;

    /** Null tant que la voiture n'est pas rentrée : c'est l'état « en déplacement ». */
    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "return_mileage")
    private Long returnMileage;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
