package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Une ligne de la fiche atelier d'un dossier groupe.
 *
 * L'atelier écrit les lignes en préparant le dossier ; l'homme les coche sur
 * place. Une ligne non cochée ne bloque pas le retour : elle reste rouge dans
 * le dossier, et c'est l'atelier qui la voit.
 */
@Entity
@Table(name = "mission_group_checklist_items")
@Getter @Setter @NoArgsConstructor
public class MissionGroupChecklistItem {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "dossier_id", nullable = false, length = 50)
    private String dossierId;

    /** L'ordre de la fiche : les choses se lisent dans l'ordre où elles se font. */
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
