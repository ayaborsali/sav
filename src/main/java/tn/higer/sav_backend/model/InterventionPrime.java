package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Prime d'intervention validée pour une sortie : le seul endroit du projet où
 * Saphir écrit ce qu'elle paie à un technicien pour une sortie.
 *
 * Trois règles, toutes prises pour le contrôle voulu :
 * 1. le montant part des horodatages d'étape, jamais d'une saisie ;
 * 2. le taux et le forfait sont RECOPIÉS à la validation. Le technicien est
 *    peut-être augmenté en mars : la prime de janvier reste ce qu'elle a été
 *    payée, sinon la paie devient indiscutable ;
 * 3. une ligne validée ne se modifie plus, et une seconde ligne pour la même
 *    sortie est refusée par la base. Une erreur se corrige par une rectification
 *    au journal de la sortie, pas en écrasant un montant payé.
 *
 * Les noms camelCase deviennent snake_case en base, comme partout dans le projet.
 */
@Entity
@Table(name = "intervention_primes")
@Getter @Setter @NoArgsConstructor
public class InterventionPrime {

    @Id
    @Column(length = 50)
    private String id;

    /** Une prime par sortie : la contrainte unique empêche le double paiement. */
    @Column(unique = true, nullable = false, length = 50)
    private String interventionId;

    /** Durée retenue par le serveur, en minutes brutes. */
    @Column(nullable = false)
    private Long paidMinutes;

    /** Minutes arrondies au quart d'heure, exprimées en heures. */
    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal billableHours;

    @Column(precision = 10, scale = 3)
    private BigDecimal hourlyRate;

    @Column(precision = 10, scale = 3)
    private BigDecimal dispatchAllowance;

    @Column(precision = 12, scale = 3)
    private BigDecimal amount;

    /** Signaux trouvés au calcul, séparés par « ; ». Vide = rien à justifier. */
    @Column(length = 500)
    private String anomalies;

    /** Ce qu'a écrit le chef d'atelier pour valider malgré les signaux. */
    @Column(length = 500)
    private String justification;

    @Column(length = 255)
    private String validatedByEmail;

    @Column(nullable = false)
    private LocalDateTime validatedAt;
}
