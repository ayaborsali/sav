package tn.higer.sav_backend.model;

/**
 * Ce qui a écrit la valeur du compteur sur la fiche du bus.
 *
 * Le chiffre seul ne veut rien dire à l'atelier : « 10 120 km » saisi de mémoire
 * par le gestionnaire et « 10 120 km » relevés sur le tableau de bord par le
 * technicien en train de dormir ne se discutent pas de la même façon. Quand un
 * palier est contesté, c'est la provenance du kilométrage qui se juge, pas le
 * nombre.
 *
 * Une seule valeur est ajoutée à la fois, par {@code VehicleMileageService} : le
 * jour où le kilométrage viendra d'un capteur embarqué, le capteur passera par la
 * même porte avec sa propre origine, au lieu d'écrire en cachette dans un coin.
 */
public enum MileageOrigin {

    /** Un humain de l'atelier a tapé la valeur sur la fiche du bus. */
    ATELIER("saisi à l'atelier"),

    /** La valeur vient du compteur relu par le technicien pendant une sortie. */
    RELEVE_TECHNICIEN("relevé par le technicien sur une sortie");

    private final String label;

    MileageOrigin(String label) {
        this.label = label;
    }

    /** Ce que l'écran de l'atelier lit, en français. */
    public String label() {
        return label;
    }
}
