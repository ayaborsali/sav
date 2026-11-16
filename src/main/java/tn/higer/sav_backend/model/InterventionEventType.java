package tn.higer.sav_backend.model;

/**
 * Une ligne du journal d'une sortie. La plupart des types reprennent l'étape
 * vécue (cf. {@link InterventionStatus}) ; les autres sont des actes de
 * gestion : CORRECTED (une heure a été rectifiée, l'ancienne valeur reste
 * lisible), NOTE (commentaire du chef), CASE (lien avec un dossier SAV),
 * PHOTO (un cliché versé) et PRIME (la prime validée).
 *
 * La colonne reste un varchar en base : ajouter un type ne demandera pas de
 * migration.
 */
public enum InterventionEventType {
    REPORTED,
    DISPATCHED,
    DEPARTED,
    ARRIVED,
    DIAGNOSED,
    REPAIRED,
    TOWED,
    LEFT_SITE,
    RETURNED,
    CLOSED,
    CORRECTED,
    /** Rattachement à un dossier SAV, ou dossier créé depuis la sortie. */
    CASE,
    /** Une photo versée : l'empreinte et l'heure de versement vivent dans la table des photos. */
    PHOTO,
    /** Prime validée pour la sortie : le montant, lui, vit dans intervention_primes. */
    PRIME,
    /** Alerte levée par écrit : la raison reste lisible au dossier, l'alerte elle-même vit dans intervention_alerts. */
    ALERT,
    /** Palier d'un forfait que cette sortie est allée faire : coché, ou refusé avec la raison écrite. */
    PALIER,
    /** Une pièce demandée, commandée, reçue ou posée pendant la sortie : la ligne vit dans intervention_part_requests. */
    PART,
    NOTE
}
