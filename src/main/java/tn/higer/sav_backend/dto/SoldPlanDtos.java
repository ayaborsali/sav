package tn.higer.sav_backend.dto;

import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Les forfaits vendus, dans les deux sens.
 *
 * Le nom du client et du bus est porté dans la réponse, même si la base ne stocke
 * que des identifiants : l'écran qui liste les forfaits doit pouvoir dire « forfait
 * 3 ans — Sfax Transport — CS 1234 » sans relancer trois appels. Une annulation
 * reste lisible, donc les noms sont recherchés même pour un forfait fermé.
 *
 * Les montants (prix du forfait, prix de palier) sont mis à {@code null} à la
 * sortie quand le compte n'a pas droit aux chiffres commerciaux — même règle que
 * le catalogue, et pour la même raison : l'onglet réseau du navigateur se lit.
 *
 * Les paliers sont renvoyés dans l'ordre du plan ({@code stepOrder}), pas dans
 * l'ordre des lignes en base : c'est l'ordre de la grille Higer que l'atelier suit.
 */
public final class SoldPlanDtos {

    private SoldPlanDtos() {}

    /**
     * Un palier du forfait vendu : son suivi (status, doneAt, maintenanceItemId,
     * mileageAtService) et ce que le palier du catalogue décrit (label, km, days,
     * priceHt). {@code ref} est la ligne de suivi, {@code planStepRef} le palier —
     * phase C a besoin des deux pour lier un document à un palier.
     */
    public record SoldStepDto(String ref, String planStepRef, int stepOrder, String label,
                              Long km, Integer days, Double priceHt, String status,
                              LocalDateTime doneAt, String maintenanceItemId, Long mileageAtService) {

        static SoldStepDto from(SoldPlanStep line, boolean pricesVisible) {
            return new SoldStepDto(line.getId(), line.getStep().getId(), line.getStep().getStepOrder(),
                    line.getStep().getLabel(), line.getStep().getKm(), line.getStep().getDays(),
                    pricesVisible ? MaintenancePlanDtos.toDecimal(line.getStep().getPriceHt()) : null,
                    line.getStatus() != null ? line.getStatus().name().toLowerCase() : null,
                    line.getDoneAt(), line.getMaintenanceItemId(), line.getMileageAtService());
        }
    }

    /**
     * Vente ou correction d'un forfait. Le statut n'est pas proposé à la création :
     * un forfait nait actif. À la modification, il ne peut que sortir d'ACTIF —
     * le service refuse de rouvrir un forfait fermé.
     */
    public record SoldPlanRequest(String planId, String clientId, String vehicleId,
                                  LocalDate startDate, LocalDate endDate, Double priceHt,
                                  String status) {}

    /**
     * La fiche complète d'un forfait vendu.
     *
     * Elle porte les trois champs du prochain palier comme la ligne de liste :
     * l'écran Angular les lit dans un seul type, et une fiche qui ne répondrait
     * pas ces champs enverrait `undefined` là où la liste met une valeur.
     */
    public record SoldPlanResponse(String id, String planId, String planName, String busModel,
                                   String clientId, String clientName, String vehicleId,
                                   String vehicleVin, String vehicleRegistration,
                                   LocalDate startDate, LocalDate endDate, Double priceHt,
                                   String status, LocalDateTime createdAt,
                                   int stepCount, int doneStepCount,
                                   String nextStepLabel, Long nextStepKm, Integer nextStepDays,
                                   List<SoldStepDto> steps) {

        public static SoldPlanResponse of(SoldPlan plan, String clientName, String vehicleVin,
                                          String vehicleRegistration, boolean pricesVisible) {
            List<SoldStepDto> lines = lignes(plan, pricesVisible);
            int done = (int) plan.getSteps().stream()
                    .filter(s -> s.getStatus() == SoldPlanStepStatus.DONE).count();
            MaintenancePlanStep prochain = prochainPalierADire(plan);
            return new SoldPlanResponse(plan.getId(), plan.getPlan().getId(), plan.getPlan().getName(),
                    plan.getPlan().getBusModel(), plan.getClientId(), clientName, plan.getVehicleId(),
                    vehicleVin, vehicleRegistration, plan.getStartDate(), plan.getEndDate(),
                    pricesVisible ? MaintenancePlanDtos.toDecimal(plan.getPriceHt()) : null,
                    plan.getStatus() != null ? plan.getStatus().name().toLowerCase() : null,
                    plan.getCreatedAt(), lines.size(), done,
                    prochain != null ? prochain.getLabel() : null,
                    prochain != null ? prochain.getKm() : null,
                    prochain != null ? prochain.getDays() : null,
                    lines);
        }
    }

