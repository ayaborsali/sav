package tn.higer.sav_backend.model;

/**
 * Les règles que le temps fait déclencher (D4). Une règle par valeur, annoncée à
 * l'avance dans {@code application.properties} sous forme de seuil : le serveur
 * ne change pas d'avis entre deux scans.
 *
 * La colonne reste un varchar en base : ajouter une règle ne demandera pas de
 * migration.
 */
public enum InterventionAlertKind {

    /** Le départ est horodaté et le cliché « avant » n'est jamais arrivé. */
    MISSING_BEFORE_PHOTO("cliché « avant » manquant"),

    /** Le travail est fini (ou le bus rentré) sans cliché « après ». */
    MISSING_AFTER_PHOTO("cliché « après » manquant"),

    /** Sortie toujours dehors après le plafond de durée. */
    STILL_OUT("sortie dehors depuis trop longtemps"),

    /** Réparé ou remorqué, la voiture n'est pas revenue à l'atelier. */
    NOT_RETURNED("bus non rentré à l'atelier"),

    /** Appel reçu, aucune voiture de déplacement affectée depuis trop longtemps. */
    NOT_DISPATCHED("aucune voiture de déplacement affectée");

    private final String label;

    InterventionAlertKind(String label) {
        this.label = label;
    }

    /** Ce que l'écran de l'atelier lit, en français. */
    public String label() {
        return label;
    }
}
