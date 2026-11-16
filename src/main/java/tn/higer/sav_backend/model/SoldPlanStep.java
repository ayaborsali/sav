package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * L'avancement d'un palier du plan, pour un forfait vendu.
 *
 * La ligne ne porte que le suivi : le kilométrage, le libellé et le prix du palier
 * se lisent sur {@link MaintenancePlanStep}. C'est possible parce que le plan
 * d'un forfait vendu reste en base (retrait = {@code active = false}, jamais un
 * effacement) et parce que les paliers d'un plan gardent leur identifiant quand on
 * modifie la grille — {@code MaintenancePlanService} le vérifie et refuse de
 * retirer un palier déjà engagé sur un forfait.
 *
 * {@code done_at}, {@code maintenance_item_id} et {@code mileage_at_service} sont
 * remplis phase C, quand un devis / BL / opération est livré ou clôturé : à la
 * vente, toutes les lignes naissent à TODO avec ces trois cases vides.
 */
@Entity
@Table(name = "sold_plan_steps",
        uniqueConstraints = @UniqueConstraint(name = "sold_plan_steps_plan_step_key",
                columnNames = {"sold_plan_id", "step_id"}))
@Getter @Setter @NoArgsConstructor
public class SoldPlanStep {

    @Id
    @Column(length = 50)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sold_plan_id", nullable = false)
    private SoldPlan soldPlan;

    /** Le palier du catalogue. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    private MaintenancePlanStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SoldPlanStepStatus status;

    /** Date du passage, posée par le serveur quand le palier devient DONE. */
    @Column(name = "done_at")
    private LocalDateTime doneAt;

    /** Le document de maintenance qui a fait le palier (null = fait hors portail ou pas encore saisi). */
    @Column(name = "maintenance_item_id", length = 50)
    private String maintenanceItemId;

    /** Compteur relevé au passage, en kilomètres. */
    @Column(name = "mileage_at_service")
    private Long mileageAtService;
}
