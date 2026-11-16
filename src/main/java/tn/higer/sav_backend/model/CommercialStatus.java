package tn.higer.sav_backend.model;

/**
 * Relation commerciale avec l'exploitant, colonne « Statut » du fichier client.
 * Distinct du champ « active » des comptes utilisateurs : ici il s'agit du client,
 * pas de la personne qui se connecte.
 */
public enum CommercialStatus {
    PROSPECT,
    ACTIVE,
    LOST
}
