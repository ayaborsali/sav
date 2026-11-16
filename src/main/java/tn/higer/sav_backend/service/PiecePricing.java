package tn.higer.sav_backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * La seule façon dont un prix de pièce se calcule dans ce portail.
 *
 * Une multiplication de prix a trois façons de tourner mal, et les trois se payent
 * sur un devis déjà accepté par le client : le calcul fait à l'écran puis
 * recopié (l'atelier arrondit à sa manière), le calcul refait deux fois avec deux
 * arrondis différents (deux écrans du même portail n'affichent pas le même prix
 * de revient), et le {@code double} qui perd un millime en route.
 *
 * Ici : {@code BigDecimal}, un seul arrondi — trois décimales, {@code HALF_UP},
 * parce que le papier Higer et la facture d'import se lisent au millime.
 *
 * Les deux prix sortent du même calcul, et d'aucune saisie : le revient depuis le
 * prix Ex Works et son coefficient, la vente depuis le revient et le sien. Le
 * second se monte sur le premier — il ne repart pas du prix Ex Works — pour que le
 * prix affiché soit exactement celui que l'atelier lit sur la ligne au-dessus.
 */
public final class PiecePricing {

    /** Le millime, pas le centime : un prix de pièce se facture au 1/1000 de dinar. */
    public static final int DECIMALES = 3;

    private PiecePricing() {}

    /**
     * Prix de revient : le prix annoncé par le fournisseur, augmenté du
     * coefficient choisi pièce par pièce (transport, dédouanement, conversion de
     * devise). Le portail ne connaît aucun de ces trois frais séparément et
     * n'invente donc aucun taux : c'est le coefficient qui les porte.
     */
    public static BigDecimal revient(BigDecimal prixExWorks, BigDecimal coefficientRevient) {
        if (prixExWorks == null || coefficientRevient == null) {
            return null;
        }
        return prixExWorks.multiply(coefficientRevient).setScale(DECIMALES, RoundingMode.HALF_UP);
    }

    /**
     * Prix de vente : le prix de revient augmenté de son coefficient de vente. Il
     * part du revient et non du prix Ex Works — deux multiplications successives,
     * chacune arrondie, disent le même chiffre que l'atelier lit sur la ligne.
     *
     * Un coefficient absent ne donne pas un prix : sans taux, la pièce n'a pas de
     * prix de vente, elle n'en a pas un à zéro.
     */
    public static BigDecimal vente(BigDecimal prixRevient, BigDecimal coefficientVente) {
        if (prixRevient == null || coefficientVente == null) {
            return null;
        }
        return prixRevient.multiply(coefficientVente).setScale(DECIMALES, RoundingMode.HALF_UP);
    }
}
