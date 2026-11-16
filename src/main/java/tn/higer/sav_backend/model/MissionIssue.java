package tn.higer.sav_backend.model;

/**
 * La réponse qu'une mission apporte à la demande : un homme dehors, le bus qui
 * vient à l'atelier, ou un devis quand le travail n'est pas couvert et doit être
 * chiffré avant d'être fait.
 *
 * Les trois mots sont ceux de l'atelier, pas ceux d'un workflow. {@code null} sur
 * une mission enregistrée et pas encore décidée : noter l'appel et trancher sont
 * deux gestes distincts, faits par deux personnes différentes.
 */
public enum MissionIssue {
    HOMME("Envoyer un homme"),
    ATELIER("Traiter à l'atelier"),
    DEVIS("Établir un devis");

    private final String label;

    MissionIssue(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
