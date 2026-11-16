package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos;
import tn.higer.sav_backend.dto.SavCaseDtos.FicheLue;
import tn.higer.sav_backend.dto.SavCaseDtos.SavCaseLinkOption;
import tn.higer.sav_backend.dto.SavCaseDtos.SavCaseRequest;
import tn.higer.sav_backend.dto.SavCaseDtos.SavCaseResponse;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.IncidentNature;
import tn.higer.sav_backend.model.PartOrderState;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SavPriority;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.FicheLabel;
import tn.higer.sav_backend.service.InterventionService;
import tn.higer.sav_backend.service.MissionService;
import tn.higer.sav_backend.service.SavCaseService;
import tn.higer.sav_backend.service.WarrantyClaimService;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD des dossiers SAV (visites, garanties, qualité, pièces, réclamations).
 * Lecture : tous les rôles connectés (règle générique GET /api/**).
 * Écriture : admin + gestionnaire_sav (règle générique /api/** — consultation bloquée).
 * Le statut n'est plus libre : il suit un graphe de transitions (voir TRANSITIONS).
 * Validation / refus d'une réclamation passent par POST /{id}/validate et /{id}/reject
 * (le PUT reste accepté pour une transition légale ou un simple changement de champs).
 */
@RestController
@RequestMapping("/api/sav-cases")
public class SavCaseController {

    /**
     * Graphe de transitions du cycle de vie SAV (statuts réels de SavCaseStatus) :
     *   OPEN          → IN_PROGRESS, ON_SITE, APPROVED, REJECTED, CLOSED
     *   IN_PROGRESS   → ON_SITE, PENDING_PARTS, APPROVED, REJECTED, CLOSED
     *   ON_SITE       → RETURNED, PENDING_PARTS, APPROVED, REJECTED, CLOSED
     *   PENDING_PARTS → IN_PROGRESS, ON_SITE, APPROVED, REJECTED, CLOSED
     *   APPROVED      → CLOSED
     *   REJECTED      → CLOSED
     *   RETURNED      → CLOSED
     *   CLOSED        → (terminal)
     * APPROVED (VALIDATED) / REJECTED ne sont atteignables que depuis un état examnable
     * (OPEN, IN_PROGRESS, ON_SITE, PENDING_PARTS), ce qui couvre le bouton validation du front.
     * ON_SITE et RETURNED sont surtout écrits par la sortie elle-même (la machine à états
     * de SavCaseService.suivreSortie) : le graphe les accepte pour qu'une modification
     * humaine ne se heurte pas à un statut que le serveur vient de poser.
     * Un PUT qui ne change pas le statut (transition identique) est toujours autorisé.
     */
    private static final Map<SavCaseStatus, Set<SavCaseStatus>> TRANSITIONS = buildTransitions();

    /** Un menu déroulant de 200 dossiers se parcourt encore ; au-delà, on cherche par la liste. */
    private static final int LINK_OPTIONS_MAX = 200;

    private static Map<SavCaseStatus, Set<SavCaseStatus>> buildTransitions() {
        Map<SavCaseStatus, Set<SavCaseStatus>> map = new EnumMap<>(SavCaseStatus.class);
        Set<SavCaseStatus> decisions = Set.of(SavCaseStatus.APPROVED, SavCaseStatus.REJECTED,
                SavCaseStatus.CLOSED);
        map.put(SavCaseStatus.OPEN, avec(decisions, SavCaseStatus.IN_PROGRESS, SavCaseStatus.ON_SITE));
        map.put(SavCaseStatus.IN_PROGRESS, avec(decisions, SavCaseStatus.ON_SITE, SavCaseStatus.PENDING_PARTS));
        map.put(SavCaseStatus.ON_SITE, avec(decisions, SavCaseStatus.RETURNED, SavCaseStatus.PENDING_PARTS));
        map.put(SavCaseStatus.PENDING_PARTS, avec(decisions, SavCaseStatus.IN_PROGRESS, SavCaseStatus.ON_SITE));
        map.put(SavCaseStatus.APPROVED, Set.of(SavCaseStatus.CLOSED));
        map.put(SavCaseStatus.REJECTED, Set.of(SavCaseStatus.CLOSED));
        map.put(SavCaseStatus.RETURNED, Set.of(SavCaseStatus.CLOSED));
        map.put(SavCaseStatus.CLOSED, Set.of());
        return map;
    }

    /** Les trois fins de parcours, plus les deux pas que l'atelier peut choisir lui-même. */
    private static Set<SavCaseStatus> avec(Set<SavCaseStatus> decisions, SavCaseStatus... pas) {
        Set<SavCaseStatus> merged = new java.util.HashSet<>(decisions);
        merged.addAll(java.util.List.of(pas));
        return Set.copyOf(merged);
    }

    private final SavCaseRepository savCases;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final SavCaseService cases;
    private final WarrantyClaimService claims;
    private final InterventionService interventions;
    private final MissionService missions;
    private final AuditService audit;

    public SavCaseController(SavCaseRepository savCases, VehicleRepository vehicles,
                             ClientRepository clients, SavCaseService cases,
                             WarrantyClaimService claims, InterventionService interventions,
                             MissionService missions, AuditService audit) {
        this.savCases = savCases;
        this.vehicles = vehicles;
        this.clients = clients;
        this.cases = cases;
        this.claims = claims;
        this.interventions = interventions;
        this.missions = missions;
        this.audit = audit;
    }

    @GetMapping
    public Page<SavCaseResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // Le n° de sortie n'est pas porté par la liste lue par tous : le module
        // Dépannage est interne, et un compte de consultation ne doit pas voir
        // qu'une sortie court sur un dossier. Il apparaît à l'écriture, et se lit
        // surtout sur la colonne « Dossier » de l'écran Dépannage.
        Page<SavCase> page = savCases.findAll(pageable);
        List<SavCase> lignes = page.getContent();
        Map<String, List<SavCase>> enfants = enfantsDe(lignes);
        Map<String, String> parents = numerosDeParents(lignes);
        Map<String, Vehicle> bus = busDePage(lignes);
        Map<String, Client> clientsParId = clientsDePage(lignes);
        return page.map(c -> SavCaseResponse.from(c, null,
                enfants.getOrDefault(c.getId(), List.of()), numeroParent(parents, c),
                ficheDe(lire(bus, c.getVehicleId()), lire(clientsParId, c.getClientId()))));
    }

    /**
     * Le numéro du dossier dont celui-ci est né. Un dossier sans parent en est la
     * majorité (une visite, une pièce, un claim ouvert seul) : demander sa clé à une
     * carte vide immuable lève un NPE et tue toute la page — c'est le 500 du
     * 26/10 sur « GET /api/sav-cases », l'écran restait vide sur 17 dossiers.
     */
    private static String numeroParent(Map<String, String> parents, SavCase c) {
        return c.getParentCaseId() == null ? null : parents.get(c.getParentCaseId());
    }

    /** Les dossiers nés de ceux de la page, en une seule requête groupée. */
    private Map<String, List<SavCase>> enfantsDe(List<SavCase> lignes) {
        List<String> ids = lignes.stream().map(SavCase::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return savCases.findByParentCaseIdIn(ids).stream()
                .collect(Collectors.groupingBy(SavCase::getParentCaseId));
    }

    /** Numéro du dossier parent, pour que la ligne dise « né de REC-… » sans recharger. */
    private Map<String, String> numerosDeParents(List<SavCase> lignes) {
        List<String> parentIds = lignes.stream().map(SavCase::getParentCaseId)
                .filter(Objects::nonNull).distinct().toList();
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        return savCases.findAllById(parentIds).stream()
                .collect(Collectors.toMap(SavCase::getId, SavCase::getCaseNumber));
    }

    // ------------------------------------------------------------------------------------------
    // Le bus et le client nommés : la vue du dossier, pas une seconde liste du portefeuille
    // ------------------------------------------------------------------------------------------

    /**
     * Les bus de la page, relus en une seule lecture. Vingt dossiers affichés ne
     * demandent pas vingt lectures de fiche : la liste s'ouvre au guichet pendant que
     * l'atelier travaille. Le nom se fait dans {@link FicheLabel} — le même bus porte
     * le même mot sur les Dossiers, les Sorties et les Missions.
     */
    private Map<String, Vehicle> busDePage(List<SavCase> lignes) {
        List<String> ids = lignes.stream().map(SavCase::getVehicleId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return vehicles.findAllById(ids).stream()
                .collect(Collectors.toMap(Vehicle::getId, v -> v));
    }

    /** Les clients de la page, relus eux aussi en une lecture groupée. */
    private Map<String, Client> clientsDePage(List<SavCase> lignes) {
        List<String> ids = lignes.stream().map(SavCase::getClientId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return clients.findAllById(ids).stream()
                .collect(Collectors.toMap(Client::getId, c -> c));
    }

    /**
     * La fiche nommée d'un dossier lu seul : une lecture du bus, une du client. La
     * clé absente se teste AVANT la carte — demander sa clé à une carte vide immuable
     * lève un NPE, et c'est précisément le 500 du 26/10 qui avait vidé l'écran des
     * Dossiers sur 17 lignes.
     */
    private FicheLue ficheDe(SavCase c) {
        Vehicle bus = c.getVehicleId() == null ? null
                : vehicles.findById(c.getVehicleId()).orElse(null);
        Client client = c.getClientId() == null ? null
                : clients.findById(c.getClientId()).orElse(null);
        return ficheDe(bus, client);
    }

    /** Les mots de la fiche, dans l'ordre de la vue. Une fiche absente rend une case vide. */
    private static FicheLue ficheDe(Vehicle bus, Client client) {
        return new FicheLue(FicheLabel.bus(bus), FicheLabel.vin(bus), FicheLabel.immatriculation(bus),
                FicheLabel.modele(bus), FicheLabel.client(client));
    }

    private static <T> T lire(Map<String, T> carte, String id) {
        return id == null ? null : carte.get(id);
    }

    /** Liste courte pour rattacher une sortie de dépannage à un dossier existant. */
    @GetMapping("/link-options")
    public List<SavCaseLinkOption> linkOptions() {
        return savCases.findAll(PageRequest.of(0, LINK_OPTIONS_MAX,
                        Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(SavCaseLinkOption::from).getContent();
    }

    @GetMapping("/{id}")
    public SavCaseResponse get(@PathVariable String id) {
        SavCase c = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        String numeroParent = c.getParentCaseId() == null ? null
                : savCases.findById(c.getParentCaseId()).map(SavCase::getCaseNumber).orElse(null);
        return SavCaseResponse.from(c, null,
                savCases.findByParentCaseIdOrderByCreatedAtAsc(id), numeroParent,
                ficheDe(c));
    }

    /**
     * Le dossier garantie né d'une réclamation. Une seule porte, et pas un champ de
     * plus dans le formulaire : la filiation ne se décrète pas depuis un
     * navigateur, sinon un poste peut réécrire après coup l'histoire d'un papier
     * déjà déposé chez le constructeur.
     */
    @PostMapping("/{id}/open-claim")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SavCaseResponse openClaim(@PathVariable String id,
                                     @AuthenticationPrincipal Jwt jwt,
                                     Authentication auth) {
        SavCase parent = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        SavCase claim = cases.openClaimFrom(parent, jwt);
        return SavCaseResponse.from(claim, interventions.openSortieNumber(claim.getId()),
                List.of(), parent.getCaseNumber(), ficheDe(claim));
    }

    /**
     * La réponse du constructeur, tenue à part du statut du dossier. Le dossier se
     * clôt quand le bus est rendu ; l'appel en garantie, lui, se clôt quand l'argent
     * ou le refus est tombé.
     */
    @PutMapping("/{id}/claim")
    public SavCaseResponse majClaim(@PathVariable String id,
                                    @RequestBody SavCaseDtos.ClaimRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        SavCase c = claims.maj(id, request, jwt);
        String numeroParent = c.getParentCaseId() == null ? null
                : savCases.findById(c.getParentCaseId()).map(SavCase::getCaseNumber).orElse(null);
        return SavCaseResponse.from(c, null,
                savCases.findByParentCaseIdOrderByCreatedAtAsc(id), numeroParent,
                ficheDe(c));
    }

    /**
     * Transaction ici, pas dans un service : le dossier et la demande qu'il déclare
     * doivent exister ensemble. Un « un technicien doit aller sur place » qui
     * échouerait après coup laisserait un dossier sans trace de la panne appelable,
     * et l'opérateur recommencerait la saisie — deux dossiers pour une panne.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SavCaseResponse create(@Valid @RequestBody SavCaseRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        SavCase c = new SavCase();
        apply(c, request);
        c.setId("sav-" + UUID.randomUUID());
        // Le nom du technicien ne vient pas de la saisie : le serveur vérifie le
        // compte choisi, ou refuse si une sortie rattache déjà un autre homme.
        cases.applyTechnician(c, request.technicianUserId(), jwt);
        // Le numéro vient du serveur quand l'écran le laisse vide. S'il est fourni
        // (reprise d'un numéro papier), on vérifie juste qu'il n'existe pas déjà.
        if (request.caseNumber() == null || request.caseNumber().isBlank()) {
            c.setCaseNumber(cases.nextNumber(c.getType()));
        } else if (savCases.existsByCaseNumberIgnoreCase(c.getCaseNumber().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce numéro de dossier existe déjà : laissez le champ vide pour que le serveur le donne.");
        }
        if (c.getCreatedAt() == null) {
            c.setCreatedAt(LocalDate.now());
        }
        savCases.save(c);
        audit.record("CREATE", "SAV_CASE", c.getId(),
                "Création du dossier " + c.getCaseNumber() + " (statut " + label(c.getStatus()) + ")", jwt);
        String demande = request.requiresIntervention() ? enregistrerLaDemande(c, jwt) : null;
        return SavCaseResponse.from(c, interventions.openSortieNumber(c.getId()), demande,
                ficheDe(c));
    }

    @PutMapping("/{id}")
    @Transactional
    public SavCaseResponse update(@PathVariable String id, @Valid @RequestBody SavCaseRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        SavCase c = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        String wanted = request.caseNumber();
        if (wanted != null && !wanted.isBlank()
                && savCases.existsByCaseNumberIgnoreCaseAndIdNot(wanted.trim(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce numéro de dossier existe déjà.");
        }
        SavCaseStatus from = c.getStatus();
        SavCaseStatus to = request.status() != null
                ? request.status()
                : (from != null ? from : SavCaseStatus.OPEN);
        checkTransition(from, to);
        apply(c, request);
        cases.applyTechnician(c, request.technicianUserId(), jwt);
        savCases.save(c);
        audit.record("UPDATE", "SAV_CASE", id,
                "Mise à jour du dossier " + c.getCaseNumber() + " (statut " + label(from) + " → " + label(c.getStatus()) + ")", jwt);
        // Coché après coup : la panne est appelable tardivement, la demande s'écrit à ce moment.
        String demande = request.requiresIntervention() ? enregistrerLaDemande(c, jwt) : null;
        return SavCaseResponse.from(c, interventions.openSortieNumber(id), demande,
                ficheDe(c));
    }

    /**
     * La demande née d'un dossier déjà écrit. Le geste n'a pas d'endpoint propre : il
     * se fait en cochant la case du dossier, create et update appelant tous deux
     * cette méthode — un dossier et sa demande doivent exister ensemble ou pas du tout.
     *
     * Elle enregistre une mission, elle ne fait plus sortir personne. Envoyer un homme
     * et une voiture est le geste du chef d'atelier sur l'écran Missions : c'est là que
     * la décision se prend et se journalise. Un dossier coché par erreur ne doit pas
     * immobiliser une équipe que personne n'a décidée.
     *
     * Rend le numéro de la demande, pour que l'écran du dossier puisse le montrer :
     * « j'ai coché, rien ne s'est passé » ne se discute pas sans preuve.
     */
    private String enregistrerLaDemande(SavCase c, Jwt jwt) {
        return missions.enregistrerDepuisDossier(c, jwt).getNumber();
    }

    /**
     * Le dossier reprend le nom de l'homme qui a fait la sortie.
     *
     * Le champ « Technicien » de la fiche est verrouillé dès qu'une sortie est au
     * dos, et la sortie partie ne se retire plus : sans cette porte, un dossier
     * portant le mauvais nom ne se corrigeait nulle part. Le serveur ne prend que
     * le nom porté par la sortie, nomme celle-ci dans la trace, et refuse si le
     * dossier n'a pas de sortie ou si la sortie n'a pas d'homme.
     */
    @PostMapping("/{id}/reprendre-technicien")
    public SavCaseResponse reprendreTechnicien(@PathVariable String id,
                                               @AuthenticationPrincipal Jwt jwt) {
        SavCase c = cases.reprendreTechnicienDeLaSortie(id, jwt);
        return SavCaseResponse.from(c, interventions.openSortieNumber(id), null, ficheDe(c));
    }

    /** Valide une réclamation : la fait passer en APPROVED (seule transition légale acceptée). */
    @PostMapping("/{id}/validate")
    public SavCaseResponse validate(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        SavCase c = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        SavCaseStatus from = c.getStatus();
        checkTransition(from, SavCaseStatus.APPROVED);
        c.setStatus(SavCaseStatus.APPROVED);
        c.setResolution("Réclamation validée par le Gestionnaire SAV de Saphir Véhicules et Automobiles. "
                + "Prise en charge accordée sous garantie.");
        c.setUpdatedAt(LocalDate.now());
        savCases.save(c);
        audit.record("VALIDATE", "SAV_CASE", id,
                "Validation du dossier " + c.getCaseNumber() + " (statut " + label(from) + " → APPROVED)", jwt);
        return SavCaseResponse.from(c, ficheDe(c));
    }

    /** Refuse une réclamation : la fait passer en REJECTED (seule transition légale acceptée). */
    @PostMapping("/{id}/reject")
    public SavCaseResponse reject(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        SavCase c = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        SavCaseStatus from = c.getStatus();
        checkTransition(from, SavCaseStatus.REJECTED);
        c.setStatus(SavCaseStatus.REJECTED);
        c.setResolution("Réclamation refusée par le Gestionnaire SAV de Saphir Véhicules et Automobiles. "
                + "Non éligible aux critères de prise en charge.");
        c.setUpdatedAt(LocalDate.now());
        savCases.save(c);
        audit.record("REJECT", "SAV_CASE", id,
                "Refus du dossier " + c.getCaseNumber() + " (statut " + label(from) + " → REJECTED)", jwt);
        return SavCaseResponse.from(c, ficheDe(c));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        SavCase c = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        // Rien ne détache les sorties ni les demandes quand le dossier part : la
        // garde se fait avant l'écriture, sinon le lien cassé est déjà écrit.
        cases.verifierSuppression(c);
        savCases.deleteById(id);
        audit.record("DELETE", "SAV_CASE", id, "Suppression du dossier " + c.getCaseNumber(), jwt);
    }

    private void checkTransition(SavCaseStatus from, SavCaseStatus to) {
        if (to == null) {
            return;
        }
        if (from == null || from == to) {
            return; // premier positionnement ou pas de changement de statut
        }
        Set<SavCaseStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transition de statut interdite : « " + label(from) + " » vers « " + label(to) + " ».");
        }
    }

    private static String label(SavCaseStatus status) {
        return status != null ? status.name().toLowerCase() : "aucun";
    }

    /** Une case remplie : vide et blancs ne comptent pas. */
    private static boolean rempli(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    /** Une case du bloc qui dit quelque chose — texte, date, nombre, oui/non. */
    private static boolean uneCase(Object valeur) {
        if (valeur == null) {
            return false;
        }
        return valeur instanceof String texte ? !texte.isBlank() : true;
    }

    /** Le bloc porte au moins une case remplie : de quoi refuser qu'il voyage seul. */
    private static boolean unBlocRempli(Object... cases) {
        return java.util.Arrays.stream(cases).anyMatch(SavCaseController::uneCase);
    }

    /**
     * Les cases d'un bloc de famille ne se remplissent que sur leur famille.
     *
     * Refusé, pas effacé en douce : le poste qui voit « vos dates de visite ont été
     * supprimées » ne sait pas laquelle des deux cases il a mal cochée. Ici le
     * message nomme le bloc et la famille du dossier.
     */
    private static void refuserHorsFamille(boolean estLaBonneFamille, String bloc, String famille,
                                           SavCaseType type, boolean desCasesRemplies) {
        if (estLaBonneFamille || !desCasesRemplies) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Un dossier « " + (type != null ? type.name().toLowerCase() : "sans famille")
                        + " » ne porte pas de case " + bloc + ". Videz le bloc " + bloc
                        + ", ou rangez le dossier dans la famille « " + famille + " ».");
    }

    private void apply(SavCase c, SavCaseRequest r) {
        if (r.caseNumber() != null && !r.caseNumber().isBlank()) {
            c.setCaseNumber(r.caseNumber().trim());
        }
        c.setType(r.type());
        c.setTitle(r.title().trim());
        c.setVehicleId(r.vehicleId());
        c.setClientId(r.clientId() != null ? r.clientId() : "");
        c.setStatus(r.status() != null ? r.status()
                : (c.getStatus() != null ? c.getStatus() : SavCaseStatus.OPEN));
        c.setPriority(r.priority() != null ? r.priority() : SavPriority.MEDIUM);
        c.setDescription(r.description());
        c.setResolution(r.resolution());
        c.setWarrantyApprovalNumber(r.warrantyApprovalNumber());
        c.setCostEstimate(r.costEstimate() != null ? r.costEstimate() : 0.0);
        // La demande du client, telle qu'elle est arrivée au téléphone ou par écrit.
        // Ces quatre cases sont la seule trace de l'appel : sans elles, un client
        // qui appelle et n'obtient rien ne laisse rien derrière lui.
        c.setCallerName(r.callerName());
        c.setCallerChannel(r.callerChannel());
        c.setCalledAt(r.calledAt());
        c.setExpectedAction(r.expectedAction());
        // Les cases des familles « visites » et « qualité » (V23). Elles ne
        // voyagent pas avec le dossier : passer d'une famille à l'autre efface les
        // cases de la précédente, et les remplir sur la mauvaise famille est refusé
        // — une date de venue posée sur une réclamation se relit comme une preuve.
        boolean visite = r.type() == SavCaseType.VISITES;
        boolean qualite = r.type() == SavCaseType.QUALITE;
        boolean pieces = r.type() == SavCaseType.PIECES;
        boolean garantie = r.type() == SavCaseType.GARANTIES;
        refuserHorsFamille(visite, "de visite", "visites", r.type(),
                r.plannedDate() != null || r.visitDate() != null || r.visitState() != null
                        || rempli(r.nextActions()));
        refuserHorsFamille(visite, "de visite périodique rattachée à un forfait", "visites", r.type(),
                rempli(r.soldPlanId()) || rempli(r.planStepId()));
        refuserHorsFamille(qualite || garantie, "de déclaration", "qualité ou garanties", r.type(),
                r.declarationDate() != null);
        refuserHorsFamille(qualite, "de criticité", "qualité", r.type(),
                rempli(r.classification()) || rempli(r.severity())
                        || rempli(r.correctiveActions()) || rempli(r.preventiveActions()));
        c.setPlannedDate(visite ? r.plannedDate() : null);
        c.setVisitDate(visite ? r.visitDate() : null);
        c.setVisitState(visite ? r.visitState() : null);
        c.setNextActions(visite ? r.nextActions() : null);
        // La visite périodique se rattache au forfait vendu : c'est le service qui
        // connaît les forfaits, et qui refuse un palier qui n'est pas celui de CE bus.
        cases.marquerLaVisite(c, r.soldPlanId(), r.planStepId());
        // « Date de déclaration » (qualité) et « Date de rédaction AG » (garantie)
        // sont la même colonne : le jour où le papier est écrit.
        c.setDeclarationDate(qualite || garantie ? r.declarationDate() : null);
        c.setClassification(qualite ? r.classification() : null);
        c.setSeverity(qualite ? r.severity() : null);
        c.setCorrectiveActions(qualite ? r.correctiveActions() : null);
        c.setPreventiveActions(qualite ? r.preventiveActions() : null);
        appliquerCommande(c, r.order(), pieces);
        appliquerPapierAg(c, r.agPaper(), garantie);
        // partReference et quantity ne sont plus écrits ici : une pièce posée est
        // une ligne nommée depuis V21, et le dossier la lit dans son papier.
        if (r.createdAt() != null) {
            c.setCreatedAt(r.createdAt());
        }
        if (r.updatedAt() != null) {
            c.setUpdatedAt(r.updatedAt());
        }
        SavCaseService.daterCloture(c);
    }

    /**
     * Le bloc « pièces de rechange » : une commande, ce qu'elle a donné.
     *
     * Trois règles, et elles tiennent la colonne d'attente d'un bus :
     *  - le bloc ne se remplit que sur la famille « pièces » ;
     *  - une pièce annoncée livrée — toute ou en partie — se date ;
     *  - « livrée » avec moins reçu que commandé est refusé : c'est une commande
     *    partielle, et l'appeler « livrée » endormirait la relance chez Higer.
     *
     * Le bloc absent de la requête ne vide rien : l'écran Dépannage modifie un
     * dossier sans rien savoir des pièces. Présent, il s'écrit tel quel, blanc
     * compris.
     */
    private void appliquerCommande(SavCase c, SavCaseDtos.PartOrderRequest order, boolean pieces) {
        if (!pieces) {
            refuserHorsFamille(false, "de commande de pièces", "pièces", c.getType(),
                    order != null && unBlocRempli(order.orderDate(), order.reference(), order.designation(),
                            order.quantityOrdered(), order.quantityReceived(), order.state(),
                            order.deliveryDate(), order.comments()));
            c.setOrderDate(null);
            c.setOrderReference(null);
            c.setOrderDesignation(null);
            c.setQuantityOrdered(null);
            c.setQuantityReceived(null);
            c.setOrderState(null);
            c.setDeliveryDate(null);
            c.setOrderComments(null);
            return;
        }
        if (order == null) {
            return;
        }
        PartOrderState etat = order.state();
        if ((etat == PartOrderState.LIVREE || etat == PartOrderState.PARTIELLE) && order.deliveryDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une commande de pièces annoncée « " + (etat != null ? etat.label().toLowerCase() : "livrée")
                            + " » se date : indiquez la date de livraison.");
        }
        Integer commandee = order.quantityOrdered();
        Integer recue = order.quantityReceived();
        if (etat == PartOrderState.LIVREE && commandee != null && recue != null && recue < commandee) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vous avez reçu " + recue + " pièce(s) sur " + commandee + " commandées : l'état est"
                            + " « partielle », pas « livrée ». La commande attend encore le reste.");
        }
        if (etat == PartOrderState.PARTIELLE && (commandee == null || recue == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une livraison partielle se chiffre : quantité commandée et quantité reçue.");
        }
        c.setOrderDate(order.orderDate());
        c.setOrderReference(order.reference());
        c.setOrderDesignation(order.designation());
        c.setQuantityOrdered(commandee);
        c.setQuantityReceived(recue);
        c.setOrderState(etat);
        c.setDeliveryDate(order.deliveryDate());
        c.setOrderComments(order.comments());
    }

    /**
     * Le papier d'appel en garantie, écrit tel que l'atelier le remplit.
     *
     * Une seule règle de fond, et c'est celle que le constructeur vérifie : un
     * incident dit « répétitif » se prouve par le numéro du AG qui l'a déjà
     * signalé. Sans lui, « répétitif » est une adjectif de plus dans un papier.
     *
     * Aucun montant n'est calculé ici : le barème de remboursement Higer n'a
     * jamais été fourni, et un total fabriqué par le portail engagerait quelqu'un
     * qui ne l'a pas décidé.
     */
    private void appliquerPapierAg(SavCase c, SavCaseDtos.AgPaperRequest p, boolean garantie) {
        if (!garantie) {
            refuserHorsFamille(false, "de papier AG", "garanties", c.getType(),
                    p != null && unBlocRempli(p.writerName(), p.repairOrderNumber(), p.repairOrderDate(),
                            p.incidentDate(), p.importer(), p.dealerNumber(), p.unitNumber(), p.camNumber(),
                            p.km(), p.securityImpact(), p.incidentType(), p.concernedPart(),
                            p.partNewOrReconditioned(), p.organKm(), p.previousClaimNumber(),
                            p.customerContext(), p.cause(), p.comments(), p.laborOperationCode(),
                            p.laborDescription(), p.allocatedTime(), p.hourlyCost(), p.laborNet(),
                            p.causalPartReference(), p.causalPartDescription(), p.functionGroup(),
                            p.serialNumber(), p.defectType(), p.defectCode(), p.otherCosts()));
            viderPapierAg(c);
            return;
        }
        if (p == null) {
            return;
        }
        if (p.incidentType() == IncidentNature.REPETITIF && !rempli(p.previousClaimNumber())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un incident « répétitif » se prouve par le numéro du AG qui l'a déjà signalé —"
                            + " sans lui, le constructeur écarte la demande comme non fondée.");
        }
        c.setAgWriterName(p.writerName());
        c.setAgRepairOrderNumber(p.repairOrderNumber());
        c.setAgRepairOrderDate(p.repairOrderDate());
        c.setAgIncidentDate(p.incidentDate());
        c.setAgImporter(p.importer());
        c.setAgDealerNumber(p.dealerNumber());
        c.setAgUnitNumber(p.unitNumber());
        c.setAgCamNumber(p.camNumber());
        c.setAgKm(p.km());
        c.setAgSecurityImpact(p.securityImpact());
        c.setAgIncidentType(p.incidentType());
        c.setAgConcernedPart(p.concernedPart());
        c.setAgPartNewOrReconditioned(p.partNewOrReconditioned());
        c.setAgOrganKm(p.organKm());
        c.setAgPreviousClaimNumber(p.previousClaimNumber());
        c.setAgCustomerContext(p.customerContext());
        c.setAgCause(p.cause());
        c.setAgComments(p.comments());
        c.setAgLaborOperationCode(p.laborOperationCode());
        c.setAgLaborDescription(p.laborDescription());
        c.setAgAllocatedTime(p.allocatedTime());
        c.setAgHourlyCost(p.hourlyCost());
        c.setAgLaborNet(p.laborNet());
        c.setAgCausalPartReference(p.causalPartReference());
        c.setAgCausalPartDescription(p.causalPartDescription());
        c.setAgFunctionGroup(p.functionGroup());
        c.setAgSerialNumber(p.serialNumber());
        c.setAgDefectType(p.defectType());
        c.setAgDefectCode(p.defectCode());
        c.setAgOtherCosts(p.otherCosts());
    }

    /** Passer de la famille garanties à une autre efface le papier : il n'a plus de signataire. */
    private static void viderPapierAg(SavCase c) {
        c.setAgWriterName(null);
        c.setAgRepairOrderNumber(null);
        c.setAgRepairOrderDate(null);
        c.setAgIncidentDate(null);
        c.setAgImporter(null);
        c.setAgDealerNumber(null);
        c.setAgUnitNumber(null);
        c.setAgCamNumber(null);
        c.setAgKm(null);
        c.setAgSecurityImpact(null);
        c.setAgIncidentType(null);
        c.setAgConcernedPart(null);
        c.setAgPartNewOrReconditioned(null);
        c.setAgOrganKm(null);
        c.setAgPreviousClaimNumber(null);
        c.setAgCustomerContext(null);
        c.setAgCause(null);
        c.setAgComments(null);
        c.setAgLaborOperationCode(null);
        c.setAgLaborDescription(null);
        c.setAgAllocatedTime(null);
        c.setAgHourlyCost(null);
        c.setAgLaborNet(null);
        c.setAgCausalPartReference(null);
        c.setAgCausalPartDescription(null);
        c.setAgFunctionGroup(null);
        c.setAgSerialNumber(null);
        c.setAgDefectType(null);
        c.setAgDefectCode(null);
        c.setAgOtherCosts(null);
    }
}
