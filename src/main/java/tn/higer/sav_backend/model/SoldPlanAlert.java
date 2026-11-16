package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Le rappel qu'un palier d'un forfait vendu est à faire.
 *
 * Personne ne l'écrit : c'est le serveur qui le déclenche en comparant le compteur
 * connu du bus au kilométrage du palier, ou la date du forfait à ses jours. La
 * ligne ne juge personne et ne facture rien — elle pose la question, et un humain y
 * répond.
 *
 * {@code soldPlanStepId} est la clé du signal : une seule alerte ouverte par ligne
 * de suivi, parce que le scan repose la même question périodiquement et qu'une
 * liste qui doubonne ne se lit plus. Le forfait est porté avec elle pour que
 * l'écran groupe les rappels sans charger les fiches.
 *
 * Deux fins, séparées comme pour les alertes de dépannage :
 *  - {@code resolved} sans {@code waived} : le palier a été fait par un document
 *    livré, le forfait est clôturé ou annulé, ou un compteur corrigé à la baisse a
 *    rendu la borne de nouveau non atteinte. Personne n'a eu à décider ;
 *  - {@code waived} : une décision écrite a levé le rappel alors que le signal
 *    restait vrai. La raison, son auteur et l'heure restent sur la ligne.
 *
 * {@code detectedAt} ne bouge jamais après le premier déclenchement : un rappel qui
 * traîne depuis trois semaines ne doit pas paraître frais.
 */
@Entity
@Table(name = "sold_plan_alerts")
@Getter @Setter @NoArgsConstructor
public class SoldPlanAlert {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "sold_plan_id", nullable = false, length = 50)
    private String soldPlanId;

    /** La ligne de suivi du palier : ce qui porte le signal. */
    @Column(name = "sold_plan_step_id", nullable = false, length = 50)
    private String soldPlanStepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SoldPlanAlertKind kind;

    /** La règle telle qu'elle a été évaluée, en français, avec ses chiffres. */
    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** true = levée par une décision écrite, pas parce que le palier s'est fait. */
    @Column(nullable = false)
    private boolean waived;

    @Column(name = "waiver_reason", length = 500)
    private String waiverReason;

    @Column(name = "waived_by_email", length = 255)
    private String waivedByEmail;

    @Column(name = "waived_at")
    private LocalDateTime waivedAt;

    /**
     * La sortie d'entretien déjà envoyée pour CE palier. Elle porte la prise en
     * charge : deux clics sur « envoyer » ne doivent pas faire sortir deux hommes
     * pour la même visite, et le bandeau doit dire qu'une équipe roule déjà au lieu
     * de laisser croire que rien n'a été fait.
     *
     * Le rappel reste ouvert tant que le palier n'est pas fait : une sortie envoyée
     * n'est pas une visite accomplie. Ce qui ferme la ligne, c'est le papier né de sa
     * clôture — ou une raison écrite.
     */
    @Column(name = "intervention_id", length = 50)
    private String interventionId;
}
