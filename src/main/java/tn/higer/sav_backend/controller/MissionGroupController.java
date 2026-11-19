package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.MissionGroupDtos.AnnulationRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.AvailabilityResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.CocherRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.ConstatRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DepartRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.HistoryRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.MyDossierRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.NextNumberResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.RetourRequest;
import tn.higer.sav_backend.service.MissionGroupService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * L'écran Mission groupe.
 *
 * Les routes suivent les deux gestes du dossier : l'atelier prépare (numéro,
 * client, bus, homme, voiture, départ prévu) ; le technicien affecté pointe le
 * départ réel et le retour. Aucune route ne reçoit les kilomètres parcourus ni
 * l'état « rentré » : le serveur les déduit des deux pointages.
 */
@RestController
@RequestMapping("/api/missions-groupe")
public class MissionGroupController {

    private final MissionGroupService service;

    public MissionGroupController(MissionGroupService service) {
        this.service = service;
    }

    /** Le technicien ne reçoit que ses dossiers ; le filtre est posé par le service. */
    @GetMapping
    public Page<DossierResponse> list(@PageableDefault(size = 20) Pageable pageable,
                                      @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.list(pageable, jwt, auth);
    }

    /** Le numéro que le formulaire pré-remplit ; l'agent peut le remplacer. */
    @GetMapping("/prochain-numero")
    public NextNumberResponse prochainNumero() {
        return new NextNumberResponse(service.prochainNumero());
    }

    /**
     * Le suivi du téléphone : ses dossiers en cours, en lignes légères. La cloche
     * du terrain relit cette liste souvent ; c'est pourquoi elle est courte et
     * réservée au compte du technicien.
     */
    @GetMapping("/mes-dossiers")
    public List<MyDossierRow> mesDossiers(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.mesDossiers(jwt, auth);
    }

    @GetMapping("/historique")
    public List<HistoryRow> historique(@RequestParam("immatriculation") String immatriculation,
                                       Authentication auth) {
        return service.historique(immatriculation, auth);
    }

    /**
     * Les hommes et les voitures déjà pris sur la période que l'atelier prépare.
     * L'écran retire ces identifiants de ses listes ; le chevauchement se calcule
     * ici, sur les missions non rentrées, et le dossier en cours de modification
     * s'exclut.
     */
    @GetMapping("/disponibilites")
    public AvailabilityResponse disponibilites(@RequestParam("depart") LocalDateTime depart,
                                               @RequestParam("retour") LocalDateTime retour,
                                               @RequestParam(name = "excludeId", required = false)
                                               String excludeId,
                                               Authentication auth) {
        return service.disponibilites(depart, retour, excludeId, auth);
    }

    @GetMapping("/{id}")
    public DossierResponse get(@PathVariable String id,
                               @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.get(id, jwt, auth);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DossierResponse creer(@Valid @RequestBody DossierRequest request,
                                 @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.creer(request, jwt, auth);
    }

    /**
     * Modifier un dossier encore à l'atelier — client, bus, homme, voiture,
     * période estimée. Refusé dès que le départ réel est pointé ou qu'une photo
     * est versée : le papier est alors l'histoire du déplacement.
     */
    @PutMapping("/{id}")
    public DossierResponse modifier(@PathVariable String id,
                                    @Valid @RequestBody DossierRequest request,
                                    @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.modifier(id, request, jwt, auth);
    }

    /**
     * Annuler un dossier encore à l'atelier, avec son motif. Le dossier reste :
     * il n'occupe plus l'homme ni la voiture, et le téléphone cesse de l'annoncer.
     * Refusé dès que le départ réel est pointé.
     */
    @PostMapping("/{id}/annulation")
    public DossierResponse annuler(@PathVariable String id,
                                   @Valid @RequestBody AnnulationRequest request,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.annuler(id, request, jwt, auth);
    }

    /**
     * Le départ réel : l'heure et, si l'homme l'a relevé, le chiffre du compteur.
     * Les deux photos — la voiture et son compteur — doivent être versées avant,
     * sur la route des photos ; sans elles, le serveur refuse l'enregistrement.
     */
    @PostMapping("/{id}/depart")
    public DossierResponse depart(@PathVariable String id,
                                  @Valid @RequestBody DepartRequest request,
                                  @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.depart(id, request, jwt, auth);
    }

    /** Le retour : date, chiffre facultatif, et les diagnostics des bus du dossier. */
    @PostMapping("/{id}/retour")
    public DossierResponse retour(@PathVariable String id,
                                  @Valid @RequestBody RetourRequest request,
                                  @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.retour(id, request, jwt, auth);
    }

    /**
     * Le constat d'un bus : code d'erreur, pièce en cause, observations. Les deux
     * photos du bus — avant et après — se versent sur la route des photos.
     */
    @PostMapping("/{id}/buses/{busId}/constat")
    public DossierResponse constat(@PathVariable String id, @PathVariable String busId,
                                   @Valid @RequestBody ConstatRequest request,
                                   @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.constat(id, busId, request, jwt, auth);
    }

    /** Cocher ou décocher une ligne de la fiche atelier ; une ligne oubliée ne bloque rien. */
    @PostMapping("/{id}/checklist/{itemId}")
    public DossierResponse cocher(@PathVariable String id, @PathVariable String itemId,
                                  @Valid @RequestBody CocherRequest request,
                                  @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.cocher(id, itemId, request, jwt, auth);
    }
}
