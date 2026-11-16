package tn.higer.sav_backend.model;

import java.util.List;

/**
 * Les étapes d'une sortie de dépannage. Chaque valeur a son horodatage dans
 * {@link Intervention} : un statut ne peut être posé que si l'heure
 * correspondante vient d'être écrite par le serveur.
 *
 * REPAIRED et TOWED sont deux issues distinctes : « remorqué » n'est pas une
 * réparation, et le dire séparément évite de compter comme réussi un bus
 * simplement déplacé.
 */
public enum InterventionStatus {
    REPORTED,
    DISPATCHED,
    ON_SITE,
    IN_PROGRESS,
    REPAIRED,
    TOWED,
    RETURNED,
    CLOSED;

    /**
     * Une sortie est « ouverte » tant qu'elle n'est pas clôturée : le technicien
     * est dehors, la voiture de déplacement est engagée, et le bus concerné attend.
     * C'est cette liste qui sert aux refus (deux sorties à la fois pour un même
     * technicien, une voiture déjà affectée).
     */
    public static final List<InterventionStatus> OPEN =
            List.of(REPORTED, DISPATCHED, ON_SITE, IN_PROGRESS, REPAIRED, TOWED, RETURNED);
}
