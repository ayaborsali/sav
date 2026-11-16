package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import tn.higer.sav_backend.dto.MissionDtos.MissionEnvoiRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionCountersResponse;
import tn.higer.sav_backend.dto.MissionDtos.MissionReasonRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.service.MissionService;

/**
 * L'écran Missions.
 *
 * Les sept routes sont les sept gestes de l'atelier, et rien d'autre : noter une
 * demande, trancher (un homme dehors, le bus à l'atelier, ou un devis à établir),
 * refuser, reporter, reprendre. Les noms sont ceux des boutons, pour que « ce que
 * je clique » et « ce que le serveur écrit » portent le même mot.
 *
 * C'est par ici que le portail fait partir un homme, et par ici seulement : la case
 * du dossier et le bouton du rappel enregistrent une demande, ils ne créent plus de
 * sortie eux-mêmes.
 *
 * Le numéro de la mission, l'horodatage de la décision et l'état « Soldée » ne se
 * saisissent nulle part : le serveur les écrit, ou les lit derrière le papier.
 */
@RestController
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService missions;

    public MissionController(MissionService missions) {
        this.missions = missions;
    }

    @GetMapping
    public Page<MissionResponse> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return missions.list(pageable);
    }

    /**
     * Les quatre chiffres du bandeau, plus le délai qui les produit. Ils comptent sur
     * toutes les demandes qui courent, pas sur la seule page affichée : un chiffre qui
     * dépendrait de la pagination serait un faux témoin.
     */
    @GetMapping("/compteurs")
    public MissionCountersResponse compteurs() {
        return missions.compteurs();
    }

    @GetMapping("/{id}")
    public MissionResponse get(@PathVariable String id) {
        return missions.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MissionResponse creer(@Valid @RequestBody MissionRequest request,
                                 @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.creer(request, jwt, auth);
    }

    /**
     * « Envoyer un homme ». Le corps est facultatif : une demande venue d'un dossier
     * part sans homme désigné (le chef l'affecte sur le volet Sorties), une demande
     * venue d'un rappel d'entretien exige la voiture et l'homme sur-le-champ, et le
     * serveur le refuse à l'écrit.
     */
    @PostMapping("/{id}/envoyer-un-homme")
    public MissionResponse envoyerUnHomme(@PathVariable String id,
                                          @RequestBody(required = false) MissionEnvoiRequest request,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.envoyerUnHomme(id, request, jwt, auth);
    }

    @PostMapping("/{id}/traiter-atelier")
    public MissionResponse traiterAtelier(@PathVariable String id,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.traiterAtelier(id, jwt, auth);
    }

    /** Le travail n'est pas couvert : un devis brouillon s'ouvre, aucun prix n'est deviné. */
    @PostMapping("/{id}/etablir-devis")
    public MissionResponse etablirDevis(@PathVariable String id,
                                        @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.etablirDevis(id, jwt, auth);
    }

    @PostMapping("/{id}/refuser")
    public MissionResponse refuser(@PathVariable String id,
                                   @RequestBody MissionReasonRequest request,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.refuser(id, request, jwt, auth);
    }

    @PostMapping("/{id}/reporter")
    public MissionResponse reporter(@PathVariable String id,
                                    @RequestBody MissionReasonRequest request,
                                    @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.reporter(id, request, jwt, auth);
    }

    @PostMapping("/{id}/reprendre")
    public MissionResponse reprendre(@PathVariable String id,
                                     @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return missions.reprendre(id, jwt, auth);
    }
}
