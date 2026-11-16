package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandRequest;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandResponse;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartReceptionRequest;
import tn.higer.sav_backend.service.InterventionPartService;

import java.util.List;

/**
 * Les pièces demandées sur une sortie.
 *
 * Deux entrées de lecture, une seule écriture par fait :
 *  - la sortie : l'écran Dépannage, où l'homme déclare ce qu'il voit devant le bus ;
 *  - le dossier : l'écran des dossiers SAV relit ce qui attend son bus, en lecture
 *    seule — la pièce ne se saisit pas au guichet, elle se reçoit du terrain.
 *
 * Pas de PUT du dossier entier ici : une demande de pièce n'est pas une case du
 * formulaire SAV, c'est un fait arrivé dehors, avec son auteur et son heure.
 */
@RestController
@RequestMapping("/api")
public class InterventionPartController {

    private final InterventionPartService pieces;

    public InterventionPartController(InterventionPartService pieces) {
        this.pieces = pieces;
    }

    /** Les pièces que cette sortie a demandées, dans l'ordre de la saisie. */
    @GetMapping("/interventions/{interventionId}/pieces")
    public List<PartDemandResponse> pourSortie(@PathVariable String interventionId,
                                               @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return pieces.pourSortie(interventionId, auth, jwt);
    }

    /** Les pièces d'une réclamation, relues par l'écran du dossier. */
    @GetMapping("/sav-cases/{savCaseId}/pieces")
    public List<PartDemandResponse> pourDossier(@PathVariable String savCaseId,
                                                @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return pieces.pourDossier(savCaseId, auth, jwt);
    }

    /**
     * L'homme dehors demande la pièce, au moment du diagnostic. Le bus, le dossier et
     * la mission ne sont pas dans le corps de la requête : le serveur les reprend de la
     * sortie, et un écran qui les redemande pourrait les faire mentir.
     */
    @PostMapping("/interventions/{interventionId}/pieces")
    @ResponseStatus(HttpStatus.CREATED)
    public PartDemandResponse demander(@PathVariable String interventionId,
                                       @Valid @RequestBody PartDemandRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return pieces.demander(interventionId, request, jwt, auth);
    }

    /** Rectifier sa demande — tant qu'elle n'est pas commandée, et alors seulement. */
    @PutMapping("/intervention-pieces/{pieceId}")
    public PartDemandResponse corriger(@PathVariable String pieceId,
                                       @Valid @RequestBody PartDemandRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return pieces.corriger(pieceId, request, jwt, auth);
    }

    /**
     * Le guichet passe la commande. Aucun corps : la date vient de l'horloge du
     * serveur et le nom du jeton, parce qu'une date de commande tapée à la main est
     * une date qu'on peut arranger.
     */
    @PostMapping("/intervention-pieces/{pieceId}/commander")
    public PartDemandResponse commander(@PathVariable String pieceId,
                                        @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return pieces.commander(pieceId, jwt, auth);
    }

    /**
     * Le magasin réceptionne la quantité arrivée à ce passage. L'état qui en sort —
     * « Partielle » ou « Livrée » — est calculé par le serveur sur le cumul, et
     * l'écran ne l'envoie pas.
     */
    @PostMapping("/intervention-pieces/{pieceId}/receptionner")
    public PartDemandResponse receptionner(@PathVariable String pieceId,
                                           @Valid @RequestBody PartReceptionRequest request,
                                           @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return pieces.receptionner(pieceId, request, jwt, auth);
    }
}
