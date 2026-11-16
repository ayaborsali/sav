package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan de maintenance d'une gamme de bus (Confort ou Standard) : le document de
 * référence que l'atelier vend en forfait (« BUS HIGER 26 WBE – URBAIN », environ
 * 80 000 km par an, vidange tous les 10 000 km ou 45 jours, plan sur 3 ans).
 *
 * Ce n'est PAS un forfait vendu : le plan décrit ce qui doit être fait, la table
 * des forfaits vendus (phase B) dira pour quel bus et à quelles dates. Un plan
 * peut exister sans jamais être vendu, et un bus peut être entretenu sans plan.
 *
 * Les paliers et les opérations suivent le plan à la vie à la mort : modifier un
 * plan, c'est réécrire sa grille entière. C'est ce que l'écran fait, et ce que
 * {@code orphanRemoval} garantit — sinon une ligne retirée de la grille resterait
 * en base à attendre un palier supprimé.
 */
@Entity
@Table(name = "maintenance_plans")
@Getter @Setter @NoArgsConstructor
public class MaintenancePlan {

    @Id
    @Column(length = 50)
    private String id;

    /** Nom du plan, unique : c'est ce que l'atelier cite quand il propose un forfait. */
    @Column(nullable = false, unique = true, length = 160)
    private String name;

    @Column(name = "bus_model", length = 120)
    private String busModel;

    /** Kilométrage d'usage annuel, tel que le document Higer l'annonce. */
    @Column(name = "annual_km")
    private Long annualKm;

    @Column(name = "oil_change_interval_km")
    private Integer oilChangeIntervalKm;

    @Column(name = "oil_change_interval_days")
    private Integer oilChangeIntervalDays;

    @Column(name = "duration_years")
    private Integer durationYears;

    /**
     * Un plan retiré du catalogue reste en base : les forfaits vendus d'avant y
     * font encore référence. Le drapeau evite qu'on le propose à une nouvelle
     * vente sans effacer l'historique.
     */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepOrder ASC")
    private List<MaintenancePlanStep> steps = new ArrayList<>();

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MaintenancePlanOperation> operations = new ArrayList<>();
}
