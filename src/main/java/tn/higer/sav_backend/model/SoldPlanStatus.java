package tn.higer.sav_backend.model;

/**
 * État d'un forfait vendu.
 *
 * ACTIVE : l'engagement court, les paliers se suivent.
 * COMPLETED : tous les paliers faits, ou le client est sorti du forfait.
 * CANCELLED : l'atelier n'assurera plus rien — ce n'est pas un effacement, les
 * paliers déjà passés et les documents liés restent consultables.
 */
public enum SoldPlanStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
