package tn.higer.sav_backend.service;

import tn.higer.sav_backend.model.Encaissement;
import tn.higer.sav_backend.model.FactureStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * L'état d'une facture, calculé une seule fois — jamais stocké, jamais tapé.
 *
 * Comme {@link MaintenanceTotals} pour les montants, cette porte unique évite que
 * l'écran et le serveur ne rendent deux états différents pour la même créance. L'état
 * se déduit de trois faits : la somme des encaissements, le total TTC et l'échéance.
 * Une facture qui vieillit passe « impayée » toute seule à la lecture, sans que
 * personne n'ait à écrire quoi que ce soit.
 *
 * Le partage est sans chevauchement : payée si tout est rentré, partielle si une
 * partie est rentrée (même échue — le versement est un fait, le retard se lit à
 * côté), impayée si l'échéance est dépassée sans rien de rentré, émise sinon.
 */
public final class FactureEtats {

    private FactureEtats() {}

    /** La somme rentrée, arrondie au centime comme le sont les montants du portail. */
    public static double sommeEncaissee(List<Encaissement> encaissements) {
        double somme = encaissements.stream()
                .mapToDouble(e -> e.getAmount() == null ? 0.0 : e.getAmount())
                .sum();
        return MaintenanceTotals.round2(somme);
    }

    /** Ce qui reste à encaisser, jamais négatif : une facture soldée reste à 0. */
    public static double restantDu(double encaisse, Double totalTTC) {
        double ttc = totalTTC == null ? 0.0 : totalTTC;
        return MaintenanceTotals.round2(Math.max(0.0, ttc - encaisse));
    }

    /**
     * L'état à la date du jour. La comparaison « tout est rentré » garde une tolérance
     * d'un demi-centime : les sommes de doubles arrondis ne tombent pas toujours pile.
     */
    public static FactureStatus etat(double encaisse, Double totalTTC, LocalDate dueOn, LocalDate today) {
        double ttc = totalTTC == null ? 0.0 : totalTTC;
        if (encaisse >= ttc - 0.005) {
            return FactureStatus.PAYEE;
        }
        if (encaisse > 0) {
            return FactureStatus.PARTIELLE;
        }
        if (dueOn != null && today != null && today.isAfter(dueOn)) {
            return FactureStatus.IMPAYEE;
        }
        return FactureStatus.EMISE;
    }

    /** L'échéance est dépassée et la créance n'est pas soldée : le retard se colore. */
    public static boolean enRetard(FactureStatus etat, LocalDate dueOn, LocalDate today) {
        return etat != FactureStatus.PAYEE && dueOn != null && today != null && today.isAfter(dueOn);
    }
}