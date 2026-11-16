package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La numérotation d'une famille se reprend sur le plus HAUT numéro posé. Le cas qui
 * a cassé l'écran Dossiers SAV est réel : une famille qui a porté « -029 » et un
 * compteur à quatre chiffres « -0028 » rendait « -029 » deux fois, et la base
 * refusait le dossier en 500.
 */
class NumberSequenceTest {

    private static final String P = "SAV-REC-2026-";

    @Test
    void uneFamilleVideCommenceAUn() {
        assertEquals(P + "001", NumberSequence.suivant(P, List.of(), 3));
    }

    @Test
    void lePremierNumeroDeLaBasePorteLeCompteur() {
        assertEquals(P + "002", NumberSequence.suivant(P, List.of(P + "001"), 3));
    }

    @Test
    void lePlusHautGagneSurLePlusLong() {
        List<String> poses = List.of(P + "029", P + "0028", P + "003");
        assertEquals(P + "0030", NumberSequence.suivant(P, poses, 3));
    }

    @Test
    void auDelaDeNeufCentQuatreVingtDixNeufLeCompteurGranditToutSeul() {
        assertEquals(P + "1000", NumberSequence.suivant(P, List.of(P + "0999"), 3));
    }

    @Test
    void unNumeroTapeALaMainNeBloquePasLaFamille() {
        assertEquals(P + "030", NumberSequence.suivant(P, List.of("ssa", P + "029"), 3));
    }

    @Test
    void unNumeroSansTiretNEstPasUnCompteur() {
        assertEquals(P + "001", NumberSequence.suivant(P, List.of("ssa"), 3));
    }
}
