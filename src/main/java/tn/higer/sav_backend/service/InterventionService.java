package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.*;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianLookup;
import tn.higer.sav_backend.model.*;
import tn.higer.sav_backend.repository.*;
import tn.higer.sav_backend.security.Access;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Machine à états des sorties de dépannage — le coeur du contrôle.
 *
 * Trois principes, à ne pas diluer :
 * 1. une heure d'étape est posée par le serveur quand un bouton est pressé.
 *    Aucun bouton n'écrit une heure venue d'un champ : « départ 9 h » tapé à la
 *    main ne prouverait rien, alors que le module existe pour vérifier qui est
 *    parti et combien de temps il est resté dehors ;
 * 2. toute interdiction est écrite en français et renvoyée au client, qui
 *    l'affiche à l'écran (les notifications muettes ne sont pas lues) ;
 * 3. une valeur fausse se rectifie en ajoutant une ligne CORRECTED au journal,
 *    jamais en réécrivant l'historique.
 *
 * Un compte TECHNICIEN ne voit que ses sorties, et un identifiant qui n'est pas
 * la sienne répond 404 : confirmer qu'une sortie existe serait déjà une fuite.
 */
@Service
public class InterventionService {

    private static final String PREFIX = "DP-";
    private static final int SEQUENCE_WIDTH = 3;

    private final InterventionRepository interventions;
    private final InterventionEventRepository events;
    private final TechnicianRepository technicians;
    private final ServiceVehicleRepository serviceVehicles;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final SavCaseRepository savCases;
    private final SavCaseService savCaseService;
    private final TechnicianDirectory directory;
    private final InterventionPhotoRepository photos;
    private final AuditService audit;
    private final VehicleMileageService compteur;

    /**
     * Le forfait que la sortie vient honorer. Le sens est unique : la sortie demande
     * au suivi des paliers ce qu'il autorise ({@code SoldPlanAlertService}) et ce
     * qu'il écrit ({@code SoldPlanService}) ; aucun des deux ne rappelle le dépannage,
     * sinon les deux modules se tiendraient par une dépendance circulaire.
     */
    private final SoldPlanService forfaits;
    private final SoldPlanAlertService rappels;

    /**
     * La demande qui a fait sortir la voiture. Un seul sens, toujours : la mission
     * tient l'identifiant de sa sortie, l'écran des sorties relit ce lien sans jamais
     * l'écrire. Deux écritures — une dans chaque table — donneraient deux liaisons
     * dont aucune n'est tenue de dire la même chose.
     */
    private final MissionRepository demandes;

    /**
     * Les pièces que l'homme a réclamées dehors. La clôture d'une sortie de pose les
     * marque « Posées » ici, par le dépôt : le service des pièces appelle ce module
     * pour lire une sortie, jamais l'inverse — deux services qui s'appellent l'un
     * l'autre forcent une injection paresseuse, et une écriture d'état cachée dans un
     * tiers ne se retrouve plus quand on cherche qui a écrit « Posée ».
     */
    private final InterventionPartRequestRepository pieces;

    /** La fiche atelier de la sortie : écrite par l'atelier, cochée par l'homme. */
    private final InterventionChecklistItemRepository checklist;

    /** Une sortie clôturée sans ces deux clichés doit une raison écrite. */
    private static final List<PhotoAngle> PROOF_ANGLES = List.of(PhotoAngle.BEFORE, PhotoAngle.AFTER);

    /** Une sortie partie depuis plus de X heures et toujours dehors est signalée (D4 en fera une alerte). */
    private final long longRunningHours;

    public InterventionService(InterventionRepository interventions,
                               InterventionEventRepository events,
                               TechnicianRepository technicians,
                               ServiceVehicleRepository serviceVehicles,
                               VehicleRepository vehicles,
                               ClientRepository clients,
                               SavCaseRepository savCases,
                               SavCaseService savCaseService,
                               TechnicianDirectory directory,
                               InterventionPhotoRepository photos,
                               AuditService audit,
                               VehicleMileageService compteur,
                               SoldPlanService forfaits,
                               SoldPlanAlertService rappels,
                               MissionRepository demandes,
                               InterventionPartRequestRepository pieces,
                               InterventionChecklistItemRepository checklist,
                               @Value("${app.depannage.long-running-hours:8}") long longRunningHours) {
        this.interventions = interventions;
        this.events = events;
        this.technicians = technicians;
        this.serviceVehicles = serviceVehicles;
        this.vehicles = vehicles;
        this.clients = clients;
        this.savCases = savCases;
        this.savCaseService = savCaseService;
        this.directory = directory;
        this.photos = photos;
        this.audit = audit;
        this.compteur = compteur;
        this.forfaits = forfaits;
        this.rappels = rappels;
        this.demandes = demandes;
        this.pieces = pieces;
        this.checklist = checklist;
        this.longRunningHours = longRunningHours;
    }

    // ----------------------------------------------------------------- lecture

    @Transactional(readOnly = true)
    public List<InterventionResponse> list(String status, Boolean openOnly, Authentication auth, Jwt jwt) {
        requireMember(auth);
        List<Intervention> rows;
        if (Boolean.TRUE.equals(openOnly)) {
            rows = interventions.findByStatusInOrderByReportedAtDesc(InterventionStatus.OPEN);
        } else if (status != null && !status.isBlank()) {
            rows = interventions.findByStatusInOrderByReportedAtDesc(
                    List.of(parseStatus(status)));
        } else {
            rows = interventions.findAll();
            rows.sort((a, b) -> b.getReportedAt().compareTo(a.getReportedAt()));
        }
        rows = restrictToOwn(rows, ownTechnicianId(auth, jwt));
        return mapAll(rows);
    }

    /** Écran « Mes interventions » du technicien : les siennes, ordonnées par heure d'appel. */
    @Transactional(readOnly = true)
    public List<InterventionResponse> myInterventions(Authentication auth, Jwt jwt) {
        Technician me = requireTechnician(jwt);
        List<Intervention> rows = interventions.findByTechnicianIdOrderByReportedAtDesc(me.getId());
        return mapAll(rows);
    }

    @Transactional(readOnly = true)
    public DayBoardResponse dayBoard(Authentication auth, Jwt jwt) {
        requireMember(auth);
        LocalDateTime from = LocalDateTime.now().toLocalDate().atStartOfDay();
        List<Intervention> rows = restrictToOwn(
                interventions.findByReportedAtGreaterThanEqualOrderByReportedAtDesc(from),
                ownTechnicianId(auth, jwt));
        List<InterventionResponse> mapped = mapAll(rows);
        long onSite = mapped.stream().filter(i -> isOneOf(i.status(), "on_site", "in_progress")).count();
        long enRoute = mapped.stream()
                .filter(i -> i.times() != null
                        && i.times().departedAt() != null && i.times().arrivedAt() == null)
                .count();
        long longRunning = mapped.stream().filter(InterventionResponse::runningLate).count();
        long engaged = serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(
                List.of(ServiceVehicleStatus.DISPATCHED)).size();
        return new DayBoardResponse(mapped, mapped.size(), enRoute, onSite, longRunning, engaged);
    }

    /**
     * Qui est encore envoyable, et qui est dehors. L'affectation ne peut pas
     * proposer un homme pour refuser le choix dans la foulée : le chef d'atelier
     * perd son clic et lit une notification muette. Le verrou — un homme, une
     * sortie à la fois — est le même que celui du serveur, donc la liste ne peut
     * pas promettre ce que l'état refuserait.
     */
    @Transactional(readOnly = true)
    public CrewResponse crew(Authentication auth) {
        requireMember(auth);
        Map<String, Intervention> dehors = new HashMap<>();
        for (Intervention i : interventions.findByStatusInOrderByReportedAtDesc(InterventionStatus.OPEN)) {
            if (i.getTechnicianId() != null) {
                dehors.putIfAbsent(i.getTechnicianId(), i);
            }
        }
        List<CrewMemberOption> libres = new ArrayList<>();
        List<CrewMemberOption> dehorsList = new ArrayList<>();
        for (TechnicianLookup t : directory.options()) {
            Intervention occupe = t.technicianId() == null ? null : dehors.get(t.technicianId());
            CrewMemberOption ligne = new CrewMemberOption(t.userId(), t.name(), t.technicianId(),
                    occupe == null ? null : occupe.getNumber(),
                    occupe == null ? null : label(occupe.getStatus()),
                    occupe == null ? null : occupe.getLocationCity());
            (occupe == null ? libres : dehorsList).add(ligne);
        }
        return new CrewResponse(libres, dehorsList);
    }

    @Transactional(readOnly = true)
    public InterventionDetailResponse detail(String id, Authentication auth, Jwt jwt) {
        Intervention i = requireVisible(id, auth, jwt);
        List<InterventionEventResponse> journal = events.findByInterventionIdOrderByRecordedAtAsc(id).stream()
                .map(InterventionEventResponse::from)
                .toList();
        return new InterventionDetailResponse(full(i), journal);
    }

    // ---------------------------------------------------------------- écriture

    /**
     * Le n° de la sortie encore en cours née d'un dossier, pour que l'écran des
     * dossiers affiche « sortie DP-… » après la déclaration. Demande d'écriture :
     * un compte de consultation ne lit pas ce numéro.
     */
    @Transactional(readOnly = true)
    public String openSortieNumber(String savCaseId) {
        return interventions.findBySavCaseIdOrderByReportedAtAsc(savCaseId).stream()
                .filter(i -> InterventionStatus.OPEN.contains(i.getStatus()))
                .map(Intervention::getNumber)
                .findFirst()
                .orElse(null);
    }

