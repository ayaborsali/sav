package tn.higer.sav_backend.model;

/** Ce que la photo doit montrer. Le texte libre ne remplace pas ces six preuves. */
public enum PhotoAngle {
    /** Le bus tel qu'il était à l'arrivée de l'équipe. */
    BEFORE,
    /** Le travail en cours sur place : ce que le chef d'atelier ne verra qu'après. */
    DURING,
    /** Ce qui a été fait sur place. */
    AFTER,
    /** La jauge de carburant de la voiture de déplacement, relevée par le technicien. */
    FUEL,
    /** Le compteur : seule garantie du kilométrage déclaré. */
    MILEAGE,
    /** Annexes (pièce commandée, bon de grue). */
    OTHER,
    /** La voiture de déplacement avant de quitter l'atelier. */
    DEPART,
    /** Le compteur de la voiture au départ : la photo remplace le chiffre tapé. */
    COMPTEUR_DEPART,
    /** La voiture de déplacement en rentrant à l'atelier. */
    RETOUR,
    /** Le compteur de la voiture au retour. */
    COMPTEUR_RETOUR;

    /** Le nom que l'atelier lit à l'écran : un refus doit dire QUELLE photo manque. */
    public String label() {
        return switch (this) {
            case BEFORE -> "avant intervention";
            case DURING -> "pendant l'intervention";
            case AFTER -> "après intervention";
            case FUEL -> "jauge de carburant";
            case MILEAGE -> "compteur";
            case OTHER -> "annexe";
            case DEPART -> "la voiture au départ";
            case COMPTEUR_DEPART -> "le compteur au départ";
            case RETOUR -> "la voiture au retour";
            case COMPTEUR_RETOUR -> "le compteur au retour";
        };
    }
}
