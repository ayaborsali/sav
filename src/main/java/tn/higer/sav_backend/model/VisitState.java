package tn.higer.sav_backend.model;

/**
 * Où en est une visite client, tenue À PART du statut du dossier.
 *
 * Le statut ({@link SavCaseStatus}) suit la chaîne atelier : une sortie de
 * dépannage, puis les décisions humaines. Une visite se planifie avant tout
 * cela, et « Reportée » ou « Annulée » n'ont rien à faire dans cette chaîne —
 * personne n'a travaillé, il n'y a rien à clôturer.
 *
 * Les quatre mots viennent tels quels de l'onglet « Visites clients » de
 * l'application de référence (Planifiée | Réalisée | Reportée | Annulée). Rien
 * n'est ajouté : un état que l'atelier n'écrit pas sur son papier ne se relit
 * nulle part.
 */
public enum VisitState {
    PLANIFIEE("Planifiée"),
    REALISEE("Réalisée"),
    REPORTEE("Reportée"),
    ANNULEE("Annulée");

    private final String label;

    VisitState(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }
}
