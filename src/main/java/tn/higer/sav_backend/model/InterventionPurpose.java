package tn.higer.sav_backend.model;

/**
 * Ce que la sortie est allée faire.
 *
 * Le tronc est le même — un homme, une voiture de déplacement, des heures posées par
 * le serveur et une prime à la clé — mais la raison de sortir ne se note pas de la
 * même façon :
 *  - {@code PANNE} : le bus est tombé en panne, quelqu'un a appelé l'atelier. La
 *    sortie naît d'un dossier SAV ou d'un appel déclaré ;
 *  - {@code ENTRETIEN} : le bus doit une visite de son forfait, et c'est le rappel
 *    du palier atteint qui l'a envoyée. Elle porte le forfait et le palier qu'elle
 *    exécute, et sa clôture coche ce palier.
 *
 * La colonne est un varchar : ajouter un motif ne demandera pas de migration. Le
 * défaut en base est {@code PANNE}, parce que toutes les sorties écrites avant cette
 * distinction étaient des pannes — les réécrire en entretien inventerait un travail
 * que personne n'a déclaré.
 */
public enum InterventionPurpose {

    /** Le bus est en panne : c'est un appel qui a décidé la sortie. */
    PANNE("panne"),

    /** Le bus doit une visite de son forfait : c'est le rappel d'un palier qui a décidé la sortie. */
    ENTRETIEN("entretien");

    private final String label;

    InterventionPurpose(String label) {
        this.label = label;
    }

    /** Ce que l'écran de l'atelier lit, en français. */
    public String label() {
        return label;
    }
}
