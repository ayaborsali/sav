package tn.higer.sav_backend.service;

import java.util.List;
import java.util.function.Predicate;

/**
 * Le prochain numéro d'une famille (dossiers SAV, sorties, contrats, ordres de
 * réparation) : le compteur reprend le plus HAUT numéro déjà posé, jamais le plus
 * long.
 *
 * La distinction n'est pas théorique. Une famille qui a porté un compteur à trois
 * chiffres puis à quatre — « -029 » et « -0028 » — range « -0028 » avant « -029 »
 * si on la trie par longueur : le candidat qui en sort est « -029 », déjà en base,
 * et l'écriture du dossier échoue en 500 sur la contrainte d'unicité.
 *
 * Un suffixe qui n'est pas un nombre (« ssa », tapé à la main avant que la saisie
 * libre soit fermée) ne se plaint pas non plus : il ne peut pas entrer en ligne de
 * compte, il ne peut pas empêcher toute une famille de créer des dossiers.
 */
public final class NumberSequence {

    /** Un numéro déjà pris n'en est jamais 500 d'affilée : au-delà, la suite est cassée. */
    private static final int TENTATIVES_MAX = 500;

    private NumberSequence() {
    }

    /**
     * @param prefix    le tronçon fixe, tiret final compris (« SAV-REC-2026- »)
     * @param existants tous les numéros de la famille pour ce préfixe
     * @param largeur   la largeur normale du compteur, jamais réduite
     */
    public static String suivant(String prefix, List<String> existants, int largeur) {
        return suivant(prefix, existants, largeur, n -> false);
    }

    /**
     * La même suite, avec une porte pour sauter un candidat que la base refuse déjà :
     * un numéro tapé à la main sur l'écran Maintenance peut porter le chiffre que le
     * compteur vient de désigner.
     */
    public static String suivant(String prefix, List<String> existants, int largeur,
                                 Predicate<String> dejaPris) {
        long plusHaut = 0;
        int compteur = largeur;
        for (String numero : existants) {
            if (numero == null) {
                continue;
            }
            String suffixe = numero.substring(numero.lastIndexOf('-') + 1);
            if (suffixe.isEmpty() || !chiffres(suffixe)) {
                continue;
            }
            long valeur;
            try {
                valeur = Long.parseLong(suffixe);
            } catch (NumberFormatException tropLong) {
                continue;
            }
            plusHaut = Math.max(plusHaut, valeur);
            compteur = Math.max(compteur, suffixe.length());
        }
        long prochain = plusHaut + 1;
        String candidat = prefix + String.format("%0" + compteur + "d", prochain);
        for (int n = 0; dejaPris.test(candidat); n++) {
            if (n >= TENTATIVES_MAX) {
                throw new IllegalStateException("Plus de numéro libre pour " + prefix + " à partir de "
                        + candidat + ".");
            }
            prochain++;
            candidat = prefix + String.format("%0" + compteur + "d", prochain);
        }
        return candidat;
    }

    private static boolean chiffres(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
