package tn.higer.sav_backend.model;

/**
 * La nature de l'incident telle que le papier AG la demande : un défaut jamais
 * vu, ou le même qui revient.
 *
 * Ce n'est pas un détail de formulaire — « répétitif » se prouve par le numéro
 * du AG précédent, et c'est la case que le constructeur regarde pour décider d'une
 * reprise ou d'une concession.
 */
public enum IncidentNature {
    NOUVEAU("Nouveau"),
    REPETITIF("Répétitif");

    private final String label;

    IncidentNature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
