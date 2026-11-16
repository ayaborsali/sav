package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une alerte du niveau 3 : ce que LE TEMPS a signalé sur une sortie.
 *
 * Le module est venu contrôler des techniciens qui toucheraient une prime sans
 * travailler ; une alerte est la trace que quelque chose n'avance pas. Elle ne
 * juge personne : elle pose la question, et un humain y répond.
 *
 * Deux fins, volontairement séparées :
 *  - {@code resolved} sans {@code waived} : la situation s'est réglée toute seule
 *    (le cliché est arrivé, le bus est rentré). Personne n'a eu à décider ;
 *  - {@code waived} : une décision écrite a levé l'alerte alors que le signal
 *    restait vrai. La raison, son auteur et l'heure restent sur la ligne.
 *
 * Une alerte levée sans raison n'existe pas : le service l'exige avant d'écrire.
 * detected_at ne bouge jamais après le premier déclenchement, pour qu'une alerte
 * qui traîne depuis trois semaines ne paraisse pas fraîche.
 */
@Entity
@Table(name = "intervention_alerts")
@Getter @Setter @NoArgsConstructor
public class InterventionAlert {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 50)
    private String interventionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private InterventionAlertKind kind;

    /** La règle telle qu'elle a été évaluée, en français, avec ses chiffres. */
    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    @Column(nullable = false)
    private boolean resolved;

    private LocalDateTime resolvedAt;

    /** true = levée par une décision écrite, pas parce que la situation s'est réglée. */
    @Column(nullable = false)
    private boolean waived;

    @Column(length = 500)
    private String waiverReason;

    @Column(length = 255)
    private String waivedByEmail;

    private LocalDateTime waivedAt;
}
