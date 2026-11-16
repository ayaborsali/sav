package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Les deux calculs d'une pièce — le revient puis la vente — seuls endroits du
 * portail où ils se font.
 *
 * Ce qui se vérifie ici n'est pas la multiplication — elle est juste — mais les
 * trois façons dont elle tourne mal sur un devis déjà accepté par le client : un
 * arrondi au centime (le papier Higer se lit au millime, et l'écart s'accumule sur
 * une ligne de douze pièces), un arrondi fait deux fois, et un calcul revenu du
 * navigateur (l'écran arrondit à sa manière).
 *
 * La vente se monte sur le revient déjà arrondi, pas sur le prix Ex Works : c'est
 * le chiffre que l'atelier lit sur la ligne du dessus, et deux multiplications en
 * chaîne doivent redonner exactement ce qu'il voit.
 */
class PiecePricingTest {

    @Test
    void leRevientSeLitAuMillime() {
        // 86,500 € × 1,7200 : le transport, le dédouanement et la devise tiennent
        // tous dans ce seul coefficient choisi par l'atelier.
        assertEquals(new BigDecimal("148.780"),
                PiecePricing.revient(new BigDecimal("86.500"), new BigDecimal("1.7200")));
    }

    @Test
    void larrondiEstDemiSuperieurPasTroncature() {
        // 1,0005 : tronqué, il donnerait 1,000 et le client paierait un millime de
        // moins que ce que le coût réel porte.
        assertEquals(new BigDecimal("1.001"),
                PiecePricing.revient(new BigDecimal("1.000"), new BigDecimal("1.0005")));
        assertEquals(new BigDecimal("208.958"),
                PiecePricing.revient(new BigDecimal("124.380"), new BigDecimal("1.68")));
    }

    @Test
    void leRevientSeRepartDuChiffreStocke() {
        // Le même couple prix/coefficient relu deux fois rend exactement le même
        // nombre, échelle comprise : un devis qui additionnerait 0,333 et 0,3330
        // n'est pas le même total qu'un devis qui additionne deux fois la même case.
        BigDecimal revient = PiecePricing.revient(new BigDecimal("0.333"), new BigDecimal("1.0005"));
        assertEquals(new BigDecimal("0.333"), revient);
        assertEquals(revient, PiecePricing.revient(new BigDecimal("0.333"), new BigDecimal("1.0005")));
    }

    @Test
    void rienNeSeCalculeSansPrixOuSansCoefficient() {
        assertNull(PiecePricing.revient(null, new BigDecimal("1.7200")));
        assertNull(PiecePricing.revient(new BigDecimal("86.500"), null));
    }

    @Test
    void lePrixDeVenteSeMonteSurLeRevient() {
        // 148,780 × 1,5000 : la marge s'applique au revient, pas au prix du
        // fournisseur — c'est la ligne du dessus que l'atelier lit dans la liste.
        assertEquals(new BigDecimal("223.170"),
                PiecePricing.vente(new BigDecimal("148.780"), new BigDecimal("1.5000")));
    }

    @Test
    void lePrixDeVenteSuitLeMemeMillimeEtLeMemeArrondi() {
        BigDecimal revient = PiecePricing.revient(new BigDecimal("1.000"), new BigDecimal("1.0005"));
        assertEquals(new BigDecimal("1.001"), revient);
        // 1,001 × 1,0005 = 1,0015005 : la moitié supérieure donne 1,002, la
        // troncature donnerait 1,001 et le client paierait un millime de moins.
        assertEquals(new BigDecimal("1.002"),
                PiecePricing.vente(revient, new BigDecimal("1.0005")));
    }

    @Test
    void sansCoefficientDeVenteLaPieceNaPasDePrixDeVente() {
        // Une pièce entrée avant V47 n'a pas de coefficient : elle n'a pas de prix
        // de vente du tout, et surtout pas un prix à zéro qui se facturerait.
        assertNull(PiecePricing.vente(new BigDecimal("148.780"), null));
        assertNull(PiecePricing.vente(null, new BigDecimal("1.5000")));
    }

    @Test
    void leMillimeEstLaSeuleEchelleAdmise() {
        assertEquals(3, PiecePricing.DECIMALES);
        // Un prix saisi au centime rend quand même trois décimales : la colonne de
        // base est au millime, et un devis qui additionne des nombres d'échelles
        // différentes est le genre d'écart qu'on retrouve chez le client.
        assertEquals(new BigDecimal("148.780"),
                PiecePricing.revient(new BigDecimal("86.5"), new BigDecimal("1.72")));
    }
}
