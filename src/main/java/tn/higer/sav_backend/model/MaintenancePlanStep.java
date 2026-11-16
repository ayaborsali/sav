package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.List;

/**
 * Un palier du plan : une visite, définie par un kilométrage et/ou un nombre de
 * jours (« visite des 45 000 km », « révision des 12 mois »).
 *
 * Les deux bornes sont nullables séparément mais jamais ensemble : un palier sans
 * kilométrage ni durée ne peut être atteint par aucun bus, et il bloquerait le
 * suivi du forfait vendu en silence. La règle est vérifiée au service.
 *
 * Le prix est nullable parce que les prix ne sont pas encore connus. Un palier
 * sans prix reste un palier complet côté travaux ; seul son chiffrage attend.
 */
@Entity
@Table(name = "maintenance_plan_steps")
@Getter @Setter @NoArgsConstructor
public class MaintenancePlanStep {

    @Id
    @Column(length = 50)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private MaintenancePlan plan;

    /** Rang dans le plan, posé par le serveur dans l'ordre reçu. */
    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "km")
    private Long km;

    @Column(name = "days")
    private Integer days;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(name = "price_ht", precision = 10, scale = 2)
    private BigDecimal priceHt;

    /** Les croix de la grille : les opérations qui tombent à ce palier. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "maintenance_plan_step_operations",
            joinColumns = @JoinColumn(name = "step_id"),
            inverseJoinColumns = @JoinColumn(name = "operation_id"))
    private List<MaintenancePlanOperation> operations = new ArrayList<>();
}
