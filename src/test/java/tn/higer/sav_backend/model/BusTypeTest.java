package tn.higer.sav_backend.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * La lecture de la gamme : ce que le document Higer écrit, tel qu'un humain l'a tapé.
 *
 * Le texte vient de trois endroits qui ne s'écrivent pas pareil — la liste de
 * l'écran Véhicules (« Confort »), la fiche Excel du plan (« BUS HIGER CONFORT »)
 * et la fiche du bus reprise de l'ancienne liste des modèles
 * (« Higer KLQ6129G (12m Urbain) »). Les deux premiers doivent se rejoindre, le
 * troisième doit être refusé : un modèle commercial ne dit aucune gamme.
 */
class BusTypeTest {

    @Test
    void leDocumentEcritEnToutesLettresEstLaMemeGamme() {
        assertEquals(BusType.CONFORT, BusType.fromLabel("BUS HIGER CONFORT"));
        assertEquals(BusType.CONFORT, BusType.fromLabel("confort"));
        assertEquals(BusType.CONFORT, BusType.fromLabel(" Confort "));
        assertEquals(BusType.STANDARD, BusType.fromLabel("Higer Standard"));
        assertEquals(BusType.STANDARD, BusType.fromLabel("STD"));
    }

    @Test
    void unModeleCommercialNeDitRien() {
        assertNull(BusType.fromLabel("Higer KLQ6129G (12m Urbain)"));
        assertNull(BusType.fromLabel("Higer Azure 12m Électrique"));
        assertNull(BusType.fromLabel("26 WBE"));
        assertNull(BusType.fromLabel("   "));
        assertNull(BusType.fromLabel(null));
    }

    /**
     * Une écriture qui nomme les deux gammes ne se tranche pas ici : le serveur doit
     * la refuser et montrer la case en cause, au lieu de choisir une grille
     * d'entretien à la place de l'atelier.
     */
    @Test
    void uneCaseQuiNommeLesDeuxGammesResteAmbigue() {
        assertNull(BusType.fromLabel("confort ou standard"));
    }

    @Test
    void laValeurRendueEstCelleQueLEcranMontre() {
        assertEquals("Confort", BusType.labelOf("bus higer confort"));
        assertEquals("Standard", BusType.labelOf("STANDARD"));
        assertNull(BusType.labelOf("KLQ6119"));
    }
}
