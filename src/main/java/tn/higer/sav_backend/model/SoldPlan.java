package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Un forfait de maintenance vendu : un plan du catalogue, attribué à un client
 * pour UN bus précis, avec des dates.
 *
 * Ce n'est ni la garantie constructeur ni un contrat de vente : c'est un paquet
 * d'entretien payé à l'avance, que l'atelier délivre palier par palier. Le bus est
 * unique parce que le suivi se fait à l'odomètre de ce bus ; le client est porté
 * séparément du bus pour qu'on puisse dire à qui le forfait a été vendu même si la
 * fiche du véhicule bouge.
 *
 * Le plan n'est jamais recopié ici : {@code planId} reste la référence, et le plan
 * d'un forfait vendu ne s'efface pas (retrait = {@code active = false}). Ce qui
 * est recopié en revanche, c'est le SUIVI : une ligne {@link SoldPlanStep} par
 * palier, créée à la vente, parce que le palier du catalogue décrit une visite
 * tandis que cette ligne dira si CE bus l'a faite, quand, à quel compteur et sur
 * quel document.
 */
@Entity
@Table(name = "sold_plans")
@Getter @Setter @NoArgsConstructor
public class SoldPlan {

    @Id
    @Column(length = 50)
    private String id;

    /** Le plan du catalogue vendu. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private MaintenancePlan plan;

    /** À qui le forfait a été vendu. Doit être le propriétaire du bus : vérifié au service. */
    @Column(name = "client_id", nullable = false, length = 50)
    private String clientId;

    /** Le bus concerné. */
    @Column(name = "vehicle_id", nullable = false, length = 50)
    private String vehicleId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Fin du forfait. Null = l'engagement court jusqu'à sa clôture. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Prix du forfait HT, nullable : les prix ne sont pas encore connus. Une fois
     * la vente enregistrée, c'est ce chiffre qui sert de référence — pas le
     * catalogue, qui peut changer après.
     */
    @Column(name = "price_ht", precision = 10, scale = 2)
    private BigDecimal priceHt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SoldPlanStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Le suivi : une ligne par palier du plan, créée à la vente. Le service refuse
     * de changer le plan d'un forfait déjà vendu, donc ces lignes ne sont jamais
     * reprises — elles ne font que passer de TODO à DONE.
     */
    @OneToMany(mappedBy = "soldPlan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SoldPlanStep> steps = new ArrayList<>();
}
