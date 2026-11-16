package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.ImportCommit;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.ImportPreview;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanRequest;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanResponse;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanSummary;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.MaintenancePlanImportService;
import tn.higer.sav_backend.service.MaintenancePlanService;

import java.util.List;

/**
 * Le catalogue des forfaits de maintenance.
 *
 * Lecture : tout compte connecté (règle générique {@code GET /api/**}), mais les
 * prix ne sortent que pour un compte qui a droit aux chiffres commerciaux
 * ({@link Access#seesCommercialFigures}) — la valeur est absente de la réponse
 * JSON, pas seulement grisée à l'écran.
 *
 * Écriture : admin et gestionnaire SAV, par la règle générique déjà en place sur
 * les verbes POST/PUT/DELETE de {@code /api/**}. Aucune règle nouvelle n'est
 * ajoutée ici : un catalogue qui s'écrit depuis un compte de consultation serait
 * une faille, et le serveur la refuse déjà.
 *
 * L'import se fait en deux temps, et c'est voulu : {@code POST /import} ne écrit
 * rien, il montre ce qu'il a compris et ce qu'il n'a pas compris ;
 * {@code POST /import/commit} enregistre la demande telle qu'elle vient d'être
 * relue. Le fichier de l'atelier ne devient donc jamais un plan tout seul.
 */
@RestController
@RequestMapping("/api/maintenance-plans")
public class MaintenancePlanController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MaintenancePlanService plans;
    private final MaintenancePlanImportService importer;

    public MaintenancePlanController(MaintenancePlanService plans, MaintenancePlanImportService importer) {
        this.plans = plans;
        this.importer = importer;
    }

    /**
     * Le catalogue. {@code sellable=true} ne laisse que les plans actifs : c'est
     * la liste qu'on propose à une vente, le reste n'étant plus vendable mais
     * restant lisible pour retrouver un forfait d'avant.
     */
    @GetMapping
    public List<PlanSummary> list(@RequestParam(required = false, defaultValue = "false") boolean sellable) {
        return sellable ? plans.listSellable() : plans.list();
    }

    @GetMapping("/{id}")
    public PlanResponse get(@PathVariable String id, Authentication auth) {
        return plans.get(id, Access.seesCommercialFigures(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse create(@Valid @RequestBody PlanRequest request, @AuthenticationPrincipal Jwt jwt,
                               Authentication auth) {
        return plans.create(request, jwt, Access.seesCommercialFigures(auth));
    }

    @PutMapping("/{id}")
    public PlanResponse update(@PathVariable String id, @Valid @RequestBody PlanRequest request,
                               @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return plans.update(id, request, jwt, Access.seesCommercialFigures(auth));
    }

    /**
     * Retrait du catalogue, pas effacement : les forfaits vendus d'avant et les
     * documents déjà imprimés gardent leur référence. Une suppression franche
     * casserait l'historique pour gagner une ligne à l'écran.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        plans.deactivate(id, jwt);
    }

    /** Aperçu d'un classeur : rien n'est enregistré à cette étape. */
    @PostMapping("/import")
    public ImportPreview preview(@RequestParam("file") MultipartFile file) {
        return importer.preview(file);
    }

    /** Enregistrement de l'aperçu relu, avec les mêmes règles que la saisie à l'écran. */
    @PostMapping("/import/commit")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse commit(@Valid @RequestBody ImportCommit commit, @AuthenticationPrincipal Jwt jwt,
                               Authentication auth) {
        return plans.create(commit.plan(), jwt, Access.seesCommercialFigures(auth));
    }

    /** Le classeur vide à remplir : quatre feuilles, les entêtes, une page de notice. */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/import/template.xlsx")
    public ResponseEntity<byte[]> template() {
        byte[] bytes = importer.template();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele-plan-maintenance.xlsx\"")
                .body(bytes);
    }
}
