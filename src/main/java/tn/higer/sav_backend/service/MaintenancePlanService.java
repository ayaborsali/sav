package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.OperationDto;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanRequest;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanResponse;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanSummary;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.StepDto;
import tn.higer.sav_backend.model.BusType;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanOperation;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.PlanOperationType;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Le catalogue des plans de maintenance.
 *
 * Ce service est le seul endroit où un plan se construit, parce que c'est là que
 * les refus se disent. Un plan mal formé ne se voit pas quand on l'écrit, il se
 * voit six mois plus tard sur un bon de travail : un palier sans kilométrage
 * jamais réclamé, une croix qui pointe une opération effacée, deux paliers qui se
 * ressemblent et l'atelier qui ne sait plus lequel a été fait. Chacun de ces cas
 * est refusé ici, en français, avec la ligne en cause.
 *
 * Les prix sont absents par défaut : le gérant les prépare. Un plan sans prix est
 * donc un plan normal, pas un plan inachevé — la case vide se signale à l'écran,
 * elle ne bloque pas l'enregistrement.
 *
 * Les identifiants viennent du serveur. La {@code ref} que l'écran ou l'import
 * envoie ne sert qu'à relier une croix à sa ligne le temps de la demande ; elle
 * est remplacée par l'identifiant réel à l'enregistrement.
 *
 * Un palier, une fois créé, GARDE son identifiant quand le plan est modifié : un
 * forfait vendu suit le palier par cet identifiant. Le retirer ou le renommer
 * alors qu'un client l'a acheté laisserait son suivi pointer dans le vide — c'est
 * refusé, alors que corriger son kilométrage, ses jours ou ses opérations reste
 * possible, puisque c'est le même palier.
 */
@Service
public class MaintenancePlanService {

    private final MaintenancePlanRepository plans;
    private final SoldPlanStepRepository soldPlanSteps;
    private final AuditService audit;

    public MaintenancePlanService(MaintenancePlanRepository plans, SoldPlanStepRepository soldPlanSteps,
                                  AuditService audit) {
        this.plans = plans;
        this.soldPlanSteps = soldPlanSteps;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PlanSummary> list() {
        return plans.findAllByOrderByCreatedAtDesc().stream().map(PlanSummaryOf::of).toList();
    }

    /** Les plans qu'une vente peut viser : actifs seulement. */
    @Transactional(readOnly = true)
    public List<PlanSummary> listSellable() {
        return plans.findByActiveTrueOrderByBusModelAscNameAsc().stream().map(PlanSummaryOf::of).toList();
    }

    @Transactional(readOnly = true)
    public PlanResponse get(String id, boolean pricesVisible) {
        return PlanResponse.from(require(id), pricesVisible);
    }

    @Transactional
    public PlanResponse create(PlanRequest request, Jwt jwt, boolean pricesVisible) {
        String name = requireName(request.name());
        if (plans.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un plan porte déjà ce nom : « " + name + " ». Change le nom ou modifie le plan existant.");
        }
        MaintenancePlan plan = new MaintenancePlan();
        plan.setId("mpl-" + UUID.randomUUID());
        plan.setCreatedAt(LocalDateTime.now());
        apply(plan, request, name);
        plans.save(plan);
        audit.record("CREATE", "MAINTENANCE_PLAN", plan.getId(),
                "Création du plan « " + plan.getName() + " » (" + plan.getSteps().size()
                        + " paliers, " + plan.getOperations().size() + " opérations)", jwt);
        return PlanResponse.from(plan, pricesVisible);
    }

    /**
     * Modification complète : l'en-tête, les paliers, les opérations et la grille
     * sont réécrits tels que reçus. C'est le geste de l'écran (on ferme la fiche
     * après avoir corrigé), et c'est ce qui garantit qu'aucune croix orpheline ne
     * survit à une ligne supprimée.
     */
    @Transactional
    public PlanResponse update(String id, PlanRequest request, Jwt jwt, boolean pricesVisible) {
        MaintenancePlan plan = require(id);
        String name = requireName(request.name());
        if (plans.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un autre plan porte déjà ce nom : « " + name + " ».");
        }
        apply(plan, request, name);
        plans.save(plan);
        audit.record("UPDATE", "MAINTENANCE_PLAN", id,
                "Modification du plan « " + plan.getName() + " » (" + plan.getSteps().size()
                        + " paliers, " + plan.getOperations().size() + " opérations)", jwt);
        return PlanResponse.from(plan, pricesVisible);
    }

    /**
     * Retrait du catalogue, sans effacement. Les forfaits vendus d'avant pointent
     * encore vers ce plan, et les documents déjà imprimés gardent leurs lignes :
     * supprimer les rangées casserait l'historique pour un gain de place nul.
     */
    @Transactional
    public void deactivate(String id, Jwt jwt) {
        MaintenancePlan plan = require(id);
        plan.setActive(false);
        plans.save(plan);
        audit.record("DEACTIVATE", "MAINTENANCE_PLAN", id,
                "Plan « " + plan.getName() + " » retiré du catalogue", jwt);
    }

    private MaintenancePlan require(String id) {
        return plans.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan de maintenance introuvable."));
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le plan doit porter un nom : c'est ce nom que l'atelier cite quand il propose un forfait.");
        }
        return raw.trim();
    }

