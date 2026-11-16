package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Dossier de sortie de dépannage : qui est parti, quand, avec quel véhicule,
 * où, ce qui a été fait, et le kilométr relevé.
 *
 * IMPORTANT — les sept colonnes d'horodatage d'étape (*At) sont écrites par le
 * serveur au moment où le bouton correspondant est pressé. Aucune n'est saisie
 * depuis un écran : le but du module est de contrôler les primes, et une heure
 * tapée à la main ne contrôle rien. Seule reportedAt est déclarée par un humain,
 * parce qu'elle précède la sortie (l'appel du client).
 *
 * Le numéro (DP-AAAA-NNN) est attribué par le serveur, jamais par le navigateur.
 * Les montants ne sont pas ici : ils se calculent à partir de ces horodatages
 * (lot D3), ce qui rend une prime impossible à « saisir librement ».
 *
 * Les noms de champs sont en camelCase et deviennent snake_case en base comme
 * partout ailleurs dans le projet (stratégie de nommage Spring Boot).
 */
@Entity
@Table(name = "interventions")
@Getter @Setter @NoArgsConstructor
public class Intervention {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, unique = true, length = 40)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InterventionStatus status;

    /** Bus du client en panne ({@code vehicles.id}). */
    @Column(nullable = false, length = 50)
    private String vehicleId;

    @Column(nullable = false, length = 255)
    private String clientId;

    /** Ressource affectée ({@code technicians.id}) ; pas un compte de connexion. */
    @Column(length = 50)
    private String technicianId;

    @Column(length = 50)
    private String serviceVehicleId;

    /** Compte qui a ouvert la sortie (le chef d'atelier). */
    @Column(length = 50)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime reportedAt;

    /** Nom libre de la personne qui a appelé l'atelier : une fiche appel, pas un compte. */
    @Column(length = 120)
    private String requestedBy;

    @Column(length = 500)
    private String locationAddress;

    @Column(length = 120)
    private String locationCity;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SavPriority priority;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime dispatchedAt;
    private LocalDateTime departedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime leftSiteAt;
    private LocalDateTime returnedAt;
    private LocalDateTime closedAt;

    /** Kilomètres de la voiture de déplacement au départ, relevés par le technicien. */
    private Long startMileage;
    private Long endMileage;

    /** Kilomètres affichés par le bus en panne au moment de l'incident. */
    private Long busMileage;

    /** Compteur du bus relevé à la fin de l'intervention, sur place. */
    private Long busMileageEnd;

    /** Code défaut Higer (feuille « Codes Defaut » du claim) ; texte libre, la liste vient du fabricant. */
    @Column(length = 40)
    private String defectCode;

    @Column(columnDefinition = "TEXT")
    private String diagnosis;

    @Column(length = 120)
    private String causingPart;

    @Column(length = 1000)
    private String resultNote;

    /** Qui a repris le bus à l'atelier. */
    @Column(length = 120)
    private String receivedBy;

    @Column(length = 1000)
    private String closureNote;

    /** Rattachement SAV / appel en garantie : optionnel, posé à la clôture. */
    @Column(length = 50)
    private String savCaseId;

    /**
     * Ce que la sortie est allée faire : une panne à réparer, ou une visite due au
     * forfait du bus. Posé par le serveur à la naissance de la sortie — un appel
     * déclaré donne PANNE, un rappel de palier donne ENTRETIEN — et jamais repris
     * depuis un écran : le motif décide de ce que la clôture va cocher.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterventionPurpose purpose;

    /** L'engagement que la sortie exécute ({@code sold_plans.id}) ; vide pour une panne. */
    @Column(name = "sold_plan_id", length = 50)
    private String soldPlanId;

    /** Le palier du catalogue que la sortie vient faire ({@code maintenance_plan_steps.id}). */
    @Column(name = "plan_step_id", length = 50)
    private String planStepId;

    /**
     * Le papier d'entretien que la clôture a créé pour ce palier. Écrit après coup,
     * jamais à l'envers : une sortie d'entretien qui n'a rien coché (palier déjà fait
     * par un autre document, forfait fermé entre-temps) laisse la case vide et le
     * journal explique pourquoi.
     */
    @Column(name = "maintenance_item_id", length = 50)
    private String maintenanceItemId;

    private LocalDateTime updatedAt;
}
