package tn.higer.sav_backend.model;

/**
 * Les deux gammes d'autobus Higer que le portail connaît : Confort ou Standard.
 * Chaque gamme a son propre ensemble de forfaits d'entretien.
 *
 * Ce n'est PAS le modèle commercial du bus (KLQ6129G, Azure, Touring…). Le châssis
 * et l'immatriculation disent déjà quel bus c'est ; ici on ne décide qu'une chose :
 * quelle grille d'entretien s'applique. Un texte libre ne pouvait pas porter cette
 * décision, parce que « Higer KLQ6129G (12m Urbain) » ne dit à personne si le bus
 * se suit en Confort ou en Standard.
 *
 * Le texte du fichier ou de l'écran passe par {@link #fromLabel} : accents, casse et
 * mentions inutiles (« BUS HIGER CONFORT ») se rejoignent sur la même valeur.
 * Une écriture qui ne désigne aucune des deux gammes renvoie null, et c'est le
 * service qui refuse avec un motif lisible — jamais une conversion silencieuse.
 */
public enum BusType {

    CONFORT("Confort"),
    STANDARD("Standard");

    private final String label;

    BusType(String label) {
        this.label = label;
    }

    /** Ce que l'écran et les papiers affichent. */
    public String label() {
        return label;
    }

    /**
     * La gamme telle qu'un humain l'a écrite, dans la fiche Excel du plan ou sur la
     * fiche du bus.
     *
     * @return null si le mot ne désigne aucune des deux gammes, y compris quand il
     *         les désigne toutes les deux (une écriture ambiguë ne se tranche pas ici)
     */
    public static BusType fromLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String needle = PlanOperationType.fold(raw);
        if (needle.isEmpty()) {
            return null;
        }
        boolean confort = needle.contains("confort");
        boolean standard = needle.contains("standard") || needle.startsWith("std");
        if (confort == standard) {
            return null;
        }
        return confort ? CONFORT : STANDARD;
    }

    /**
     * Le nom de la gamme, ou null si la valeur lue n'est pas une gamme connue.
     * Utilisé là où une colonne de texte continue de stocker la valeur
     * (« vehicles.model », « maintenance_plans.bus_model »).
     */
    public static String labelOf(String raw) {
        BusType type = fromLabel(raw);
        return type == null ? null : type.label();
    }
}
