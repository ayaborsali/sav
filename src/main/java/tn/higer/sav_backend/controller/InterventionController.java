package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.higer.sav_backend.dto.InterventionDtos.*;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.InterventionDocumentService;
import tn.higer.sav_backend.service.InterventionPdfService;
import tn.higer.sav_backend.service.InterventionService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Sorties de dépannage. Le contrôleur ne fait que brancher HTTP sur la machine
 * à états ({@link InterventionService}) : les règles d'heure, de statut et de
 * propriété sont serveur, parce qu'un écran qu'on contourne par une requête
 * manuelle ne serait plus un contrôle.
 *
 * Une étape se poste (POST /{id}/depart, /arrive, …), elle ne se met pas à jour
 * par PUT : c'est ce qui garantit que l'heure enregistrée est l'heure du clic.
 */
@RestController
@RequestMapping("/api/interventions")
public class InterventionController {

    private final InterventionService service;
    private final InterventionDocumentService documentService;
    private final InterventionPdfService pdfService;
    private final AuditService audit;

    public InterventionController(InterventionService service,
                                  InterventionDocumentService documentService,
                                  InterventionPdfService pdfService,
                                  AuditService audit) {
        this.service = service;
        this.documentService = documentService;
        this.pdfService = pdfService;
        this.audit = audit;
    }

