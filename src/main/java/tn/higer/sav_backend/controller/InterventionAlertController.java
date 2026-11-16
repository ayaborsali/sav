package tn.higer.sav_backend.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.AlertDtos.AlertDto;
import tn.higer.sav_backend.dto.AlertDtos.AlertListResponse;
import tn.higer.sav_backend.dto.AlertDtos.ScanResponse;
import tn.higer.sav_backend.dto.AlertDtos.WaiveRequest;
import tn.higer.sav_backend.service.DepannageAlertService;

/**
 * Alertes de dépannage — le suivi dans le temps (lot D4).
 *
 * Trois routes, aucune de saisie :
 *  - GET  /api/interventions/alerts        les signaux encore ouverts + les seuils appliqués ;
 *  - POST /api/interventions/alerts/scan   la même vérification que le scheduler, sans l'attendre ;
 *  - POST /api/interventions/alerts/{id}/waive  la décision écrite d'un humain.
 *
 * Les routes sont littérales et passent devant {@code GET /api/interventions/{id}} :
 * « alerts » n'est pas un numéro de sortie. Le détail des droits (qui lève, qui ne
 * lit que ses sorties) est dans {@link DepannageAlertService}, parce qu'une règle
 * HTTP seule se contourne par une requête manuelle.
 */
@RestController
@RequestMapping("/api/interventions/alerts")
public class InterventionAlertController {

    private final DepannageAlertService service;

    public InterventionAlertController(DepannageAlertService service) {
        this.service = service;
    }

    @GetMapping
    public AlertListResponse list(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.open(auth, jwt);
    }

    /** Vérification immédiate : l'atelier n'a pas à attendre le tour du scheduler. */
    @PostMapping("/scan")
    public ScanResponse scan(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
        int opened = service.scan();
        return new ScanResponse(opened, service.open(auth, jwt));
    }

    @PostMapping("/{alertId}/waive")
    public AlertDto waive(@PathVariable String alertId,
                          @RequestBody(required = false) WaiveRequest request,
                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.waive(alertId, request, jwt, auth);
    }
}
