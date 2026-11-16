package tn.higer.sav_backend.model;

/**
 * Disponibilité d'une voiture de déplacement. Sert à ne pas affecter deux sorties
 * à la même : DISPATCHED est posé par le serveur à l'affectation et rendu
 * à la clôture ou au retour.
 */
public enum ServiceVehicleStatus {
    AVAILABLE,
    DISPATCHED,

    /** au garage atelier, donc non mobilisable */
    AT_WORKSHOP,

    /** en panne : à distinguer d'AT_WORKSHOP, un véhicule indisponible n'est pas entretenu */
    OUT_OF_ORDER
}