    /**
     * La gamme d'autobus que ce plan entretient : Confort ou Standard.
     *
     * Elle est obligatoire ici, sinon la vente ne peut plus être contrôlée : un plan
     * sans gamme se vendrait sur un bus de l'autre gamme, et le client serait suivi
     * avec la grille d'entretien d'un autre bus. La fiche d'import porte cette case
     * (« Modèle ») ; l'atelier l'écrit donc une fois, dans le fichier.
     */
    private static String typeDePlan(String raw, String planName) {
        String label = BusType.labelOf(raw);
        if (label != null) {
            return label;
        }
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le plan « " + planName + " » ne dit pas quelle gamme d'autobus il entretient. "
                            + "Renseignez la case Modèle de la fiche : Confort ou Standard.");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "« " + raw.trim() + " » n'est pas une gamme d'autobus pour le plan « " + planName
                        + " » : la case Modèle vaut Confort ou Standard.");
    }

    /** Construit le plan à partir de la demande, en refusant tout ce qui ne se lira pas six mois plus tard. */
    private void apply(MaintenancePlan plan, PlanRequest request, String name) {
        List<OperationDto> operationDtos = request.operations() != null ? request.operations() : List.of();
        List<StepDto> stepDtos = request.steps() != null ? request.steps() : List.of();

        check("km annuel", request.annualKm());
        check("fréquence de vidange en km", request.oilChangeIntervalKm());
        check("fréquence de vidange en jours", request.oilChangeIntervalDays());
        check("durée du plan en années", request.durationYears());

        plan.setName(name);
        plan.setBusModel(typeDePlan(request.busModel(), name));
        plan.setAnnualKm(request.annualKm());
        plan.setOilChangeIntervalKm(request.oilChangeIntervalKm());
        plan.setOilChangeIntervalDays(request.oilChangeIntervalDays());
        plan.setDurationYears(request.durationYears());
        plan.setActive(request.active() == null || request.active());

        Map<String, MaintenancePlanOperation> byRef = new HashMap<>();
        List<MaintenancePlanOperation> operations = new ArrayList<>();
        for (int i = 0; i < operationDtos.size(); i++) {
            OperationDto dto = operationDtos.get(i);
            MaintenancePlanOperation operation = toOperation(dto, i + 1);
            operation.setPlan(plan);
            // L'identifiant est toujours celui du serveur : une demande ne peut pas
            // faire porter à une ligne l'identifiant d'une ligne d'un autre plan.
            operation.setId("mpo-" + UUID.randomUUID());
            String ref = refOf(dto.ref(), operation.getId());
            if (byRef.putIfAbsent(ref, operation) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Deux opérations de la ligne " + (i + 1) + " portent la même référence interne (« "
                                + ref + " ») : la grille ne saurait plus laquelle cocher.");
            }
            operations.add(operation);
        }

        Set<String> labels = new HashSet<>();
        // Les paliers qui existent déjà, indexés par leur libellé : un forfait vendu
        // référence un palier PAR SON IDENTIFIANT, donc modifier un plan ne doit pas
        // redonner un identifiant à un palier qui en a déjà un.
        Map<String, MaintenancePlanStep> existants = indexParLibelle(plan);
        List<MaintenancePlanStep> cibles = new ArrayList<>();
        int order = 1;
        for (StepDto dto : stepDtos) {
            String label = requireLabel(dto.label(), order);
            if (!labels.add(PlanOperationType.fold(label))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Deux paliers portent le même libellé « " + label + " ». Un palier se reconnaît à ce qu'on lit dessus.");
            }
            if (dto.km() == null && dto.days() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le palier « " + label + " » n'a ni kilométrage ni durée : aucun bus ne peut l'atteindre, il ne serait jamais réclamé.");
            }
            check("kilométrage du palier « " + label + " »", dto.km());
            check("durée en jours du palier « " + label + " »", dto.days());

            MaintenancePlanStep step = existants.get(PlanOperationType.fold(label));
            if (step == null) {
                step = new MaintenancePlanStep();
                // L'identifiant est toujours celui du serveur : une demande ne peut pas
                // faire porter à un palier l'identifiant d'un palier d'un autre plan.
                step.setId("mps-" + UUID.randomUUID());
            }
            step.setPlan(plan);
            step.setStepOrder(order++);
            step.setLabel(label);
            step.setKm(dto.km());
            step.setDays(dto.days());
            step.setPriceHt(money(dto.priceHt(), "palier « " + label + " »"));

            List<MaintenancePlanOperation> crossed = new ArrayList<>();
            if (dto.operationRefs() != null) {
                for (String ref : dto.operationRefs()) {
                    MaintenancePlanOperation operation = byRef.get(ref == null ? "" : ref.trim());
                    if (operation == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Le palier « " + label + " » renvoie à une opération inconnue (« " + ref
                                        + " »). Coche la grille dans le même enregistrement que les lignes.");
                    }
                    crossed.add(operation);
                }
            }
            step.getOperations().clear();
            step.getOperations().addAll(crossed);
            cibles.add(step);
        }

        // Un palier retiré ou renommé alors qu'un client l'a déjà acheté ferait pointer
        // le suivi du bus sur rien : la grille se corrige, l'engagement d'un client non.
        retireDesPaliersEngages(plan, cibles);

        plan.getOperations().clear();
        plan.getOperations().addAll(operations);
        for (MaintenancePlanStep ancien : List.copyOf(plan.getSteps())) {
            if (!cibles.contains(ancien)) {
                plan.getSteps().remove(ancien);
            }
        }
        for (MaintenancePlanStep cible : cibles) {
            if (!plan.getSteps().contains(cible)) {
                plan.getSteps().add(cible);
            }
        }
    }

    /** Les paliers connus du plan, par libellé plié : ce qui permet de leur garder leur identifiant. */
    private static Map<String, MaintenancePlanStep> indexParLibelle(MaintenancePlan plan) {
        Map<String, MaintenancePlanStep> index = new HashMap<>();
        for (MaintenancePlanStep step : plan.getSteps()) {
            index.put(PlanOperationType.fold(step.getLabel()), step);
        }
        return index;
    }

    /**
     * Refuse de faire disparaître un palier engagé sur un forfait vendu — retire
     * comme renommé, puisqu'un renommage crée une ligne neuve et orpheline.
     * Un plan sans steps (création) ne consulte personne : pas de requête inutile.
     */
    private void retireDesPaliersEngages(MaintenancePlan plan, List<MaintenancePlanStep> cibles) {
        if (plan.getSteps().isEmpty()) {
            return;
        }
        Set<String> engages = new HashSet<>(soldPlanSteps.findEngagedStepIds(SoldPlanStatus.CANCELLED));
        if (engages.isEmpty()) {
            return;
        }
        for (MaintenancePlanStep ancien : plan.getSteps()) {
            boolean survit = cibles.contains(ancien);
            if (!survit && engages.contains(ancien.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le palier « " + ancien.getLabel() + " » est engagé chez un client : on ne le retire ni ne le "
                                + "renomme. Modifie son kilométrage, ses jours ou ses opérations, ça reste le même palier.");
            }
        }
    }

    private static MaintenancePlanOperation toOperation(OperationDto dto, int line) {
        PlanOperationType type = dto.operationType() == null
                ? null : PlanOperationType.fromLabel(dto.operationType());
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'action de la ligne " + line + " n'est pas reconnue (« " + dto.operationType()
                            + " »). Écris l'une de ces cinq : remplacer, contrôler, graisser, nettoyer, régler.");
        }
        if (isBlank(dto.organ()) && isBlank(dto.article())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La ligne " + line + " ne dit rien de précis : donne-lui au moins un organe ou une désignation.");
        }
        check("fréquence en km de la ligne " + line, dto.frequencyKm());

        MaintenancePlanOperation operation = new MaintenancePlanOperation();
        operation.setOperationType(type);
        operation.setOrgan(trimToNull(dto.organ()));
        operation.setArticle(trimToNull(dto.article()));
        operation.setReference(trimToNull(dto.reference()));
        operation.setQuantityText(trimToNull(dto.quantityText()));
        operation.setFrequencyKm(dto.frequencyKm());
        operation.setUnitPriceHt(money(dto.unitPriceHt(), "ligne " + line));
        return operation;
    }

    private static String requireLabel(String raw, int order) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le palier " + order + " n'a pas de libellé. Un palier se nomme (« visite des 45 000 km »), sinon la grille ne veut plus rien dire.");
        }
        return raw.trim();
    }

    /** Une {@code ref} de demande : la clé choisie par l'écran, ou l'identifiant du serveur. */
    private static String refOf(String ref, String fallback) {
        return ref == null || ref.isBlank() ? fallback : ref.trim();
    }

    /**
     * Un prix de catalogue s'arrête au dinar et au millime : la colonne est en
     * {@code numeric(10,2)}. Plutôt que de laisser la base arrondir 85,567 en
     * 85,57 sans rien dire, la ligne est refusée et l'atelier corrige son chiffre.
     * La même règle sert au prix d'un forfait vendu (V14, même colonne).
     */
    static java.math.BigDecimal money(Double value, String what) {
        check(what, value);
        if (value == null) {
            return null;
        }
        java.math.BigDecimal exact = java.math.BigDecimal.valueOf(value);
        if (exact.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le prix de « " + what + " » (" + value + ") a plus de deux décimales. "
                            + "Un montant de catalogue s'arrête au millime.");
        }
        return exact;
    }

    private static void check(String what, Number value) {
        if (value != null && value.doubleValue() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le champ « " + what + " » ne peut pas être négatif.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** Une ligne de la liste du catalogue, avec ce qu'il faut pour choisir sans ouvrir le plan. */
    private static final class PlanSummaryOf {
        private PlanSummaryOf() {}

        static PlanSummary of(MaintenancePlan plan) {
            int missingPrices = (int) plan.getSteps().stream().filter(s -> s.getPriceHt() == null).count()
                    + (int) plan.getOperations().stream().filter(o -> o.getUnitPriceHt() == null).count();
            return new PlanSummary(plan.getId(), plan.getName(), plan.getBusModel(), plan.isActive(),
                    plan.getSteps().size(), plan.getOperations().size(), missingPrices);
        }
    }
}
