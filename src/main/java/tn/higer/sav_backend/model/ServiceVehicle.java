package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Voiture de déplacement : le véhicule qui part en intervention (dépanneuse, fourgon d'atelier).
 *
 * Table séparée de {@code vehicles} : celle-ci exige un client et un VIN unique,
 * ce qu'un véhicule d'atelier n'a pas. Un bus client et une dépanneuse ne sont
 * pas la même notion et ne doivent pas partager une contrainte.
 *
 * Le kilométrage n'est pas un historique ici : les relevés de départ et de
 * retour vivent sur chaque {@link Intervention}. current_mileage n'est que le
 * dernier connu, pour le tableau de flotte.
 */
@Entity
@Table(name = "service_vehicles")
@Getter @Setter @NoArgsConstructor
public class ServiceVehicle {

    @Id
    @Column(length = 50)
    private String id;

    /** Immatriculation, unique : c'est la seule identité durable d'un véhicule d'atelier. */
    @Column(nullable = false, unique = true, length = 40)
    private String registration;

    @Column(nullable = false, length = 120)
    private String label;

    /**
     * Type de voiture (« Dépanneuse », « Fourgon d'atelier », « Pick-up »). Laissé en
     * texte libre volontairement : la liste dépend du parc réel, et une énumération
     * imposerait une migration pour chaque ajout.
     */
    @Column(nullable = false, length = 30)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ServiceVehicleStatus status = ServiceVehicleStatus.AVAILABLE;

    private Long currentMileage;

    /**
     * Réservé à la télémétrie : vide aujourd'hui. Le capteur GPS du véhicule de
     * service pourra y être rattaché sans modifier le schéma ni les écrans.
     */
    @Column(name = "gps_device_id", length = 60)
    private String gpsDeviceId;

    @Column(name = "gps_provider", length = 60)
    private String gpsProvider;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 500)
    private String notes;
}
