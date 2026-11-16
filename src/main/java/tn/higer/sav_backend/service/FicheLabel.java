package tn.higer.sav_backend.service;

import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Vehicle;

/**
 * Le mot que le portail met sur un bus et sur un client, en un seul endroit.
 *
 * Quatre écrans nomment le bus (Dossiers, Sorties, Missions, Primes) et la fiche du
 * kilométrage écrit son refus lui aussi. Si chacun choisit son champ — l'un le VIN,
 * l'autre la plaque — le même bus change de nom d'un module à l'autre, et l'atelier
 * croit voir deux bus. La formule est donc ici, et nulle part ailleurs.
 *
 * Le bus se reconnaît à son VIN : l'immatriculation n'est pas une identité fiable
 * (une plaque change, un bus reste). Elle ne sert que de repli quand la fiche n'a
 * pas de VIN — un bus entré à la main avant que la détection existe.
 */
public final class FicheLabel {

    private FicheLabel() {
    }

    /** Le bus, nommé : son VIN, sa plaque seulement si le VIN manque. */
    public static String bus(Vehicle v) {
        if (v == null) {
            return null;
        }
        return nonBlanc(v.getVin()) ? v.getVin().trim() : immatriculation(v);
    }

    /** Le VIN du bus. Ce que la fiche ne porte pas rend vide, jamais une chaîne blanche. */
    public static String vin(Vehicle v) {
        return v == null ? null : mot(v.getVin());
    }

    /** L'immatriculation, telle qu'elle est peinte sur le bus : celle que l'homme lit au portail. */
    public static String immatriculation(Vehicle v) {
        return v == null ? null : mot(v.getRegistration());
    }

    /** Le modèle du bus, pour la fiche détaillée. */
    public static String modele(Vehicle v) {
        return v == null ? null : mot(v.getModel());
    }

    /** Le client, nommé : son nom, jamais sa clé. */
    public static String client(Client c) {
        return c == null ? null : mot(c.getName());
    }

    /** Le blanc n'est pas une valeur : une case que la fiche ne connaît pas se rend vide. */
    public static String mot(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean nonBlanc(String value) {
        return value != null && !value.isBlank();
    }
}
