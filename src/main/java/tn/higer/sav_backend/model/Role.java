package tn.higer.sav_backend.model;

/**
 * Les comptes du portail. Un compte ne porte qu'un seul rôle.
 *
 * CHEF_ATELIER : seul à décider qui sort, avec quel véhicule, et seul (avec ADMIN) à
 * voir les primes. TECHNICIEN : accès minimal, ses propres interventions, aucune heure
 * saisie au clavier, aucun montant.
 */
public enum Role {
    ADMIN,
    GESTIONNAIRE_SAV,
    CONSULTATION,
    CHEF_ATELIER,
    TECHNICIEN
}
