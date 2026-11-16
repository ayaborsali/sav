package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Une ligne du plan : ce que l'atelier fait sur un organe (« moteur », « boîte »,
 * « freins », « climatisation », « batterie »).
 *
 * La quantité est un texte (« 23 L », « 53 L », « 2 ») parce que le document
 * Higer écrit l'unité dans la même case. La transformer en nombre perdrait
 * l'information à recopier sur le bon de travail, et l'atelier relit ce texte,
 * il ne le calcule pas.
 *
 * Le prix unitaire est nullable : les prix viennent après le plan. Quand une
 * opération est chargée dans un document, son prix est COPIÉ dans la ligne du
 * document — un devis ou un BL ne pointe jamais vers le catalogue, sinon changer
 * un prix ici réécrirait les papiers déjà signés.
 */
@Entity
@Table(name = "maintenance_plan_operations")
@Getter @Setter @NoArgsConstructor
public class MaintenancePlanOperation {

    @Id
    @Column(length = 50)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private MaintenancePlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private PlanOperationType operationType;

    @Column(length = 80)
    private String organ;

    /** La désignation de l'article, telle qu'elle ira sur le document de travail. */
    @Column(length = 300)
    private String article;

    /** Référence pièce ou nom du lubrifiant (« 15W-40 », « Higer 12345-A »). */
    @Column(length = 120)
    private String reference;

    @Column(name = "quantity_text", length = 60)
    private String quantityText;

    /** Intervalle de la tâche, en kilomètres, tel que le plan l'annonce. */
    @Column(name = "frequency_km")
    private Integer frequencyKm;

    @Column(name = "unit_price_ht", precision = 10, scale = 2)
    private BigDecimal unitPriceHt;
}
