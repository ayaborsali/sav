package tn.higer.sav_backend.model;

/**
 * La règle qui a rendu un palier dû.
 *
 * Une valeur par borne du catalogue, parce que les deux ne se discutent pas de la
 * même façon : « le bus a roulé » se vérifie à l'odomètre, « le temps a tourné » se
 * vérifie à la date du forfait. Un palier qui cumule les deux n'est signalé
 * qu'une fois — par la borne atteinte en premier — et son message dit l'autre.
 *
 * La colonne reste un varchar en base : ajouter une règle ne demandera pas de
 * migration.
 */
public enum SoldPlanAlertKind {

    /** Le compteur connu du bus a atteint le kilométrage du palier. */
    KM_ATTEINT("compteur du bus atteint"),

    /** Les jours du palier sont écoulés depuis le début du forfait. */
    DELAI_ECHU("délai du palier écoulé");

    private final String label;

    SoldPlanAlertKind(String label) {
        this.label = label;
    }

    /** Ce que l'écran de l'atelier lit, en français. */
    public String label() {
        return label;
    }
}