    /** Ouverture d'une sortie : l'appel est déclaré, le technicien est nommé tout de suite. */
    @Transactional
    public InterventionResponse create(InterventionRequest r, Jwt jwt, Authentication auth) {
        requireManager(auth);
        Vehicle bus = vehicles.findById(r.vehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le bus indiqué est introuvable : choisissez-le dans la liste des véhicules."));
        Technician tech = r.technicianUserId() == null ? null : directory.ficheFor(r.technicianUserId(), jwt);
        if (tech != null) {
            if (!tech.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le technicien « " + tech.getName() + " » est marqué inactif.");
            }
            if (interventions.existsByTechnicianIdAndStatusIn(tech.getId(), InterventionStatus.OPEN)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le technicien « " + tech.getName()
                                + " » a déjà une sortie en cours. Clôturez-la avant d'en ouvrir une autre.");
            }
        }
        if (interventions.existsByVehicleIdAndStatusIn(bus.getId(), InterventionStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce bus a déjà une sortie en cours (" + bus.getVin() + ").");
        }

        Intervention i = new Intervention();
        i.setId("itv-" + UUID.randomUUID());
        i.setNumber(nextNumber());
        i.setStatus(InterventionStatus.REPORTED);
        i.setPurpose(InterventionPurpose.PANNE);
        i.setVehicleId(bus.getId());
        i.setClientId(r.clientId() != null && !r.clientId().isBlank() ? r.clientId() : bus.getClientId());
        i.setTechnicianId(tech == null ? null : tech.getId());
        i.setCreatedBy(jwt != null ? jwt.getSubject() : null);
        i.setReportedAt(checkedReportedAt(r.reportedAt()));
        i.setRequestedBy(trim(r.requestedBy()));
        i.setLocationAddress(trim(r.locationAddress()));
        i.setLocationCity(trim(r.locationCity()));
        i.setDescription(r.description());
        i.setPriority(r.priority() != null ? r.priority() : SavPriority.MEDIUM);
        i.setBusMileage(checkedMileage(r.busMileage(), "le kilométrage du bus"));
        i.setCreatedAt(LocalDateTime.now());
        interventions.save(i);
        // Le compteur relu sur le bus à l'appel aligne la fiche (porte unique du compteur).
        compteur.releveTechnicien(bus.getId(), i.getBusMileage(), releveur(i, tech, jwt));

        event(i.getId(), InterventionEventType.REPORTED, InterventionEventSource.MANAGED, jwt,
                "Sortie " + i.getNumber() + (tech == null ? " ouverte, personne encore affectée"
                        : " ouverte pour " + tech.getName()));
        audit.record("CREATE", "INTERVENTION", i.getId(),
                "Ouverture de la sortie " + i.getNumber()
                        + (tech == null ? " (technicien non encore désigné)" : " (technicien " + tech.getName() + ")"),
                jwt);
        // À l'instant où elle naît, la sortie n'a pas encore de demande accrochée :
        // c'est la mission qui pose son identifiant dans la foulée, et l'écran relit
        // le numéro au rafraîchissement suivant.
        return map(i, tech == null ? null : tech.getName(), FicheLabel.bus(bus),
                clientName(i.getClientId()), null, null, null, List.of());
    }

    /**
     * La sortie naît du dossier SAV. Le bus, le client, la ville et la panne sont
     * déjà écrits dans le dossier : les redemander à l'atelier produirait deux
     * versions de la même histoire, et c'est précisément ce que le module doit
     * empêcher. L'homme affecté au dossier part avec la sortie s'il est libre ;
     * sinon la sortie attend l'affectation, elle ne vole pas un homme dehors.
     */
    @Transactional
    public InterventionResponse createFromSavCase(String savCaseId, Jwt jwt, Authentication auth) {
        requireDesk(auth);
        SavCase c = savCases.findById(savCaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Le dossier SAV est introuvable."));
        if (c.getVehicleId() == null || c.getVehicleId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + c.getCaseNumber() + " ne dit pas de quel bus il s'agit : "
                            + "une sortie sans bus ne peut pas être déclarée.");
        }
        // Déclarer deux fois la même panne ne doit pas créer deux sorties : la seconde
        // demande renvoie celle qui court déjà, l'écran affiche son numéro.
        Intervention already = interventions.findBySavCaseIdOrderByReportedAtAsc(c.getId()).stream()
                .filter(i -> InterventionStatus.OPEN.contains(i.getStatus()))
                .findFirst()
                .orElse(null);
        if (already != null) {
            return full(already);
        }
        Vehicle bus = vehicles.findById(c.getVehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le bus du dossier " + c.getCaseNumber() + " n'existe plus dans le portail."));
        String clientId = c.getClientId() != null && !c.getClientId().isBlank()
                ? c.getClientId() : bus.getClientId();
        Client client = clientId == null ? null : clients.findById(clientId).orElse(null);
        Technician tech = freeFicheOf(c.getTechnicianUserId(), jwt);

        Intervention i = new Intervention();
        i.setId("itv-" + UUID.randomUUID());
        i.setNumber(nextNumber());
        i.setStatus(InterventionStatus.REPORTED);
        i.setPurpose(InterventionPurpose.PANNE);
        i.setVehicleId(bus.getId());
        i.setClientId(clientId);
        i.setTechnicianId(tech == null ? null : tech.getId());
        i.setSavCaseId(c.getId());
        i.setCreatedBy(jwt != null ? jwt.getSubject() : null);
        i.setReportedAt(LocalDateTime.now());
        i.setRequestedBy("dossier " + c.getCaseNumber());
        i.setLocationCity(client == null ? null : client.getCity());
        i.setLocationAddress(client == null ? null : client.getAddress());
        i.setDescription(caseStory(c));
        i.setPriority(c.getPriority() != null ? c.getPriority() : SavPriority.MEDIUM);
        i.setCreatedAt(LocalDateTime.now());
        interventions.save(i);

        event(i.getId(), InterventionEventType.REPORTED, InterventionEventSource.MANAGED, jwt,
                "Sortie " + i.getNumber() + " née du dossier " + c.getCaseNumber()
                        + (tech == null ? " — personne encore affectée" : ", " + tech.getName() + " repris du dossier"));
        audit.record("CREATE", "INTERVENTION", i.getId(),
                "Ouverture de la sortie " + i.getNumber() + " depuis le dossier " + c.getCaseNumber(), jwt);
        return full(i);
    }

    /**
     * Le rappel d'un palier envoie l'équipe. Le geste est celui d'une panne — une
     * voiture de déplacement et un homme affectés ensemble, des heures posées par le
     * serveur, une prime à la clé — seule la raison change : ici c'est le compteur du
     * bus qui a parlé, pas un appel. Le motif écrit sur la sortie le dit à l'écran et
     * au panneau des primes, qui paie les deux motifs sur la même règle.
     *
     * Deux clics ne font pas deux sorties : quand le rappel porte déjà une sortie en
     * cours, cette demande renvoie sa fiche au lieu d'envoyer un second homme pour la
     * même visite. Le rappel, lui, reste ouvert jusqu'au palier réellement fait — une
     * équipe en route n'est pas une visite accomplie.
     *
     * Le bus et le palier ne se choisissent pas ici : ils viennent du rappel et du
     * forfait. Les demander à l'écran produirait deux versions de la même histoire,
     * exactement ce que le module doit empêcher.
     */
    @Transactional
    public InterventionResponse envoyerPourRappel(String alertId, EnvoyerRequest r, Jwt jwt,
                                                  Authentication auth) {
        requireManager(auth);
        SoldPlanAlertService.EnvoiAutorise envoi = rappels.rappelAEnvoyer(alertId);
        Intervention dejaPartie = envoi.interventionIdDejaEnvoyee() == null ? null
                : interventions.findById(envoi.interventionIdDejaEnvoyee()).orElse(null);
        if (dejaPartie != null && InterventionStatus.OPEN.contains(dejaPartie.getStatus())) {
            return full(dejaPartie);
        }

        Vehicle bus = vehicles.findById(envoi.vehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le bus de ce forfait n'existe plus dans le portail : la sortie ne peut pas "
                                + "être déclarée sur un bus supprimé."));
        Intervention dehors = interventions
                .findByVehicleIdAndStatusInOrderByReportedAtDesc(bus.getId(), InterventionStatus.OPEN)
                .stream().findFirst().orElse(null);
        if (dehors != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce bus a déjà la sortie " + dehors.getNumber() + " en cours ("
                            + label(dehors.getStatus()) + "). Clôturez-la, ou faites la visite avec l'équipe "
                            + "déjà dehors : deux sorties pour un même bus paieraient deux fois le trajet.");
        }

        Intervention i = new Intervention();
        i.setId("itv-" + UUID.randomUUID());
        i.setNumber(nextNumber());
        i.setStatus(InterventionStatus.REPORTED);
        i.setPurpose(InterventionPurpose.ENTRETIEN);
        i.setVehicleId(bus.getId());
        i.setClientId(envoi.clientId());
        i.setSoldPlanId(envoi.soldPlanId());
        i.setPlanStepId(envoi.planStepId());
        i.setCreatedBy(jwt != null ? jwt.getSubject() : null);
        i.setReportedAt(LocalDateTime.now());
        i.setRequestedBy(truncate("rappel du palier « " + envoi.palierLabel() + " »", 120));
        Client client = envoi.clientId() == null ? null : clients.findById(envoi.clientId()).orElse(null);
        i.setLocationCity(client == null ? null : client.getCity());
        i.setLocationAddress(client == null ? null : client.getAddress());
        i.setDescription(envoi.messageRappel());
        i.setPriority(SavPriority.MEDIUM);
        i.setCreatedAt(LocalDateTime.now());
        interventions.save(i);

        event(i.getId(), InterventionEventType.REPORTED, InterventionEventSource.MANAGED, jwt,
                "Sortie " + i.getNumber() + " envoyée par le rappel du palier « " + envoi.palierLabel()
                        + " » du forfait « " + envoi.planName() + " »");
        audit.record("CREATE", "INTERVENTION", i.getId(),
                "Ouverture de la sortie " + i.getNumber() + " pour le palier « " + envoi.palierLabel()
                        + " » du forfait « " + envoi.planName() + " »", jwt);
        rappels.priseEnCharge(alertId, i.getId(), i.getNumber(), jwt);

        // L'affectation suit dans la même écriture, avec les refus déjà écrits pour
        // l'écran Dépannage : un refus annule tout et ne laisse pas une sortie née
        // sans homme ni voiture.
        return dispatch(i.getId(),
                new DispatchRequest(r.serviceVehicleId(), null, r.technicianUserId(), List.of()), jwt, auth);
    }

    /** L'homme du dossier, seulement s'il peut partir : sinon la sortie attendra l'affectation. */
    private Technician freeFicheOf(UUID userId, Jwt jwt) {
        if (userId == null) {
            return null;
        }
        try {
            Technician tech = directory.ficheFor(userId, jwt);
            return tech != null && tech.isActive()
                    && !interventions.existsByTechnicianIdAndStatusIn(tech.getId(), InterventionStatus.OPEN)
                    ? tech : null;
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    /** Le dossier raconte la panne en une phrase : le titre d'abord, le détail ensuite. */
    private String caseStory(SavCase c) {
        String detail = c.getDescription() == null ? null : c.getDescription().trim();
        return detail == null || detail.isBlank() ? c.getTitle() : c.getTitle() + " — " + detail;
    }

    /** Champs descriptifs uniquement : les heures et les statuts ne passent pas par là. */
    @Transactional
    public InterventionResponse update(String id, InterventionRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        if (i.getStatus() == InterventionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sortie " + i.getNumber() + " est clôturée : elle ne se modifie plus.");
        }
        if (r.technicianUserId() == null && i.getDepartedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sortie " + i.getNumber() + " est déjà partie : l'homme qui est sur place "
                            + "ne se retire pas, il se rectifie au journal.");
        }
        Technician tech = r.technicianUserId() == null ? null : directory.ficheFor(r.technicianUserId(), jwt);
        if (tech != null && !tech.getId().equals(i.getTechnicianId())
                && interventions.existsByTechnicianIdAndStatusIn(tech.getId(), InterventionStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le technicien « " + tech.getName() + " » a déjà une sortie en cours.");
        }
        if (tech != null) {
            i.setTechnicianId(tech.getId());
        }
        if (r.vehicleId() != null && !r.vehicleId().isBlank() && !r.vehicleId().equals(i.getVehicleId())) {
            Vehicle bus = vehicles.findById(r.vehicleId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le bus indiqué est introuvable."));
            i.setVehicleId(bus.getId());
        }
        LocalDateTime reported = checkedReportedAt(r.reportedAt());
        if (!reported.equals(i.getReportedAt())) {
            if (i.getDispatchedAt() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "L'heure de l'appel n'est plus modifiable une fois la sortie affectée :"
                                + " passez par une rectification pour garder la trace.");
            }
            i.setReportedAt(reported);
        }
        i.setRequestedBy(trim(r.requestedBy()));
        i.setLocationAddress(trim(r.locationAddress()));
        i.setLocationCity(trim(r.locationCity()));
        i.setDescription(r.description());
        if (r.priority() != null) {
            i.setPriority(r.priority());
        }
        if (r.busMileage() != null) {
            i.setBusMileage(checkedMileage(r.busMileage(), "le kilométrage du bus"));
        }
        if (r.clientId() != null && !r.clientId().isBlank()) {
            i.setClientId(r.clientId());
        }
        i.setUpdatedAt(LocalDateTime.now());
        interventions.save(i);
        // Une rectification du compteur relevé aligne elle aussi la fiche du bus,
        // à la hausse seulement : la porte refuse qu'une fiche redescende ici.
        compteur.releveTechnicien(i.getVehicleId(), i.getBusMileage(), releveur(i, null, jwt));
        audit.record("UPDATE", "INTERVENTION", id,
                "Mise à jour de la sortie " + i.getNumber(), jwt);
        return full(i);
    }

    /**
     * L'affectation, en un geste : la voiture cesse d'être disponible et l'homme qui
     * part est nommé. Les deux se décident ensemble parce que c'est à ce moment que
     * l'atelier sait qui est libre — la sortie, elle, peut être née d'un dossier SAV
     * ouvert par quelqu'un qui ne connaît pas le planning du jour.
     */
    @Transactional
    public InterventionResponse dispatch(String id, DispatchRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        requireStatus(i, InterventionStatus.REPORTED,
                "l'affectation d'une voiture de déplacement");
        ServiceVehicle v = availableVehicle(r.serviceVehicleId());
        if (interventions.existsByServiceVehicleIdAndStatusIn(v.getId(), InterventionStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le véhicule « " + v.getRegistration() + " » est déjà engagé sur une sortie.");
        }
        Technician tech = toAssign(i, r.technicianUserId(), jwt);
        LocalDateTime now = LocalDateTime.now();
        i.setServiceVehicleId(v.getId());
        i.setTechnicianId(tech.getId());
        i.setDispatchedAt(now);
        i.setStatus(InterventionStatus.DISPATCHED);
        i.setUpdatedAt(now);
        interventions.save(i);
        v.setStatus(ServiceVehicleStatus.DISPATCHED);
        serviceVehicles.save(v);
        ecrireFiche(i.getId(), r.checklist());
        event(i.getId(), InterventionEventType.DISPATCHED, InterventionEventSource.SERVER, jwt,
                "Véhicule " + v.getRegistration() + " affecté à " + tech.getName());
        // Le dossier de la sortie passe « En cours » : l'homme est parti, le travail est ouvert.
        savCaseService.suivreSortie(i, jwt);
        audit.record("DISPATCH", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : véhicule " + v.getRegistration()
                        + " et technicien " + tech.getName() + " affectés", jwt);
        return full(i);
    }

    /**
     * La fiche atelier, écrite au départ par l'atelier : une ligne par chose à
     * faire sur place. Les lignes vides sont ignorées ; l'ordre de saisie est
     * l'ordre de lecture sous le bus.
     */
    private void ecrireFiche(String interventionId, List<String> lignes) {
        if (lignes == null || lignes.isEmpty()) {
            return;
        }
        int ordre = 1;
        for (String ligne : lignes) {
            String label = trim(ligne);
            if (label == null) {
                continue;
            }
            InterventionChecklistItem item = new InterventionChecklistItem();
            item.setId("ick-" + UUID.randomUUID());
            item.setInterventionId(interventionId);
            item.setPosition(ordre++);
            item.setLabel(label);
            item.setDone(false);
            checklist.save(item);
        }
    }

    /**
     * L'homme qui part. Soit l'écran le nomme, soit la sortie en a déjà un (hérité du
     * dossier SAV). Un compte dehors sur une autre sortie est refusé : c'est le verrou
     * qui empêche un homme de cumuler deux primes le même jour.
     */
    private Technician toAssign(Intervention i, java.util.UUID userId, Jwt jwt) {
        if (userId == null) {
            Technician kept = i.getTechnicianId() == null ? null
                    : technicians.findById(i.getTechnicianId()).orElse(null);
            if (kept == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choisis aussi l'homme qui part : une sortie sans nom ne se contrôle pas "
                                + "et ne se paie pas.");
            }
            return kept;
        }
        Technician tech = directory.ficheFor(userId, jwt);
        if (!tech.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le technicien « " + tech.getName() + " » est marqué inactif.");
        }
        boolean dehors = interventions.findByTechnicianIdAndStatusInOrderByReportedAtDesc(
                        tech.getId(), InterventionStatus.OPEN).stream()
                .anyMatch(other -> !other.getId().equals(i.getId()));
        if (dehors) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le technicien « " + tech.getName() + " » est déjà dehors sur une autre sortie. "
                            + "Clôturez-la, ou envoyez quelqu'un d'autre.");
        }
        return tech;
    }

    /**
     * « Je pars » : les deux photos de la voiture — la voiture elle-même et son
     * compteur — sont versées avant l'enregistrement. Le chiffre reste facultatif :
     * la photo prouve le départ, et le chiffre ne sert qu'à calculer la distance.
     */
    @Transactional
    public InterventionResponse depart(String id, DepartRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        requireStatus(i, InterventionStatus.DISPATCHED, "le départ");
        if (i.getServiceVehicleId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucune voiture de déplacement n'est affectée à cette sortie : "
                            + "il n'y a rien à photographier.");
        }
        requireFreshTimestamp(i.getDepartedAt(), "Le départ est déjà enregistré à "
                + at(i.getDepartedAt()) + ".");
        ServiceVehicle v = serviceVehicles.findById(i.getServiceVehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La voiture de déplacement de cette sortie est introuvable."));
        requireVoiturePhotographiee(i, PhotoAngle.DEPART, PhotoAngle.COMPTEUR_DEPART, "départ");
        Long start = r.startMileage() == null ? null
                : checkedMileage(r.startMileage(), "le kilométrage de départ");
        if (start != null && v.getCurrentMileage() != null && start < v.getCurrentMileage()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compteur indiqué (" + start + " km) est inférieur au dernier relevé du véhicule ("
                            + v.getCurrentMileage() + " km) : relisez-le sur le tableau de bord.");
        }
        LocalDateTime now = LocalDateTime.now();
        i.setDepartedAt(now);
        i.setStartMileage(start);
        i.setUpdatedAt(now);
        interventions.save(i);
        event(i.getId(), InterventionEventType.DEPARTED, InterventionEventSource.SERVER, jwt,
                "Départ à " + at(now) + (start == null
                        ? ", compteur photographié (chiffre non relevé)"
                        : ", compteur " + start + " km"));
        audit.record("DEPART", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : départ enregistré avec la photo du compteur", jwt);
        return full(i);
    }

    /**
     * Le départ et le retour se prouvent par la voiture et son compteur, pas par
     * un chiffre tapé.
     */
    private void requireVoiturePhotographiee(Intervention i, PhotoAngle vehicule,
                                             PhotoAngle compteur, String moment) {
        String manquantes = null;
        if (!photos.existsByInterventionIdAndAngle(i.getId(), vehicule)) {
            manquantes = vehicule == PhotoAngle.DEPART ? "la voiture" : "la voiture au retour";
        }
        if (!photos.existsByInterventionIdAndAngle(i.getId(), compteur)) {
            manquantes = manquantes == null ? "le compteur" : manquantes + " et le compteur";
        }
        if (manquantes != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le " + moment + " de la sortie " + i.getNumber() + " se prouve par deux photos : "
                            + "il manque " + manquantes + ". Photographiez la voiture et son compteur, "
                            + "puis enregistrez le " + moment + ".");
        }
    }

    /** « J'arrive » : l'heure de présence sur place ne peut pas être tapée, elle est prise ici. */
    @Transactional
    public InterventionResponse arrive(String id, ArrivalRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        requireStatus(i, InterventionStatus.DISPATCHED, "l'arrivée sur place");
        if (i.getDepartedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il faut enregistrer le départ avant l'arrivée : le temps de route se calcule entre les deux.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(i.getDepartedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'arrivée tombe avant le départ enregistré (" + at(i.getDepartedAt())
                            + "). Vérifiez l'heure de la machine qui sert le serveur.");
        }
        i.setStatus(InterventionStatus.ON_SITE);
        i.setArrivedAt(now);
        if (r != null && r.busMileage() != null) {
            i.setBusMileage(checkedMileage(r.busMileage(), "le kilométrage du bus"));
        }
        i.setUpdatedAt(now);
        interventions.save(i);
        // « J'arrive » : le compteur relu sur place devient le compteur de la fiche.
        compteur.releveTechnicien(i.getVehicleId(), i.getBusMileage(), releveur(i, null, jwt));
        event(i.getId(), InterventionEventType.ARRIVED, InterventionEventSource.SERVER, jwt,
                "Arrivée à " + at(now) + (r != null && r.note() != null ? " — " + r.note() : ""));
        // « J'arrive » : le dossier dit désormais que l'homme est sur place.
        savCaseService.suivreSortie(i, jwt);
        audit.record("ARRIVE", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : arrivée sur place enregistrée", jwt);
        return full(i);
    }

    /** Le diagnostic s'enregistre pendant l'intervention. */
    @Transactional
    public InterventionResponse progress(String id, ProgressRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        requireStatus(i, InterventionStatus.ON_SITE, "le début des réparations");
        LocalDateTime now = LocalDateTime.now();
        i.setStatus(InterventionStatus.IN_PROGRESS);
        i.setDefectCode(trim(r.defectCode()));
        i.setDiagnosis(r.diagnosis());
        i.setCausingPart(trim(r.causingPart()));
        i.setUpdatedAt(now);
        interventions.save(i);
        event(i.getId(), InterventionEventType.DIAGNOSED, InterventionEventSource.SERVER, jwt,
                "Diagnostic enregistré" + (r.defectCode() != null ? " (code " + r.defectCode() + ")" : ""));
        audit.record("UPDATE", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : diagnostic enregistré", jwt);
        return full(i);
    }

    /**
     * Issue de l'intervention : « repaired » ou « towed ». Les deux sont distincts
     * parce qu'un bus remorqué n'est pas un bus réparé, et le compte serait faux.
     */
    @Transactional
    public InterventionResponse finish(String id, FinishRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        requireStatus(i, InterventionStatus.IN_PROGRESS, "la fin de l'intervention");
        // Le compteur se relit avant toute écriture : un refus ne doit pas avancer la sortie.
        Long busEnd = checkedBusEnd(i, r.busMileageEnd());
        InterventionStatus result = switch (r.result().trim().toLowerCase()) {
            case "repaired" -> InterventionStatus.REPAIRED;
            case "towed" -> InterventionStatus.TOWED;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Résultat inconnu : indiquez « repaired » (réparé sur place) ou « towed » (remorqué).");
        };
        LocalDateTime now = LocalDateTime.now();
        i.setStatus(result);
        i.setFinishedAt(now);
        i.setResultNote(trim(r.resultNote()));
        i.setBusMileageEnd(busEnd);
        i.setUpdatedAt(now);
        interventions.save(i);
        // Le compteur de fin est le plus haut jamais lu sur ce bus à cette date :
        // c'est lui qui tient la fiche, et donc les paliers qui en dépendent.
        compteur.releveTechnicien(i.getVehicleId(), i.getBusMileageEnd(), releveur(i, null, jwt));
        event(i.getId(), result == InterventionStatus.REPAIRED
                        ? InterventionEventType.REPAIRED : InterventionEventType.TOWED,
                InterventionEventSource.SERVER, jwt,
                "Intervention terminée à " + at(now) + " — "
                        + (result == InterventionStatus.REPAIRED ? "réparé sur place" : "remorqué"));
        audit.record("FINISH", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : " + result.name().toLowerCase(), jwt);
        return full(i);
    }

    /** La voiture quitte les lieux ; le retour à l'atelier viendra après. */
    @Transactional
    public InterventionResponse leaveSite(String id, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        if (i.getStatus() != InterventionStatus.REPAIRED && i.getStatus() != InterventionStatus.TOWED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "On ne quitte les lieux qu'après avoir dit si le bus est réparé ou remorqué.");
        }
        requireFreshTimestamp(i.getLeftSiteAt(), "Le départ des lieux est déjà enregistré à "
                + at(i.getLeftSiteAt()) + ".");
        LocalDateTime now = LocalDateTime.now();
        i.setLeftSiteAt(now);
        i.setUpdatedAt(now);
        interventions.save(i);
        event(i.getId(), InterventionEventType.LEFT_SITE, InterventionEventSource.SERVER, jwt,
                "Quitté les lieux à " + at(now));
        audit.record("LEAVE_SITE", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : départ des lieux", jwt);
        return full(i);
    }

    /** Retour à l'atelier : le kilométrage d'arrivée ferme la boucle et rend la voiture. */
    @Transactional
    public InterventionResponse returnToWorkshop(String id, ReturnRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        if (i.getStatus() != InterventionStatus.REPAIRED && i.getStatus() != InterventionStatus.TOWED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le retour ne s'enregistre qu'une fois l'intervention terminée.");
        }
        if (i.getLeftSiteAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il faut d'abord enregistrer le départ des lieux.");
        }
        requireVoiturePhotographiee(i, PhotoAngle.RETOUR, PhotoAngle.COMPTEUR_RETOUR, "retour");
        Long end = r.endMileage() == null ? null
                : checkedMileage(r.endMileage(), "le kilométrage de retour");
        if (end != null && i.getStartMileage() != null && end < i.getStartMileage()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compteur de retour (" + end + " km) est inférieur à celui du départ ("
                            + i.getStartMileage() + " km) : la distance ne peut pas être négative.");
        }
        requireConstatEcrit(i);
        boolean ficheIncomplete = !checklist.findByInterventionIdOrderByPositionAsc(i.getId()).stream()
                .filter(item -> !item.isDone()).toList().isEmpty();
        LocalDateTime now = LocalDateTime.now();
        i.setStatus(InterventionStatus.RETURNED);
        i.setReturnedAt(now);
        i.setEndMileage(end);
        i.setReceivedBy(trim(r.receivedBy()));
        i.setUpdatedAt(now);
        interventions.save(i);
        if (i.getServiceVehicleId() != null) {
            serviceVehicles.findById(i.getServiceVehicleId()).ifPresent(v -> {
                v.setStatus(ServiceVehicleStatus.AVAILABLE);
                if (end != null) {
                    v.setCurrentMileage(end);
                }
                serviceVehicles.save(v);
            });
        }
        event(i.getId(), InterventionEventType.RETURNED, InterventionEventSource.SERVER, jwt,
                "Retour à " + at(now) + (end == null
                        ? ", compteur photographié (chiffre non relevé)"
                        : ", compteur " + end + " km")
                        + (r.note() != null ? " — " + r.note() : "")
                        // Une fiche oubliée ne bloque pas le retour : elle reste rouge
                        // dans la sortie, et l'atelier la voit.
                        + (ficheIncomplete ? " — fiche atelier incomplète" : ""));
        // Le technicien est revenu : le dossier le dit, avant même la clôture du chef.
        savCaseService.suivreSortie(i, jwt);
        audit.record("RETURN", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : retour à l'atelier", jwt);
        return full(i);
    }

    /**
     * La rentrée constatée : la voiture est revenue, l'homme n'a pointé aucune heure
     * (téléphone perdu, application jamais ouverte, sortie d'essai restée ouverte).
     *
     * Le geste n'invente rien : l'arrivée, le diagnostic, la fin et le départ des
     * lieux restent vides, et la fiche les montrera absents. Il écrit l'heure du
     * constat — celle du clic de l'atelier —, rend la voiture et garde la raison au
     * journal. Sans cette porte, une sortie que personne ne pointe tiendrait son
     * homme et sa voiture pour toujours.
     */
    @Transactional
    public InterventionResponse declareReturn(String id, DeclareReturnRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        if (i.getStatus() == InterventionStatus.RETURNED || i.getStatus() == InterventionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sortie " + i.getNumber() + " est déjà rentrée : il n'y a plus rien à constater.");
        }
        if (i.getDepartedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sortie " + i.getNumber() + " n'est jamais partie : il n'y a pas de rentrée à "
                            + "constater. Une sortie sans départ se supprime.");
        }
        String reason = trim(r.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Écrivez pourquoi la sortie n'a pas été pointée (téléphone perdu, application "
                            + "non ouverte, sortie d'essai) : la raison reste au journal.");
        }
        LocalDateTime now = LocalDateTime.now();
        i.setStatus(InterventionStatus.RETURNED);
        i.setReturnedAt(now);
        i.setUpdatedAt(now);
        interventions.save(i);
        if (i.getServiceVehicleId() != null) {
            // Seule une voiture engagée par cette sortie redevient libre : à l'atelier
            // ou en panne, elle reste indisponible pour sa propre raison.
            serviceVehicles.findById(i.getServiceVehicleId()).ifPresent(v -> {
                if (v.getStatus() == ServiceVehicleStatus.DISPATCHED) {
                    v.setStatus(ServiceVehicleStatus.AVAILABLE);
                    serviceVehicles.save(v);
                }
            });
        }
        event(i.getId(), InterventionEventType.RETURNED, InterventionEventSource.MANAGED, jwt,
                "Rentrée constatée à l'atelier à " + at(now) + ", sans pointage — " + reason);
        audit.record("RETURN", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " : rentrée constatée sans pointage", jwt);
        return full(i);
    }

    /**
     * Le retour ne se compte pas sur un constat vide : le code défaut, la pièce
     * en cause et ce que l'homme a constaté s'écrivent devant le bus. Une sortie
     * rentrée sans constat ne dit rien à l'atelier le lendemain.
     */
    private void requireConstatEcrit(Intervention i) {
        List<String> manques = new ArrayList<>();
        if (trim(i.getDefectCode()) == null) {
            manques.add("le code défaut");
        }
        if (trim(i.getCausingPart()) == null) {
            manques.add("la pièce en cause");
        }
        if (trim(i.getDiagnosis()) == null) {
            manques.add("ce que vous avez constaté");
        }
        if (!manques.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le retour de la sortie " + i.getNumber() + " attend le constat écrit devant le bus : "
                            + String.join(", ", manques) + ". Notez le constat, puis comptez le retour.");
        }
    }

    /**
     * Cocher une ligne de la fiche atelier. Le retour n'est jamais bloqué par une
     * ligne oubliée : elle reste rouge dans la sortie, et l'atelier la voit.
     */
    @Transactional
    public InterventionResponse cocher(String id, String itemId, CocherRequest r,
                                       Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireTechnicianOrManager(i, jwt, auth);
        InterventionChecklistItem item = checklist.findByInterventionIdOrderByPositionAsc(id).stream()
                .filter(ligne -> ligne.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cette ligne n'appartient pas à la fiche de la sortie " + i.getNumber() + "."));
        item.setDone(r.done());
        item.setDoneAt(r.done() ? LocalDateTime.now() : null);
        item.setDoneBy(r.done() ? releveur(i, null, jwt) : null);
        checklist.save(item);
        audit.record("UPDATE", "INTERVENTION_CHECKLIST", item.getId(),
                "Fiche de la sortie " + i.getNumber() + " : « " + item.getLabel() + " » "
                        + (item.isDone() ? "cochée" : "décochée"), jwt);
        return full(i);
    }

    /**
     * Clôture : le chef d'atelier valide, et rien de l'heure d'étape ne bouge plus.
     *
     * C'est aussi le moment du rattachement au dossier SAV, parce que la sortie ne
     * sait ce qu'elle est devenue (garantie, réclamation, hors garantie) qu'une fois
     * le diagnostic et le résultat écrits. Le serveur refuse un identifiant de
     * dossier qui n'existe pas : un lien cassé ne se voit que le jour où on le cherche.
     */
    @Transactional
    public InterventionResponse close(String id, ClosureRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        requireStatus(i, InterventionStatus.RETURNED, "la clôture");

        // La preuve d'abord : clôturer est le dernier moment où l'on peut encore
        // réclamer les clichés. Le module ne bloque pas un atelier sans téléphone —
        // il exige une raison écrite, qui restera au journal et qui sera relue à la prime.
        List<String> missing = missingProof(i.getId());
        if (!missing.isEmpty()) {
            String reason = trim(r.missingProofReason());
            if (reason == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Il manque les photos " + String.join(" et ", missing)
                                + ". Verse-les depuis le poste, ou écris pourquoi elles ne sont pas là"
                                + " (équipe sans téléphone, appareil hors service) : la raison reste au journal"
                                + " et la prime ne sera pas validée sans elles.");
            }
            event(i.getId(), InterventionEventType.PHOTO, InterventionEventSource.MANAGED, jwt,
                    "Clôture sans preuves photo (" + String.join(", ", missing) + ") : " + reason);
        }

        LocalDateTime now = LocalDateTime.now();
        i.setStatus(InterventionStatus.CLOSED);
        i.setClosedAt(now);
        i.setClosureNote(trim(r.closureNote()));

        String existing = trim(r.savCaseId());
        boolean wantsNew = Boolean.TRUE.equals(r.openSavCase());
        if (existing != null && wantsNew) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il faut choisir : rattacher un dossier existant OU en créer un nouveau, pas les deux.");
        }
        if (existing != null) {
            SavCase linked = savCases.findById(existing)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le dossier SAV indiqué est introuvable : prenez-le dans la liste des dossiers."));
            i.setSavCaseId(linked.getId());
            // Le dossier prend l'homme de la sortie, ou refuse si lui en suit un autre :
            // deux noms sur le même travail, c'est un faux des deux côtés.
            savCaseService.adoptTechnicianFrom(linked, i, ficheOf(i));
            event(i.getId(), InterventionEventType.CASE, InterventionEventSource.MANAGED, jwt,
                    "Rattachée au dossier " + linked.getCaseNumber());
        } else if (wantsNew) {
            SavCase created = savCases.findById(
                    savCaseService.createFromIntervention(i, ficheOf(i),
                            r.savCaseType(), jwt).getId()).orElseThrow();
            i.setSavCaseId(created.getId());
            event(i.getId(), InterventionEventType.CASE, InterventionEventSource.MANAGED, jwt,
                    "Dossier SAV " + created.getCaseNumber() + " créé depuis cette sortie");
        }

        i.setUpdatedAt(now);
        interventions.save(i);
        // Les pièces vues devant le bus deviennent la marchandise du dossier au même
        // moment que la sortie : une ligne restée sans dossier au dos ne figure dans
        // aucun papier AG, et le constructeur ne rembourse que des pièces nommées.
        reclamerLesPiecesDeLaSortie(i, jwt);
        // Le dossier suit la clôture — sauf s'il vient de naître de ce geste : un dossier
        // qui se crée à la sortie reste « Ouvert », c'est l'atelier qui le ferme après coup.
        // Le dossier déjà au dos de la sortie compte au même titre que celui qu'on vient
        // de re-choisir : à l'écran, l'homme qui clôture sa sortie ne re-saisit pas un
        // numéro qu'il porte déjà, et son dossier serait resté « Rentré » pour toujours.
        if (!wantsNew && i.getSavCaseId() != null) {
            savCaseService.suivreSortie(i, jwt);
        }
        event(i.getId(), InterventionEventType.CLOSED, InterventionEventSource.SERVER, jwt,
                "Clôturée à " + at(now));
        // Une sortie d'entretien doit cocher ce qu'elle est allée faire : l'ordre de
        // réparation naît dans le même geste que la clôture, jamais après coup. Une
        // panne ne crée aucun papier — elle se règle, elle, avec le dossier SAV.
        if (i.getPurpose() == InterventionPurpose.ENTRETIEN
                && i.getSoldPlanId() != null && i.getPlanStepId() != null) {
            SoldPlanService.PapierDePalier papier = forfaits.papierPourPalier(
                    new SoldPlanService.SortiePourPalier(i.getSoldPlanId(), i.getPlanStepId(),
                            i.getVehicleId(), i.getClientId(), i.getNumber(),
                            nameOf(i.getTechnicianId()), i.getBusMileageEnd()), jwt);
            i.setMaintenanceItemId(papier.documentId());
            interventions.save(i);
            event(i.getId(), InterventionEventType.PALIER, InterventionEventSource.SERVER, jwt, papier.note());
        }
        // Le second passage d'une pièce rentrée : la clôture de la sortie de pose écrit
        // « Posée » sur la ligne, pendant que le chef signe la sortie, pas après coup.
        marquerLesPiecesPosees(i, now, jwt);
        audit.record("CLOSE", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " clôturée", jwt);
        return full(i);
    }

    /**
     * La sortie déclarée en qualité — le clic d'un humain sur sa ligne, jamais un geste
     * du serveur.
     *
     * Ce que la sortie sait déjà ne se retape pas : le code défaut relevé devant le bus
     * entre dans le titre du dossier, le diagnostic et la pièce en cause dans sa
     * description, l'homme reste celui de la sortie. Ce qu'elle ne sait pas — la
     * classification, la criticité, les actions correctives et préventives — vient du
     * corps du clic et reste vide si l'atelier ne l'écrit pas : le portail ne choisit
     * pas une gravité à la place de quelqu'un.
     *
     * La sortie garde le dossier qui est au dos d'elle ({@code sav_case_id}) : un
     * dossier qualité n'est pas le dossier du dépannage, c'est une déclaration sur ce
     * qui a été vu devant le bus. Le lien s'écrit sur le dossier
     * ({@code declared_from_intervention_id}, V34). Aucun statut ne bouge — déclarer
     * n'est pas clôturer, et une anomalie se signale pendant qu'on la voit.
     */
    @Transactional
    public DeclarationQualiteResponse declarerEnQualite(String id, DeclarationQualiteRequest declaration,
                                                        Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        SavCase dossier = savCaseService.declarerEnQualite(i, ficheOf(i), declaration, jwt);
        event(i.getId(), InterventionEventType.CASE, InterventionEventSource.MANAGED, jwt,
                "Déclarée en qualité : dossier " + dossier.getCaseNumber());
        audit.record("CREATE", "SAV_CASE", dossier.getId(),
                "Déclaration en qualité du problème vu en sortie " + i.getNumber()
                        + (i.getDefectCode() == null || i.getDefectCode().isBlank()
                                ? "" : " (code défaut " + i.getDefectCode().trim() + ")"), jwt);
        return new DeclarationQualiteResponse(dossier.getId(), dossier.getCaseNumber(), dossier.getTitle(),
                dossier.getDeclarationDate() == null ? null : dossier.getDeclarationDate().toString(),
                i.getNumber());
    }

    /**
     * La pièce que cette mission était allée monter est posée. Le mot ne se saisit
     * nulle part : il se déduit du lien écrit à la livraison
     * ({@code intervention_part_requests.fitting_mission_id}, V32) et de la clôture de
     * la sortie que cette mission a fait sortir. Une sortie qui n'est pas née d'une
     * mission de pose ne marque rien — et le portail ne devine pas, au risque d'écrire
     * « Posée » sur une pièce qui dort encore au magasin.
     *
     * Deux gardes, et pas une de plus :
     *  - une ligne qui n'est pas « Livrée » ne se pose pas (le reste d'une commande
     *    partielle ne peut pas être monté) ;
     *  - une ligne déjà marquée garde sa date et son nom : rejouer une clôture ne
     *    réécrit pas l'histoire, et deux écritures d'un même fait est le défaut que
     *    l'atelier reproche au portail.
     */
    private void marquerLesPiecesPosees(Intervention i, LocalDateTime now, Jwt jwt) {
        Mission pose = demandes.findByInterventionId(i.getId()).orElse(null);
        if (pose == null) {
            return;
        }
        for (InterventionPartRequest p : pieces.findByFittingMissionId(pose.getId())) {
            if (p.getState() != PartRequestState.LIVREE) {
                continue;
            }
            p.setState(PartRequestState.POSEE);
            p.setFittedAt(now);
            p.setFittedInterventionId(i.getId());
            p.setFittedBy(compteDe(jwt));
            p.setUpdatedAt(now);
            pieces.save(p);
            event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                    "Pièce montée pendant cette sortie : " + p.getDesignation() + " (réf. "
                            + (p.getReference() == null ? "non lue sur la pièce" : p.getReference())
                            + "), demandée dehors et livrée au magasin — mission " + pose.getNumber());
            audit.record("UPDATE", "INTERVENTION_PART", p.getId(),
                    "Sortie " + i.getNumber() + " : pièce posée — " + p.getDesignation()
                            + " (mission " + pose.getNumber() + ")", jwt);
        }
    }

