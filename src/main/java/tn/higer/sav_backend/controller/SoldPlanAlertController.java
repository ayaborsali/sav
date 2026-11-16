package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.PlanAlertDto;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.PlanAlertListResponse;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.ScanResponse;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.WaiveRequest;
import tn.higer.sav_backend.service.SoldPlanAlertService;

/**
 * Les rappels de paliers : ce que le compteur d'un bus réclame.
 *
 * Trois verbes, aucun de saisie. Un rappel naît d'un scan du serveur, se lit, et se
 * lève par une raison écrite — jamais en tapant sa date à la main, sinon l'atelier
 * déclarerait dû ce qu'il n'a pas vérifié.
 *
 * Chemin à part ({@code /api/sold-plan-alerts}) et non {@code /api/sold-plans/...} :
 * un segment littéral y voisinerait le {@code /{id}} d'un forfait, et une route qui
 * dépend de l'ordre de résolution des modèles n'est pas une route lisible.
 *
 * Sécurité : aucune règle nouvelle. La lecture tombe sous la règle générique
 * {@code GET /api/**} (tout compte connecté) ; le scan et la levée sous les verbes
 * d'écriture déjà réservés à l'admin et au gestionnaire SAV, et le service le
 * re-vérifie. Un rappel ne porte aucun montant : le rôle consultation lit la liste
 * sans y voir un prix, comme sur la fiche du forfait.
 */
@RestController
@RequestMapping("/api/sold-plan-alerts")
public class SoldPlanAlertController {

    private final SoldPlanAlertService alerts;

    public SoldPlanAlertController(SoldPlanAlertService alerts) {
        this.alerts = alerts;
    }

    /** Les rappels encore ouverts, avec la règle appliquée. */
    @GetMapping
    public PlanAlertListResponse open() {
        return alerts.ouverts();
    }

    /**
     * Vérifier maintenant : la même passe que le tour périodique, déclenchée de
     * l'écran. Personne n'a à attendre dix minutes pour savoir quels bus attendent,
     * et un compteur corrigé à la baisse ferme sa ligne dans la foulée.
     */
    @PostMapping("/scan")
    public ScanResponse scan() {
        int opened = alerts.scan();
        return new ScanResponse(opened, alerts.ouverts());
    }

    /** Lever un rappel : la raison est exigée, et elle reste sur la ligne. */
    @PostMapping("/{id}/waive")
    public PlanAlertDto waive(@PathVariable String id, @Valid @RequestBody WaiveRequest request,
                              @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return alerts.lever(id, request, jwt, auth);
    }
}
