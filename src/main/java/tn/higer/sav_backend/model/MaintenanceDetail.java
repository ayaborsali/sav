package tn.higer.sav_backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une ligne d'un document de maintenance (devis, BL, ordre de travail).
 *
 * {@code quantity} est le nombre qui entre dans le total. {@code quantityText}
 * garde la quantité telle que le plan Higer l'écrit, unité comprise (« 23 L »,
 * « 6.5 kg ») : le document de référence met l'unité dans la même case que le
 * nombre, et le seul endroit où l'atelier peut la relire est la ligne elle-même.
 * Les deux sont nullables — une ligne saisie à la main n'a pas de texte, une ligne
 * du catalogue sans nombre lisible n'a pas de quantité comptable.
 *
 * {@code nature} et {@code suppliedBy} sont le devis qui se lit : ce que la ligne
 * chiffre (main-d'œuvre, pièce, déplacement) et, pour une pièce, qui la fournit.
 * Ils sont nullables EN BASE parce qu'une ligne de catalogue n'a pas à le savoir,
 * mais {@code DevisService} les exige sur un devis — un total sans nature ne dit
 * à personne ce qu'il facture.
 *
 * {@code done} et {@code realHours} sont l'ordre de travail qui se lit : l'opération
 * a-t-elle été faite, et combien d'heures elle a vraiment pris. Le catalogue prévoit
 * ({@code quantityText} porte le temps standard), l'atelier coche et écrit les
 * heures. Les deux sont nullables — un devis n'a rien à cocher, il ne travaille pas —
 * mais {@code OrdreService} les réclame à la clôture, parce que c'est là que le
 * papier devient la preuve que quelqu'un a touché au bus.
 */
@Embeddable
@Getter @Setter @NoArgsConstructor
public class MaintenanceDetail {

    private String reference;
    private String label;
    private Double quantity;
    private Double unitPrice;

    /**
     * La pièce du catalogue dont cette ligne est la copie (V46), quand elle vient de
     * Gestion PR. C'est une trace, pas un lien vivant : ni clé étrangère, ni recalcul.
     * La référence, le libellé et le prix restent ceux du papier — un catalogue qui
     * change de prix six mois plus tard ne réécrit pas un devis que le client a
     * accepté. Vide = ligne saisie sans catalogue, ce qui reste le cas normal d'une
     * main-d'œuvre, d'un déplacement ou d'une pièce apportée par le client.
     */
    @Column(name = "piece_id", length = 50)
    private String pieceId;

    @Column(name = "quantity_text", length = 60)
    private String quantityText;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LineNature nature;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplied_by", length = 20)
    private PartSupplier suppliedBy;

    /**
     * L'opération a-t-elle été faite. {@code null} et {@code false} veulent dire la
     * même chose pour l'atelier — « pas coché » — mais pas pour la base : une ligne
     * écrite avant ce champ ne doit pas devenir une affirmation fausse.
     */
    @Column(name = "done")
    private Boolean done;

    /** Les heures réellement passées sur cette opération, par l'homme qui l'a faite. */
    @Column(name = "real_hours")
    private Double realHours;
}
