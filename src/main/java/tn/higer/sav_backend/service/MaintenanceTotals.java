package tn.higer.sav_backend.service;

import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Les totaux d'un document de maintenance, calculés une seule fois.
 *
 * Le portail a deux portes d'écriture pour un même papier : le formulaire de
 * l'écran Maintenance, et la clôture d'une sortie d'entretien qui crée son ordre de
 * réparation toute seule. Si le calcul était copié dans les deux, un changement de
 * TVA ou d'arrondi donnerait deux montants pour la même visite — et c'est le montant
 * qui se discute chez le client.
 *
 * La règle reste celle du formulaire : totalHT = somme des lignes (quantité × prix
 * unitaire), total TTC = total HT × (1 + TVA). Les montants envoyés par un navigateur
 * sont ignorés.
 *
 * <b>Sauf pour l'ordre de travail</b> (V38) <b>et le bon de livraison</b> (V39) : ces
 * deux-là ne chiffrent pas. Le prix est sur le devis que le client a signé, et le
 * remettre sur l'ordre ou sur le rendu du bus donnerait trois montants à défendre pour
 * un même travail. Les papiers déjà enregistrés gardent les leurs — on cesse d'en
 * écrire, on n'efface pas ce qui est écrit.
 */
public final class MaintenanceTotals {

    private MaintenanceTotals() {}

    /** Total HT somme des lignes, total TTC arrondi au centime, avec la TVA configurée. */
    public static void appliquer(MaintenanceItem document, double tvaRateConfig) {
        // Seul le devis chiffre : l'ordre décrit le travail et le bon de livraison le
        // rend. Les montants déjà posés sur les papiers d'avant restent tels qu'ils ont
        // été écrits — ce calcul-ci ne les touchait pas hier, il ne les touche pas aujourd'hui.
        if (document.getType() != MaintenanceType.DEVIS) {
            return;
        }
        double totalHT = document.getDetails().stream()
                .mapToDouble(ligne -> nz(ligne.getQuantity()) * nz(ligne.getUnitPrice()))
                .sum();
        document.setTotalHT(round2(totalHT));
        document.setTotalTTC(round2(totalHT * (1 + tvaFraction(tvaRateConfig))));
    }

    /**
     * Taux TVA normalisé en fraction (0.19 pour 19 %), lu défensivement : une valeur
     * entre 0 et 1 est déjà une fraction, une valeur > 1 est un pourcentage, une
     * valeur négative retombe sur 19 % plutôt que de soustraire la taxe.
     */
    public static double tvaFraction(double configured) {
        double v = configured;
        if (v < 0) {
            v = 19;
        }
        if (v > 1) {
            v = v / 100.0; // ex. 19 → 0.19
        }
        return v;
    }

    public static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double nz(Double value) {
        return value != null ? value : 0.0;
    }
}
