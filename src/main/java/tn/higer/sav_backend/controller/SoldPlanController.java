package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
import tn.higer.sav_backend.dto.SoldPlanDtos.ChargedLineDto;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanRequest;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanResponse;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanSummary;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.SoldPlanService;

import java.util.List;

/**
 * Les forfaits vendus : ce que l'atelier a vendu comme entretien, à quel client,
 * sur quel bus, et où en est le suivi des paliers.
 *
 * Lecture : tout compte connecté (la règle générique {@code GET /api/**}), prix
 * masqués pour le rôle consultation. Écriture : admin et gestionnaire SAV, par la
 * règle déjà en place — aucune règle nouvelle ici, et le catalogue les a déjà
 * fait passer pour la même raison.
 *
 * Le suivi des paliers ne se décrète pas à la main depuis cet écran : il avance
 * tout seul quand le devis ou le BL rattaché au forfait est livré (phase C).
 * L'endpoint {@code .../lines} n'écrit rien — il montre ce que le palier demande,
 * et l'atelier pose ces lignes dans le document.
 */
@RestController
@RequestMapping("/api/sold-plans")
public class SoldPlanController {

    private final SoldPlanService soldPlans;

    public SoldPlanController(SoldPlanService soldPlans) {
        this.soldPlans = soldPlans;
    }

    /** Les forfaits, avec trois filtres facultatifs : un bus, un client, un statut. */
    @GetMapping
    public List<SoldPlanSummary> list(@RequestParam(required = false) String vehicleId,
                                      @RequestParam(required = false) String clientId,
                                      @RequestParam(required = false) String status,
                                      Authentication auth) {
        return soldPlans.list(vehicleId, clientId, status, Access.seesCommercialFigures(auth));
    }

    @GetMapping("/{id}")
    public SoldPlanResponse get(@PathVariable String id, Authentication auth) {
        return soldPlans.get(id, Access.seesCommercialFigures(auth));
    }

    /**
     * Les lignes que ce palier demande de faire, prêtes à être chargées dans un
     * devis ou un BL. {@code stepId} est l'identifiant du palier dans le catalogue.
     */
    @GetMapping("/{id}/steps/{stepId}/lines")
    public List<ChargedLineDto> lines(@PathVariable String id, @PathVariable String stepId,
                                      Authentication auth) {
        return soldPlans.lignesACharger(id, stepId, Access.seesCommercialFigures(auth));
    }

    /** Vente d'un forfait : le serveur génère le suivi, un palier à faire par palier du plan. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SoldPlanResponse create(@Valid @RequestBody SoldPlanRequest request,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return soldPlans.create(request, jwt, Access.seesCommercialFigures(auth));
    }

    /** Dates, prix, clôture. Le plan, le client et le bus sont figés côté service. */
    @PutMapping("/{id}")
    public SoldPlanResponse update(@PathVariable String id, @Valid @RequestBody SoldPlanRequest request,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return soldPlans.update(id, request, jwt, Access.seesCommercialFigures(auth));
    }

    /** Annulation, pas effacement : le forfait reste lisible avec ses paliers déjà faits. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        soldPlans.cancel(id, jwt);
    }
}
