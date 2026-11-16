package tn.higer.sav_backend.model;

/**
 * Les deux premiers après {@code OPEN} sont le déroulé d'une sortie de dépannage :
 * l'homme est sur place, puis il est revenu à l'atelier. Les trois du milieu sont
 * des décisions humaines (pièces, accord, refus) : une décision ne s'écrase pas
 * parce qu'une étape de sortie vient de passer.
 *
 * {@code EnumType.STRING} dans la base : ajouter un état ne demande pas de migration.
 */
public enum SavCaseStatus {
    OPEN,
    IN_PROGRESS,
    ON_SITE,
    PENDING_PARTS,
    APPROVED,
    REJECTED,
    RETURNED,
    CLOSED
}
