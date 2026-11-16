package tn.higer.sav_backend.model;

/**
 * Par quel bout le client a joint l'atelier.
 *
 * Les cinq valeurs reprennent la case « Canal » du document de référence
 * (Téléphone | E-mail | Courrier | Visite | Autre) : rien n'est ajouté, parce
 * qu'un canal inventé par le portail est un canal que personne ne relit.
 *
 * Ce n'est pas un détail de saisie : le contrat engage une réponse sous 48 h, et
 * une demande venue par courrier recommandé ne se prouve pas comme un coup de
 * téléphone.
 */
public enum CallerChannel {
    TELEPHONE("Téléphone"),
    EMAIL("E-mail"),
    COURRIER("Courrier"),
    VISITE("Venu sur place"),
    AUTRE("Autre");

    private final String label;

    CallerChannel(String label) {
        this.label = label;
    }

    /** Le mot que l'atelier lit à l'écran. */
    public String label() {
        return label;
    }
}
