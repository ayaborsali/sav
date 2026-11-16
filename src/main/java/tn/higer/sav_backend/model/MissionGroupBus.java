package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une mission d'un dossier groupe : un bus, le problème constaté, le diagnostic.
 *
 * L'immatriculation est la clé de lecture de l'historique. Elle est doublée d'une
 * forme normalisée ({@link #registrationKey}) : deux façons d'écrire la même
 * plaque ne doivent pas couper l'historique du bus en deux.
 */
@Entity
@Table(name = "mission_group_buses")
@Getter @Setter @NoArgsConstructor
public class MissionGroupBus {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "dossier_id", nullable = false, length = 50)
    private String dossierId;

    /** Ordre de la ligne à l'écran : le papier se relit dans l'ordre où il a été écrit. */
    @Column(name = "line_order", nullable = false)
    private Integer position;

    /** Le bus du portefeuille, choisi parmi ceux du client du dossier. */
    @Column(name = "vehicle_id", length = 64)
    private String vehicleId;

    /** Immatriculation recopiée le jour du papier : une plaque changée ne réécrit pas l'histoire. */
    @Column(nullable = false, length = 120)
    private String registration;

    /** Forme normalisée (majuscules, espaces réduits) : la clé de l'historique. */
    @Column(name = "registration_key", nullable = false, length = 120)
    private String registrationKey;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String diagnosis;

    /** Le code d'erreur lu sur le bus, devant le bus. */
    @Column(name = "defect_code", length = 80)
    private String defectCode;

    /** La pièce en cause, s'il y en a une — celle qu'on commande. */
    @Column(name = "causing_part", length = 255)
    private String causingPart;

    /** Ce que l'homme a vu et fait, en ses mots. */
    @Column(columnDefinition = "TEXT")
    private String observations;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
