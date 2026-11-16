package tn.higer.sav_backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une ligne de facture, recopiée du devis accepté au moment d'émettre.
 *
 * C'est une trace, pas un lien vivant : ni clé étrangère, ni recalcul. La référence,
 * le libellé, la quantité et le prix restent ceux du papier que le client a accepté —
 * un devis ou un catalogue qui change de prix six mois plus tard ne réécrit pas la
 * facture reçue. La même leçon que {@code MaintenanceDetail.pieceId} (V46).
 *
 * {@code nature} et {@code suppliedBy} descendent avec la ligne pour que la facture
 * dise ce qu'elle chiffre : une pièce fournie par le client est déjà à 0 sur le devis,
 * et ce qui est couvert par le contrat n'a pas été devisé. Le total de la ligne
 * ({@code lineTotal}) est quantité × prix unitaire, calculé par le serveur.
 */
@Embeddable
@Getter @Setter @NoArgsConstructor
public class FactureLine {

    @Column(length = 120)
    private String reference;

    @Column(length = 500)
    private String label;

    private Double quantity;

    @Column(name = "unit_price")
    private Double unitPrice;

    @Column(name = "line_total")
    private Double lineTotal;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private LineNature nature;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplied_by", length = 20)
    private PartSupplier suppliedBy;
}