package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une ligne de la fiche atelier d'une sortie de dépannage.
 *
 * Même règle que pour un dossier groupe : l'atelier écrit la fiche au départ,
 * l'homme la coche sur place, et une ligne oubliée reste rouge sans bloquer le
 * retour.
 */
@Entity
@Table(name = "intervention_checklist_items")
@Getter @Setter @NoArgsConstructor
public class InterventionChecklistItem {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "intervention_id", nullable = false, length = 50)
    private String interventionId;

    @Column(name = "line_order", nullable = false)
    private Integer position;

    @Column(nullable = false, length = 300)
    private String label;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "done_at")
    private LocalDateTime doneAt;

    @Column(name = "done_by", length = 255)
    private String doneBy;
}
