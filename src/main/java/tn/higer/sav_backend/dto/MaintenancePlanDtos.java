package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanOperation;
import tn.higer.sav_backend.model.MaintenancePlanStep;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Le catalogue des plans de maintenance, dans les deux sens.
 *
 * Les montants ne quittent jamais ce serveur sans contrôle : {@code from(plan,
 * pricesVisible)} met les prix à null quand le compte n'a pas droit aux chiffres
 * commerciaux, pour que la réponse JSON ne contienne pas la valeur. Un « masqué »
 * affiché à l'écran ne protège rien dès que la requête est lue dans l'onglet réseau.
 *
 * Chaque palier et chaque opération portent un {@code ref} : dans la réponse c'est
 * l'identifiant réel, dans la demande c'est la clé que l'écran (ou l'import Excel)
 * utilise pour dire « cette croix relie ce palier à cette opération ». Le serveur
 * garde la main sur les identifiants, l'import n'a jamais à en inventer.
 */
public final class MaintenancePlanDtos {

    private MaintenancePlanDtos() {}

    /**
     * Les prix se stockent en {@code numeric(10,2)} — donc en {@code BigDecimal},
     * comme le prix du bus dans {@code Vehicle} — mais ils circulent en nombre
     * simple vers l'écran : c'est ce que le module maintenance connaît déjà, et un
     * devis ne se saisit pas au centime près.
     */
    static Double toDecimal(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** Un palier, avec les opérations que la grille y attache (par {@code ref}). */
    public record StepDto(String ref, String label, Long km, Integer days, Double priceHt,
                          List<String> operationRefs) {

        static StepDto from(MaintenancePlanStep step, boolean pricesVisible) {
            return new StepDto(step.getId(), step.getLabel(), step.getKm(), step.getDays(),
                    pricesVisible ? toDecimal(step.getPriceHt()) : null,
                    step.getOperations().stream().map(MaintenancePlanOperation::getId).toList());
        }
    }

    /** Une ligne du plan. {@code operationType} est le nom en minuscules, {@code operationTypeLabel} le mot de l'atelier. */
    public record OperationDto(String ref, String operationType, String operationTypeLabel,
                               String organ, String article, String reference, String quantityText,
                               Integer frequencyKm, Double unitPriceHt) {

        static OperationDto from(MaintenancePlanOperation operation, boolean pricesVisible) {
            return new OperationDto(
                    operation.getId(),
                    operation.getOperationType() != null ? operation.getOperationType().name().toLowerCase() : null,
                    operation.getOperationType() != null ? operation.getOperationType().label() : null,
                    operation.getOrgan(), operation.getArticle(), operation.getReference(),
                    operation.getQuantityText(), operation.getFrequencyKm(),
                    pricesVisible ? toDecimal(operation.getUnitPriceHt()) : null);
        }
    }

    /** Création ou modification complète d'un plan : l'en-tête, les paliers, les opérations, la grille. */
    public record PlanRequest(@NotBlank String name, String busModel, Long annualKm,
                              Integer oilChangeIntervalKm, Integer oilChangeIntervalDays,
                              Integer durationYears, Boolean active,
                              List<StepDto> steps, List<OperationDto> operations) {}

    public record PlanResponse(String id, String name, String busModel, Long annualKm,
                               Integer oilChangeIntervalKm, Integer oilChangeIntervalDays,
                               Integer durationYears, boolean active, LocalDateTime createdAt,
                               List<StepDto> steps, List<OperationDto> operations) {

        public static PlanResponse from(MaintenancePlan plan, boolean pricesVisible) {
            List<OperationDto> operations = plan.getOperations().stream()
                    .map(o -> OperationDto.from(o, pricesVisible))
                    .toList();
            List<StepDto> steps = plan.getSteps().stream()
                    .map(s -> StepDto.from(s, pricesVisible))
                    .toList();
            return new PlanResponse(
                    plan.getId(), plan.getName(), plan.getBusModel(), plan.getAnnualKm(),
                    plan.getOilChangeIntervalKm(), plan.getOilChangeIntervalDays(),
                    plan.getDurationYears(), plan.isActive(), plan.getCreatedAt(), steps, operations);
        }
    }

    /** Une ligne de la liste du catalogue : assez pour choisir, sans charger la grille. */
    public record PlanSummary(String id, String name, String busModel, boolean active,
                              int stepCount, int operationCount, int missingPriceCount) {}

    /** Où le problème a été vu : la feuille, la ligne telle qu'Excel la numérote, la colonne. */
    public record ImportIssue(String sheet, Integer row, String column, String message) {}

    /**
     * L'aperçu de l'import : ce qui serait enregistré, et ce qui l'empêcherait.
     * Rien n'est écrit en base à ce stade — {@code canImport()} dit seulement si
     * la confirmation passera.
     */
    public record ImportPreview(PlanRequest plan, List<ImportIssue> errors, List<ImportIssue> warnings) {

        public boolean canImport() {
            return errors.isEmpty() && plan != null;
        }
    }

    /** Ce que la confirmation envoie au serveur, après un aperçu accepté. */
    public record ImportCommit(PlanRequest plan) {}
}