    @GetMapping
    public List<InterventionResponse> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) Boolean open,
                                           @AuthenticationPrincipal Jwt jwt,
                                           Authentication auth) {
        return service.list(status, open, auth, jwt);
    }

    /** « Sorties du jour » : la liste ouverte aujourd'hui plus les compteurs. */
    @GetMapping("/today")
    public DayBoardResponse today(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.dayBoard(auth, jwt);
    }

    /** « Mes interventions » du technicien : une seule entrée de menu pour lui. */
    @GetMapping("/mine")
    public List<InterventionResponse> mine(@AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.myInterventions(auth, jwt);
    }

    /**
     * Qui est envoyable aujourd'hui. L'affectation ne propose que des hommes libres,
     * parce que proposer quelqu'un de dehors ferait un refus après le clic — et un
     * refus qui arrive trop tard ne se lit pas.
     */
    @GetMapping("/crew")
    public CrewResponse crew(Authentication auth) {
        return service.crew(auth);
    }

    /**
     * Les sorties rattachées à un dossier SAV. C'est l'autre moitié du lien : la
     * fiche SAV doit montrer ce qui a été fait sur le terrain, pas seulement le
     * dossier que le poste a tapé.
     */
    @GetMapping("/by-sav-case/{savCaseId}")
    public List<InterventionResponse> forSavCase(@PathVariable String savCaseId,
                                                 @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.forSavCase(savCaseId, auth, jwt);
    }

    /** Cocher ou décocher une ligne de la fiche atelier : une ligne oubliée ne bloque rien. */
    @PostMapping("/{id}/checklist/{itemId}")
    public InterventionResponse cocher(@PathVariable String id, @PathVariable String itemId,
                                       @Valid @RequestBody CocherRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.cocher(id, itemId, request, jwt, auth);
    }

    @GetMapping("/{id}")
    public InterventionDetailResponse get(@PathVariable String id,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.detail(id, auth, jwt);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterventionResponse create(@Valid @RequestBody InterventionRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.create(request, jwt, auth);
    }

    /**
     * Il n'y a plus ici de route « envoyer une équipe sur un rappel ». Le geste
     * s'appelle {@link InterventionService#envoyerPourRappel} et n'est plus reachable
     * que par une mission, derrière la route {@code /api/missions/{id}/envoyer-un-homme} :
     * une sortie ne naît que d'une décision d'atelier tracée, jamais d'un clic sur un
     * bandeau d'alerte. Le rappel, lui, reste ouvert jusqu'au palier fait.
     */

    @PutMapping("/{id}")
    public InterventionResponse update(@PathVariable String id,
                                       @Valid @RequestBody InterventionRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.update(id, request, jwt, auth);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        service.delete(id, jwt, auth);
    }

    @PostMapping("/{id}/dispatch")
    public InterventionResponse dispatch(@PathVariable String id, @Valid @RequestBody DispatchRequest request,
                                         @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.dispatch(id, request, jwt, auth);
    }

    @PostMapping("/{id}/depart")
    public InterventionResponse depart(@PathVariable String id, @Valid @RequestBody DepartRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.depart(id, request, jwt, auth);
    }

    @PostMapping("/{id}/arrive")
    public InterventionResponse arrive(@PathVariable String id,
                                       @RequestBody(required = false) ArrivalRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.arrive(id, request, jwt, auth);
    }

    @PostMapping("/{id}/diagnosis")
    public InterventionResponse diagnosis(@PathVariable String id, @Valid @RequestBody ProgressRequest request,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.progress(id, request, jwt, auth);
    }

    /** Issue du passage : « repaired » ou « towed ». */
    @PostMapping("/{id}/finish")
    public InterventionResponse finish(@PathVariable String id, @Valid @RequestBody FinishRequest request,
                                       @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.finish(id, request, jwt, auth);
    }

    @PostMapping("/{id}/leave-site")
    public InterventionResponse leaveSite(@PathVariable String id,
                                          @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.leaveSite(id, jwt, auth);
    }

    @PostMapping("/{id}/return")
    public InterventionResponse returnToWorkshop(@PathVariable String id,
                                                 @Valid @RequestBody ReturnRequest request,
                                                 @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.returnToWorkshop(id, request, jwt, auth);
    }

    /**
     * La rentrée que personne n'a pointée : l'atelier constate que la voiture est
     * revenue. Les heures de terrain restent vides — le geste n'invente rien — et
     * la raison écrite reste au journal.
     */
    @PostMapping("/{id}/declare-return")
    public InterventionResponse declareReturn(@PathVariable String id,
                                              @Valid @RequestBody DeclareReturnRequest request,
                                              @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.declareReturn(id, request, jwt, auth);
    }

    @PostMapping("/{id}/close")
    public InterventionResponse close(@PathVariable String id, @Valid @RequestBody ClosureRequest request,
                                      @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.close(id, request, jwt, auth);
    }

    /**
     * « Déclarer en qualité » : le clic d'un chef d'atelier sur une sortie. Le corps
     * est optionnel — un clic sans rien d'écrit déclare le problème, les quatre cases
     * de l'atelier se remplissent si quelqu'un les remplit. La sortie ne change ni de
     * statut ni d'affectation, et rien ne se déclare tout seul : un dossier qualité
     * écrit par le serveur serait une anomalie que personne n'a vue.
     */
    @PostMapping("/{id}/declaration-qualite")
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationQualiteResponse declarerEnQualite(@PathVariable String id,
                                                        @Valid @RequestBody(required = false)
                                                        DeclarationQualiteRequest declaration,
                                                        @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.declarerEnQualite(id, declaration, jwt, auth);
    }

    @PostMapping("/{id}/note")
    public InterventionResponse note(@PathVariable String id, @Valid @RequestBody NoteRequest request,
                                     @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.note(id, request, jwt, auth);
    }

    /** Rectification d'une heure ou d'un compteur : ajoutée au journal, jamais substituée en cachette. */
    @PostMapping("/{id}/correct")
    public InterventionResponse correct(@PathVariable String id, @Valid @RequestBody CorrectionRequest request,
                                        @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.correct(id, request, jwt, auth);
    }

    /**
     * Bon d'intervention en PDF, prêt à imprimer et à faire signer. Le document est
     * reconstruit intégralement depuis la base à chaque demande : il ne peut pas rester
     * en retard sur la sortie. La mise en page est déléguée à un script Python
     * (voir {@link InterventionPdfService}).
     *
     * {@code version=client} (par défaut) ne contient aucun montant ; {@code version=interne}
     * ajoute la prime validée et n'est accepté que pour la paie. L'édition est journalisée :
     * une prime imprimée se sait.
     */
    @GetMapping("/{id}/document.pdf")
    public ResponseEntity<byte[]> document(@PathVariable String id,
                                           @RequestParam(required = false, defaultValue = "client") String version,
                                           @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        Map<String, Object> plan = documentService.payload(id, version, auth, jwt);
        byte[] pdf = pdfService.render(plan);
        boolean internal = InterventionDocumentService.VERSION_INTERNE.equalsIgnoreCase(version);
        String number = String.valueOf(plan.getOrDefault("number", id));
        audit.record("DOWNLOAD", "INTERVENTION", id,
                "Édition du bon d'intervention " + number + " ("
                        + (internal ? "version interne, avec la prime" : "version client, sans montant") + ")",
                jwt);
        String filename = number + "-bon-" + (internal ? "interne" : "client") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }
}