    /**
     * Une ligne de la liste des forfaits : assez pour choisir, sans charger le suivi
     * palier par palier.
     *
     * Le prochain palier se calcule ici et non à l'écran, pour une raison simple :
     * « prochain » veut dire « le premier encore à faire dans l'ordre de la grille »,
     * et cet ordre n'existe que dans le plan. Un écran qui le refait à sa manière
     * peut annoncer une visite que l'atelier ne doit pas encore.
     *
     * Les trois champs sont {@code null} quand plus aucun palier n'est à faire :
     * l'écran doit alors le dire, pas laisser une ligne en blanc. Aucun montant n'y
     * figure, donc le masque du compte de consultation ne les concerne pas.
     */
    public record SoldPlanSummary(String id, String planId, String planName, String clientId, String clientName,
                                  String vehicleId, String vehicleVin, String vehicleRegistration,
                                  LocalDate startDate, LocalDate endDate, Double priceHt, String status,
                                  int stepCount, int doneStepCount,
                                  String nextStepLabel, Long nextStepKm, Integer nextStepDays) {

        public static SoldPlanSummary of(SoldPlan plan, String clientName, String vehicleVin,
                                         String vehicleRegistration, boolean pricesVisible) {
            int done = (int) plan.getSteps().stream()
                    .filter(s -> s.getStatus() == SoldPlanStepStatus.DONE).count();
            MaintenancePlanStep prochain = prochainPalierADire(plan);
            return new SoldPlanSummary(plan.getId(), plan.getPlan().getId(), plan.getPlan().getName(),
                    plan.getClientId(), clientName, plan.getVehicleId(), vehicleVin, vehicleRegistration,
                    plan.getStartDate(), plan.getEndDate(),
                    pricesVisible ? MaintenancePlanDtos.toDecimal(plan.getPriceHt()) : null,
                    plan.getStatus() != null ? plan.getStatus().name().toLowerCase() : null,
                    plan.getSteps().size(), done,
                    prochain != null ? prochain.getLabel() : null,
                    prochain != null ? prochain.getKm() : null,
                    prochain != null ? prochain.getDays() : null);
        }
    }

    /** Le premier palier encore à faire, dans l'ordre de la grille ; {@code null} si tout est fait. */
    private static MaintenancePlanStep prochainPalierADire(SoldPlan plan) {
        return plan.getSteps().stream()
                .filter(line -> line.getStatus() == SoldPlanStepStatus.TODO)
                .min(Comparator.comparingInt(line -> line.getStep().getStepOrder()))
                .map(line -> line.getStep())
                .orElse(null);
    }

    /** Les paliers du forfait, dans l'ordre de la grille du plan. */
    private static List<SoldStepDto> lignes(SoldPlan plan, boolean pricesVisible) {
        return plan.getSteps().stream()
                .sorted(Comparator.comparingInt(line -> line.getStep().getStepOrder()))
                .map(line -> SoldStepDto.from(line, pricesVisible))
                .toList();
    }

    /**
     * Une ligne à charger dans un document de maintenance : ce que la grille du plan
     * demande à ce palier, déjà présentée comme une ligne de devis ou de BL
     * (référence, désignation, quantité, prix).
     *
     * Aucun identifiant du catalogue n'y figure, volontairement : les lignes
     * chargées sont RECOPIÉES dans le document, elles ne rappellent jamais le plan.
     * Changer un prix au catalogue ne réécrirait pas un BL déjà livré au client.
     *
     * {@code quantity} est le nombre qui entre dans le total (« 23 L » → 23) ;
     * {@code quantityText} garde la quantité telle que le document Higer l'écrit,
     * unité comprise. Sans ce texte, l'unité du plan disparaît du bon de travail et
     * l'atelier doit rouvrir le classeur pour la retrouver.
     *
     * {@code priceMissing} signale « prix à définir » sans rien bloquer : les prix
     * arrivent après le plan, et un palier sans prix se charge et se fait quand même.
     */
    public record ChargedLineDto(String reference, String label, Double quantity, Double unitPrice,
                                 String quantityText, boolean priceMissing) {

        public static ChargedLineDto of(String reference, String label, Double quantity,
                                        java.math.BigDecimal unitPrice, String quantityText,
                                        boolean pricesVisible) {
            Double prix = pricesVisible ? MaintenancePlanDtos.toDecimal(unitPrice) : null;
            // Un compte qui n'a pas droit aux chiffres lit « prix à définir » sur toutes
            // les lignes : moins informatif, mais surtout pas une fuite sur ce que le
            // catalogue sait déjà.
            return new ChargedLineDto(reference, label, quantity, prix, quantityText, prix == null);
        }
    }
}
