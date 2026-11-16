package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tn.higer.sav_backend.dto.FactureDtos.AFacturerDto;
import tn.higer.sav_backend.dto.FactureDtos.EncaissementRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureEmissionRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureResponse;
import tn.higer.sav_backend.dto.FactureDtos.FactureSummary;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.FactureService;

import java.util.List;

/**
 * L'argent qui entre : les factures nées des bons de livraison signés, et la relance
 * de ceux qui attendent encore leur facture (I01, I02).
 *
 * Lecture : tout compte connecté (la règle générique {@code GET /api/**}), montants
 * masqués serveur pour qui n'a pas droit aux chiffres commerciaux. Écriture : admin
 * et gestionnaire SAV — le guichet — par la règle déjà en place sur POST /api/** ;
 * aucune règle nouvelle ici, et c'est déjà lui qui voit l'argent. L'atelier ne
 * s'écrit pas un encaissement.
 *
 * L'état d'une facture (émise / partielle / payée / impayée) ne se poste pas : il se
 * calcule à la lecture depuis les encaissements et l'échéance. Le seul geste qui
 * l'avance est l'encaissement d'un versement.
 */
@RestController
@RequestMapping("/api/factures")
public class FactureController {

    private final FactureService factures;

    public FactureController(FactureService factures) {
        this.factures = factures;
    }

    /** Les factures, avec l'état du jour et ce qui reste dû. */
    @GetMapping
    public List<FactureSummary> list(Authentication auth) {
        return factures.list(Access.seesCommercialFigures(auth));
    }

    /**
     * La relance (I02) : les bons de livraison signés jamais facturés. Déclaré avant
     * {@code /{id}} pour que « a-facturer » ne se lise pas comme un identifiant.
     */
    @GetMapping("/a-facturer")
    public List<AFacturerDto> aFacturer(Authentication auth) {
        return factures.aFacturer(Access.seesCommercialFigures(auth));
    }

    @GetMapping("/{id}")
    public FactureResponse get(@PathVariable String id, Authentication auth) {
        return factures.get(id, Access.seesCommercialFigures(auth));
    }

    /** Émettre la facture d'un BL signé : le montant est recopié du devis accepté, jamais tapé. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FactureResponse emettre(@Valid @RequestBody FactureEmissionRequest request,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return factures.emettre(request, jwt, Access.seesCommercialFigures(auth));
    }

    /** Encaisser un versement : borné au restant dû, daté du jour, écrit par le guichet. */
    @PostMapping("/{id}/encaissements")
    @ResponseStatus(HttpStatus.CREATED)
    public FactureResponse encaisser(@PathVariable String id, @Valid @RequestBody EncaissementRequest request,
                                     @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return factures.encaisser(id, request, jwt, Access.seesCommercialFigures(auth));
    }
}