package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Une pièce montée sur un bus pendant un papier de maintenance.
 *
 * Ce n'est pas une ligne de devis : les lignes du document portent un montant et
 * n'ont pas d'identité, donc personne ne peut dire « CETTE pièce ». Or un appel en
 * garantie, lui, se joue là-dessus : le constructeur demande la pièce cassée, et un
 * claim dont la pièce n'est pas revenue est annulé.
 *
 * Le bus n'est pas saisi ici — il est repris du document ({@code MaintenanceItem}),
 * par {@code ItemPartService}. Deux portes pour « dans quel bus cette pièce est
 * montée », c'est deux réponses différentes le jour où il faut les additionner.
 */
@Entity
@Table(name = "item_parts")
@Getter @Setter @NoArgsConstructor
public class ItemPart {

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "maintenance_item_id", nullable = false, length = 50)
    private String maintenanceItemId;

    @Column(name = "vehicle_id", nullable = false)
    private String vehicleId;

    /** Le dossier garantie qui réclame cette pièce au constructeur, quand il existe. */
    @Column(name = "sav_case_id", length = 50)
    private String savCaseId;

    private String reference;
    private String designation;

    private Double quantity;

    private LocalDate fittedOn;

    /** La pièce cassée est-elle repartie chez le constructeur ? */
    @Column(nullable = false)
    private boolean returned;

    @Column(name = "returned_at")
    private LocalDate returnedAt;

    /** Pourquoi elle n'est pas revenue — écrite, jamais devinée par le portail. */
    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;
}
