package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Journal des étapes d'une sortie : qui a fait quoi, quand, depuis quelle source.
 *
 * Une ligne ne se modifie pas et ne se supprime pas. Une heure fausse se rectifie
 * en ajoutant une ligne CORRECTED : la valeur erronée reste lisible, ce qui est
 * précisément ce qui rend le contrôle crédible — sinon le journal ne prouverait
 * rien sur celui qui l'alimente.
 */
@Entity
@Table(name = "intervention_events")
@Getter @Setter @NoArgsConstructor
public class InterventionEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 50)
    private String interventionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private InterventionEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterventionEventSource source = InterventionEventSource.SERVER;

    private String actorUserId;

    @Column(length = 255)
    private String actorEmail;

    @Column(nullable = false)
    private LocalDateTime recordedAt;

    @Column(length = 500)
    private String note;
}
