package tn.higer.sav_backend.model;

/**
 * Les trois papiers de l'atelier, dans une seule table coupée par ce champ.
 *
 * Les mots viennent du document de référence, pas d'une habitude d'écran : le
 * {@link #DEVIS} chiffre un travail hors forfait chez le client, l'{@link #OPERATION}
 * est l'ordre de travail — le papier qui dit que l'atelier exécute et ne facture
 * personne —, le {@link #BL} rend le bus et les pièces.
 *
 * Une chaîne de trois maillons, donc : devis accepté → ordre → bon de livraison.
 * Les libellés sont ici une seule fois pour que la mission, l'onglet Maintenance et
 * les messages de refus disent tous le même nom du même papier.
 */
public enum MaintenanceType {
    DEVIS("Devis"),
    BL("Bon de livraison"),
    OPERATION("Ordre de travail");

    private final String label;

    MaintenanceType(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }
}
