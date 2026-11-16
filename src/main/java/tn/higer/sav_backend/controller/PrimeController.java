package tn.higer.sav_backend.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeDto;
import tn.higer.sav_backend.dto.PrimeDtos.PrimePeriodResponse;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeValidationRequest;
import tn.higer.sav_backend.service.PrimeService;

/**
 * Primes d'intervention — la partie payante du contrôle.
 *
 * Deux endpoints suffisent, et aucun n'accepte un montant :
 *  - la lecture calcule (ou relit la ligne validée) à partir des horodatages ;
 *  - la validation enregistre la décision du chef d'atelier, avec sa
 *    justification quand le calcul a signalé quelque chose.
 *
 * Le service ferme la porte à tout compte qui n'a pas à voir la paie ; les mots
 * de passe et les rôles sont vérifiés deux fois (règles HTTP ici, contrôle métier
 * dans {@link PrimeService}) parce qu'une seule des deux finit toujours par être
 * modifiée sans l'autre.
 */
@RestController
@RequestMapping("/api")
public class PrimeController {

    private final PrimeService service;

    public PrimeController(PrimeService service) {
        this.service = service;
    }

    /** Prime d'une sortie : brouillon calculé, ou ligne validée telle qu'elle a été payée. */
    @GetMapping("/interventions/{interventionId}/prime")
    public PrimeDto forIntervention(@PathVariable String interventionId,
                                    @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.forIntervention(interventionId, auth, jwt);
    }

    @PostMapping("/interventions/{interventionId}/prime/validate")
    public PrimeDto validate(@PathVariable String interventionId,
                             @RequestBody(required = false) PrimeValidationRequest request,
                             @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.validate(interventionId, request, jwt, auth);
    }

    /** Tableau du mois : lignes validées, totaux par technicien, total général. */
    @GetMapping("/primes")
    public PrimePeriodResponse period(@RequestParam(value = "month", required = false) String month,
                                      Authentication auth) {
        return service.period(month, auth);
    }
}