    /**
     * Les pièces vues devant le bus deviennent la marchandise du dossier. Une ligne
     * d{@code intervention_part_requests} sans {@code sav_case_id} au dos ne figure
     * dans aucun papier AG : le constructeur ne rembourse que des pièces rattachées à
     * un appel, et le rattachement ne se ressaisit pas à l'écran — il se déduit de la
     * sortie qui les a vues.
     *
     * Trois silences, et chacun est une faute d'un autre :
     *  - la sortie n'a pas de dossier (une sortie née d'un rappel de forfait) : rien
     *    à réclamer, une réclamation suppose un client qui a demandé ;
     *  - le dossier rattaché n'existe plus : {@code DELETE /api/sav-cases} ne nettoie
     *    aucun pointeur, et une closure refusée parce qu'un papier a été supprimé
     *    punirait l'homme qui clôture sa sortie d'un geste d'un autre ;
     *  - une ligne déjà portée par un dossier n'est pas déplacée : deux dossiers ne
     *    peuvent pas réclamer la même pièce, et celui qui l'a demandée dehors a raison
     *    contre celui qui vient après.
     */
    private void reclamerLesPiecesDeLaSortie(Intervention i, Jwt jwt) {
        if (i.getSavCaseId() == null) {
            return;
        }
        SavCase dossier = savCases.findById(i.getSavCaseId()).orElse(null);
        if (dossier == null) {
            return;
        }
        List<InterventionPartRequest> aReclamer = pieces.findByInterventionIdOrderByCreatedAtAsc(i.getId())
                .stream().filter(p -> p.getSavCaseId() == null).toList();
        if (aReclamer.isEmpty()) {
            return;
        }
        aReclamer.forEach(p -> p.setSavCaseId(dossier.getId()));
        pieces.saveAll(aReclamer);
        event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                aReclamer.size() + " pièce(s) vue(s) sur ce bus réclamée(s) par le dossier "
                        + dossier.getCaseNumber());
    }

    /** Le compte qui écrit la case, comme sur « demandée par », « commandée par », « reçue par ». */
    private static String compteDe(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String email = jwt.getClaimAsString("email");
        return email == null || email.isBlank() ? jwt.getSubject() : email;
    }

    /** Les sorties rattachées à un dossier SAV, dans l'ordre chronologique. */
    @Transactional(readOnly = true)
    public List<InterventionResponse> forSavCase(String savCaseId, Authentication auth, Jwt jwt) {
        requireMember(auth);
        List<Intervention> rows = interventions.findBySavCaseIdOrderByReportedAtAsc(savCaseId);
        String own = ownTechnicianId(auth, jwt);
        return mapAll(own == null ? rows
                : rows.stream().filter(i -> own.equals(i.getTechnicianId())).toList());
    }

    /** Note interne du chef d'atelier : le journal garde l'auteur et l'heure. */
    @Transactional
    public InterventionResponse note(String id, NoteRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        event(i.getId(), InterventionEventType.NOTE, InterventionEventSource.MANAGED, jwt, r.note());
        audit.record("NOTE", "INTERVENTION", id,
                "Note sur la sortie " + i.getNumber(), jwt);
        return full(i);
    }

    /**
     * Rectification d'une heure ou d'un kilométrage. Elle est autorisée parce
     * qu'une erreur de saisie arrive, mais elle s'AJOUTE au journal en gardant la
     * valeur fausse visible, avec la raison demandée — sinon le registre cesserait
     * de prouver quoi que ce soit.
     */
    @Transactional
    public InterventionResponse correct(String id, CorrectionRequest r, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        String before;
        String after;
        switch (r.field()) {
            case "reportedAt" -> {
                LocalDateTime v = requireValue(r.value(), "reportedAt");
                before = at(i.getReportedAt());
                i.setReportedAt(v);
                after = at(v);
            }
            case "departedAt" -> {
                LocalDateTime v = requireValue(r.value(), "departedAt");
                before = at(i.getDepartedAt());
                i.setDepartedAt(v);
                after = at(v);
            }
            case "arrivedAt" -> {
                LocalDateTime v = requireValue(r.value(), "arrivedAt");
                before = at(i.getArrivedAt());
                i.setArrivedAt(v);
                after = at(v);
            }
            case "finishedAt" -> {
                LocalDateTime v = requireValue(r.value(), "finishedAt");
                before = at(i.getFinishedAt());
                i.setFinishedAt(v);
                after = at(v);
            }
            case "leftSiteAt" -> {
                LocalDateTime v = requireValue(r.value(), "leftSiteAt");
                before = at(i.getLeftSiteAt());
                i.setLeftSiteAt(v);
                after = at(v);
            }
            case "returnedAt" -> {
                LocalDateTime v = requireValue(r.value(), "returnedAt");
                before = at(i.getReturnedAt());
                i.setReturnedAt(v);
                after = at(v);
            }
            case "startMileage" -> {
                Long v = requireMileageValue(r.mileageValue());
                before = str(i.getStartMileage());
                i.setStartMileage(v);
                after = str(v);
            }
            case "endMileage" -> {
                Long v = requireMileageValue(r.mileageValue());
                before = str(i.getEndMileage());
                i.setEndMileage(v);
                after = str(v);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Champ non rectifiable : « " + r.field() + " ».");
        }
        i.setUpdatedAt(LocalDateTime.now());
        interventions.save(i);
        event(i.getId(), InterventionEventType.CORRECTED, InterventionEventSource.MANAGED, jwt,
                r.field() + " : " + before + " → " + after + " (motif : " + r.reason() + ")");
        audit.record("CORRECT", "INTERVENTION", id,
                "Sortie " + i.getNumber() + " rectifiée : " + r.field(), jwt);
        return full(i);
    }

    /** Suppression : seulement tant que rien n'a été horodaté, pour ne pas effacer une preuve. */
    @Transactional
    public void delete(String id, Jwt jwt, Authentication auth) {
        Intervention i = requireVisible(id, auth, jwt);
        requireManager(auth);
        if (i.getDepartedAt() != null || i.getArrivedAt() != null || i.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sortie " + i.getNumber() + " a des heures enregistrées : on ne la supprime pas, "
                            + "elle reste au registre.");
        }
        if (i.getServiceVehicleId() != null) {
            serviceVehicles.findById(i.getServiceVehicleId()).ifPresent(v -> {
                if (v.getStatus() == ServiceVehicleStatus.DISPATCHED) {
                    v.setStatus(ServiceVehicleStatus.AVAILABLE);
                    serviceVehicles.save(v);
                }
            });
        }
        // Un dossier qualité né d'un clic sur cette sortie lui survit : le problème vu
        // devant le bus reste déclaré. Seul le lien vers la sortie effacée s'éteint,
        // sinon la fiche du dossier pointerait une sortie qui n'existe plus.
        List<SavCase> declares = savCases.findByDeclaredFromInterventionId(id);
        if (!declares.isEmpty()) {
            declares.forEach(c -> c.setDeclaredFromInterventionId(null));
            savCases.saveAll(declares);
        }
        interventions.deleteById(id);
        audit.record("DELETE", "INTERVENTION", id,
                "Suppression de la sortie " + i.getNumber() + " avant tout horodatage", jwt);
    }

    // ------------------------------------------------------------- mises en forme

    InterventionResponse full(Intervention i) {
        // Une identité pas encore posée ne se cherche pas : « intervention_id is null »
        // désignerait la première mission sans sortie, pas la bonne.
        Mission demande = i.getId() == null ? null
                : demandes.findByInterventionId(i.getId()).orElse(null);
        List<InterventionChecklistItem> fiche = i.getId() == null ? List.of()
                : checklist.findByInterventionIdOrderByPositionAsc(i.getId());
        return map(i, nameOf(i.getTechnicianId()), vehicleLabel(i.getVehicleId()),
                clientName(i.getClientId()), registrationOf(i.getServiceVehicleId()),
                savCaseNumber(i.getSavCaseId()), demande, fiche);
    }

    private List<InterventionResponse> mapAll(List<Intervention> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> techNames = technicians.findAllById(ids(rows, Intervention::getTechnicianId))
                .stream().collect(Collectors.toMap(Technician::getId, Technician::getName));
        Map<String, Vehicle> buses = vehicles.findAllById(ids(rows, Intervention::getVehicleId))
                .stream().collect(Collectors.toMap(Vehicle::getId, v -> v));
        Map<String, String> clientNames = clients.findAllById(ids(rows, Intervention::getClientId))
                .stream().collect(Collectors.toMap(Client::getId, Client::getName));
        Map<String, String> registrations = serviceVehicles
                .findAllById(ids(rows, Intervention::getServiceVehicleId))
                .stream().collect(Collectors.toMap(ServiceVehicle::getId, ServiceVehicle::getRegistration));
        Map<String, String> caseNumbers = savCases
                .findAllById(ids(rows, Intervention::getSavCaseId))
                .stream().collect(Collectors.toMap(SavCase::getId, SavCase::getCaseNumber));
        // Le lien inverse, en une seule lecture : la demande qui a fait sortir chaque
        // voiture. Les missions sans sortie sont hors du coup — chercher par des
        // identifiants pleins évite qu'un « is null » raccroche une sortie à la
        // première demande encore indécise.
        Map<String, Mission> parSortie = demandes
                .findByInterventionIdIn(rows.stream().map(Intervention::getId)
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Mission::getInterventionId, java.util.function.Function.identity(),
                        (une, uneAutre) -> une));
        // Les fiches atelier en une seule lecture, groupées par sortie : une requête
        // par ligne ferait payer la liste entière pour un écran qui en montre dix.
        Map<String, List<InterventionChecklistItem>> fiches = checklist
                .findByInterventionIdIn(ids(rows, Intervention::getId)).stream()
                .sorted(java.util.Comparator.comparing(InterventionChecklistItem::getPosition))
                .collect(Collectors.groupingBy(InterventionChecklistItem::getInterventionId));
        List<InterventionResponse> out = new ArrayList<>(rows.size());
        for (Intervention i : rows) {
            out.add(map(i, techNames.get(i.getTechnicianId()), FicheLabel.bus(buses.get(i.getVehicleId())),
                    clientNames.get(i.getClientId()), registrations.get(i.getServiceVehicleId()),
                    caseNumbers.get(i.getSavCaseId()), parSortie.get(i.getId()),
                    fiches.getOrDefault(i.getId(), List.of())));
        }
        return out;
    }

    private InterventionResponse map(Intervention i, String technicianName, String vehicleLabel,
                                     String clientName, String registration, String savCaseNumber,
                                     Mission demande, List<InterventionChecklistItem> fiche) {
        TimesDto times = new TimesDto(i.getReportedAt(), i.getDispatchedAt(), i.getDepartedAt(),
                i.getArrivedAt(), i.getFinishedAt(), i.getLeftSiteAt(), i.getReturnedAt(), i.getClosedAt());
        Long distance = i.getStartMileage() != null && i.getEndMileage() != null
                ? i.getEndMileage() - i.getStartMileage() : null;
        MileagesDto mileages = new MileagesDto(i.getStartMileage(), i.getEndMileage(),
                i.getBusMileage(), i.getBusMileageEnd(), distance);
        DurationsDto durations = new DurationsDto(
                minutes(i.getDepartedAt(), i.getArrivedAt()),
                minutes(i.getArrivedAt(), i.getFinishedAt()),
                minutes(i.getArrivedAt(), i.getLeftSiteAt()),
                minutes(i.getDepartedAt(), i.getReturnedAt()));
        boolean late = i.getDepartedAt() != null && i.getReturnedAt() == null
                && LocalDateTime.now().isAfter(i.getDepartedAt().plusHours(longRunningHours));
        return new InterventionResponse(
                i.getId(), i.getNumber(),
                i.getStatus() != null ? i.getStatus().name().toLowerCase() : null,
                i.getPriority() != null ? i.getPriority().name().toLowerCase() : null,
                i.getVehicleId(), vehicleLabel, i.getClientId(), clientName,
                i.getTechnicianId(), technicianName,
                i.getServiceVehicleId(), registration,
                new LocationDto(i.getLocationAddress(), i.getLocationCity()),
                i.getRequestedBy(), i.getDescription(),
                times, mileages, durations,
                i.getDefectCode(), i.getDiagnosis(), i.getCausingPart(),
                i.getResultNote(), i.getReceivedBy(), i.getClosureNote(), i.getSavCaseId(), savCaseNumber,
                // Une sortie écrite avant la distinction n'a pas de motif : c'était une panne.
                (i.getPurpose() == null ? InterventionPurpose.PANNE : i.getPurpose()).label(),
                i.getSoldPlanId(), i.getPlanStepId(), i.getMaintenanceItemId(),
                i.getCreatedBy(), late, LocalDateTime.now(), i.getCreatedAt(), i.getUpdatedAt(),
                demande == null ? null : demande.getNumber(),
                demande == null ? null : demande.getDecidedAt(),
                fiche.stream().map(item -> new ChecklistItemResponse(item.getId(), item.getPosition(),
                        item.getLabel(), item.isDone(),
                        item.getDoneAt() == null ? null : item.getDoneAt().withNano(0).toString(),
                        item.getDoneBy())).toList(),
                fiche.stream().anyMatch(item -> !item.isDone()));
    }

    // ------------------------------------------------------------------ contrôles

    /** Visible au service photo : la même règle de propriété sert aux deux écrans. */
    Intervention requireVisible(String id, Authentication auth, Jwt jwt) {
        requireMember(auth);
        Intervention i = interventions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sortie introuvable."));
        if (Access.isTechnician(auth)) {
            Technician me = requireTechnician(jwt);
            if (!me.getId().equals(i.getTechnicianId())) {
                // 404 et non 403 : on ne confirme pas à un technicien qu'une sortie d'autrui existe.
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sortie introuvable.");
            }
        }
        return i;
    }

    private List<Intervention> restrictToOwn(List<Intervention> rows, String technicianId) {
        if (technicianId == null) {
            return rows;
        }
        return rows.stream().filter(i -> technicianId.equals(i.getTechnicianId())).toList();
    }

    /** Identifiant de la fiche technicien du compte connecté, ou null si le compte n'est pas technicien.
     *  Visible au service d'alertes : un technicien ne lit que les signaux de ses propres sorties. */
    String ownTechnicianId(Authentication auth, Jwt jwt) {
        return Access.isTechnician(auth) ? requireTechnician(jwt).getId() : null;
    }

    private Technician requireTechnician(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Aucun compte technicien n'est relié à cette connexion.");
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identifiant de connexion illisible.");
        }
        Technician t = technicians.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Ce compte n'est rattaché à aucune fiche technicien : demandez au chef d'atelier."));
        if (!t.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La fiche technicien « " + t.getName() + " » est inactive.");
        }
        return t;
    }

    /**
     * Visible au service des pièces demandées : une pièce se demande dehors, elle
     * appartient donc à la sortie de celui qui l'écrit, et c'est la même règle qui
     * décide. Deux versions de « cette sortie est-elle à vous » finiraient par se
     * contredire.
     */
    void requireTechnicianOrManager(Intervention i, Jwt jwt, Authentication auth) {
        if (Access.managesInterventions(auth)) {
            return;
        }
        if (!Access.isTechnician(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce compte n'a pas accès aux étapes de sortie.");
        }
        Technician me = requireTechnician(jwt);
        if (!me.getId().equals(i.getTechnicianId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cette sortie n'est pas la vôtre.");
        }
    }

    /**
     * Déclarer une sortie depuis un dossier : le guichet (gestionnaire SAV, admin)
     * ou l'atelier, jamais le terrain. Un compte de technicien ne se donne pas
     * lui-même la sortie qui sera payée.
     */
    private void requireDesk(Authentication auth) {
        requireMember(auth);
        if (Access.isTechnician(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Une sortie se déclare depuis le guichet ou l'atelier, pas depuis le terrain.");
        }
    }

    private void requireManager(Authentication auth) {
        requireMember(auth);
        if (!Access.managesInterventions(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seuls le chef d'atelier et l'administrateur ouvrent, affectent et clôturent une sortie.");
        }
    }

    /**
     * Le module Dépannage est interne : le rôle CONSULTATION (et tout compte qui
     * n'y est pas habilité) n'en lit ni les sorties, ni les temps, ni les primes.
     * Vérifié à chaque appel, y compris en lecture.
     *
     * Visible du dehors : l'écran des pièces demandées relit des lignes de sortie et
     * doit opposer la même garde, sinon une fenêtre du module ouvrirait le module.
     */
    void requireMember(Authentication auth) {
        if (!Access.usesDepannageModule(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Le module dépannage est interne : votre compte n'y a pas accès.");
        }
    }

    private void requireStatus(Intervention i, InterventionStatus expected, String action) {
        if (i.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Impossible d'enregistrer " + action + " : la sortie est au statut « "
                            + label(i.getStatus()) + " », elle doit être « " + label(expected) + " ».");
        }
    }

    private void requireFreshTimestamp(LocalDateTime existing, String message) {
        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private ServiceVehicle availableVehicle(String id) {
        ServiceVehicle v = serviceVehicles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Voiture de déplacement introuvable."));
        if (!v.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le véhicule « " + v.getRegistration() + " » est retiré de la flotte.");
        }
        if (v.getStatus() != ServiceVehicleStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le véhicule « " + v.getRegistration() + " » n'est pas disponible ("
                            + vehicleLabel(v.getStatus()) + ").");
        }
        return v;
    }

    private LocalDateTime checkedReportedAt(LocalDateTime requested) {
        LocalDateTime now = LocalDateTime.now();
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'heure de l'appel est dans le futur : elle ne peut pas être postérieure à maintenant.");
        }
        if (requested.isBefore(now.minusDays(90))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'heure de l'appel est antérieure de plus de 90 jours : "
                            + "une sortie si ancienne se déclare comme dossier régulier, pas comme dépannage.");
        }
        return requested;
    }

    /**
     * Le compteur du bus relevé à la fin. Obligatoirement à ou après celui de
     * l'arrivée : un bus qui recule signerait une sortie inventée après coup.
     */
    private Long checkedBusEnd(Intervention i, Long value) {
        Long checked = checkedMileage(value, "le compteur du bus à la fin");
        if (checked != null && i.getBusMileage() != null && checked < i.getBusMileage()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compteur du bus à la fin (" + checked + " km) est plus petit que celui relevé "
                            + "sur place (" + i.getBusMileage() + " km). Reprenez la valeur affichée au compteur.");
        }
        return checked;
    }

    private Long checkedMileage(Long value, String label) {
        // La borne est celle de la porte du compteur : un relevé de sortie et une
        // saisie de fiche se contrôlent avec le même nombre, pas deux règles voisines.
        return VehicleMileageService.format(value, label);
    }

    private LocalDateTime requireValue(LocalDateTime value, String field) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nouvelle heure du champ « " + field + " » est obligatoire.");
        }
        if (value.isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une heure rectifiée ne peut pas être dans le futur.");
        }
        return value;
    }

    private Long requireMileageValue(Long value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nouvelle valeur du compteur est obligatoire.");
        }
        return checkedMileage(value, "le kilométrage");
    }

    /** Le numéro DP-AAAA-NNN est attribué par le serveur, jamais tapé dans un navigateur. */
    private String nextNumber() {
        String prefix = PREFIX + LocalDateTime.now().getYear() + "-";
        return NumberSequence.suivant(prefix, interventions.findNumbersByPrefix(prefix), SEQUENCE_WIDTH);
    }

    void event(String interventionId, InterventionEventType type, InterventionEventSource source,
               Jwt jwt, String note) {
        InterventionEvent e = new InterventionEvent();
        e.setInterventionId(interventionId);
        e.setEventType(type);
        e.setSource(source);
        e.setRecordedAt(LocalDateTime.now());
        e.setNote(truncate(note, 500));
        if (jwt != null) {
            e.setActorUserId(jwt.getSubject());
            e.setActorEmail(jwt.getClaimAsString("email"));
        }
        events.save(e);
    }

    private static List<String> ids(List<Intervention> rows,
                                    java.util.function.Function<Intervention, String> getter) {
        return rows.stream().map(getter).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static Long minutes(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        return Duration.between(from, to).toMinutes();
    }

    /** La fiche de paie du technicien de la sortie ; null si la fiche a disparu du dossier. */
    private Technician ficheOf(Intervention i) {
        return i.getTechnicianId() == null ? null
                : technicians.findById(i.getTechnicianId()).orElse(null);
    }

    private String nameOf(String technicianId) {
        return technicianId == null ? null
                : technicians.findById(technicianId).map(Technician::getName).orElse(null);
    }

    /**
     * Qui a relu le compteur du bus : l'homme affecté à la sortie, à défaut le compte
     * qui a tapé la valeur. Le nom part avec le chiffre sur la fiche du bus, parce
     * qu'un kilométrage contesté se discute avec un témoin.
     */
    private String releveur(Intervention i, Technician tech, Jwt jwt) {
        String nom = tech != null ? tech.getName() : nameOf(i.getTechnicianId());
        return nom != null ? nom : (jwt == null ? null : jwt.getClaimAsString("email"));
    }

    private String registrationOf(String serviceVehicleId) {
        return serviceVehicleId == null ? null
                : serviceVehicles.findById(serviceVehicleId)
                .map(ServiceVehicle::getRegistration).orElse(null);
    }

    private String vehicleLabel(String vehicleId) {
        return vehicleId == null ? null
                : FicheLabel.bus(vehicles.findById(vehicleId).orElse(null));
    }

    private String clientName(String clientId) {
        return clientId == null ? null : clients.findById(clientId).map(Client::getName).orElse(null);
    }

    /** Les angles de preuve absents, libellés pour l'écran de l'atelier. */
    private List<String> missingProof(String interventionId) {
        return PROOF_ANGLES.stream()
                .filter(a -> !photos.existsByInterventionIdAndAngle(interventionId, a))
                .map(PhotoAngle::label)
                .toList();
    }

    private String savCaseNumber(String savCaseId) {
        return savCaseId == null ? null
                : savCases.findById(savCaseId).map(SavCase::getCaseNumber).orElse(null);
    }

    private static boolean isOneOf(String value, String... candidates) {
        for (String c : candidates) {
            if (c.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String label(InterventionStatus status) {
        return status == null ? "aucun" : status.name().toLowerCase();
    }

    /** Un filtre d'écran mal orthographié doit répondre clairement plutôt que de trier n'importe quoi. */
    private static InterventionStatus parseStatus(String value) {
        try {
            return InterventionStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Statut de sortie inconnu : « " + value + " ».");
        }
    }

    private static String vehicleLabel(ServiceVehicleStatus status) {
        return switch (status) {
            case AVAILABLE -> "disponible";
            case DISPATCHED -> "déjà en intervention";
            case AT_WORKSHOP -> "à l'atelier";
            case OUT_OF_ORDER -> "en panne";
        };
    }

    private static String at(LocalDateTime v) {
        return v == null ? "—" : v.toString();
    }

    private static String str(Long v) {
        return v == null ? "—" : String.valueOf(v);
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
