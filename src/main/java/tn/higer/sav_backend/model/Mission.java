package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * La mission : la réponse de l'atelier à une demande qui attend une décision.
 *
 * Elle ne porte aucun mot de l'appel — qui a parlé, par quel canal, à quelle heure,
 * ce qu'il attend reste sur le {@link SavCase} (V20). Elle porte la décision
 * ({@link #issue}) et les pointeurs vers les papiers qu'elle a faits naître : une
 * {@link Intervention} (DP-) si un homme est envoyé, un {@link MaintenanceItem}
 * (OR- ou DV-) si le bus passe à l'atelier ou si un devis est établi.
 *
 * Une demande a deux origines possibles, jamais les deux : un client qui a parlé
 * ({@link #savCaseId}) ou un palier de forfait qui a tourné ({@link #alertId},
 * rappel automatique — personne n'a appelé). C'est le point où les trois portes
 * d'avant se rejoignent : seule cette table fait partir un homme.
 *
 * Pas de montant : une mission affiche des numéros, l'argent vit sur le papier.
 */
@Entity
@Table(name = "missions")
@Getter @Setter @NoArgsConstructor
public class Mission {

    @Id
    @Column(length = 50)
    private String id;

    /** MT-AAAA-NNN, posé par le serveur seul. */
    @Column(nullable = false, unique = true, length = 40)
    private String number;

    /** La demande d'un client. Null quand la demande vient d'un rappel. */
    @Column(name = "sav_case_id", length = 50)
    private String savCaseId;

    /** Le rappel de palier qui réclame une visite. Null quand un client a appelé. */
    @Column(name = "alert_id", length = 50)
    private String alertId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MissionIssue issue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MissionState state;

    @Column(name = "intervention_id", length = 50)
    private String interventionId;

    @Column(name = "maintenance_item_id", length = 50)
    private String maintenanceItemId;

    @Column(length = 1000)
    private String reason;

    @Column(length = 1000)
    private String note;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    private LocalDateTime decidedAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
