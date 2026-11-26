package tn.higer.sav_backend.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusDiagnosisRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusLineRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.AnnulationRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.ChecklistItemResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.CocherRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.ConstatRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DepartRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.AvailabilityResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.HistoryRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.MyDossierRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.AttachmentResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.PhotoResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.RetourRequest;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.MissionCallChannel;
import tn.higer.sav_backend.model.MissionGroupAttachment;
import tn.higer.sav_backend.model.MissionGroupBus;
import tn.higer.sav_backend.model.MissionGroupChecklistItem;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.model.MissionGroupPhoto;
import tn.higer.sav_backend.model.MissionGroupPhotoAngle;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.ServiceVehicle;
import tn.higer.sav_backend.model.ServiceVehicleStatus;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionGroupAttachmentRepository;
import tn.higer.sav_backend.repository.MissionGroupBusRepository;
import tn.higer.sav_backend.repository.MissionGroupChecklistItemRepository;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.repository.MissionGroupPhotoRepository;
import tn.higer.sav_backend.repository.ServiceVehicleRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Le registre des missions groupe : un dossier = un déplacement de la voiture,
 * une ligne = un bus.
 *
 * Le geste se fait à deux mains, et c'est le serveur qui sépare les rôles :
 * 1. l'atelier prépare le dossier — client du portefeuille, bus choisis parmi
 *    ceux du client (ou désignés par leur immatriculation saisie à la main),
 *    problème écrit bus par bus, homme et voiture affectés, départ prévu. Le
 *    numéro est proposé (1740, 1741…) mais l'agent peut le remplacer par celui
 *    du registre papier ; un numéro déjà pris est refusé ;
 * 2. le technicien affecté pointe le départ réel (heure + kilométrage de la
 *    voiture) en versant la photo avant ; plus tard, il pointe le retour, le
 *    kilométrage et la photo après, avec le diagnostic de chaque bus. L'atelier
 *    garde la main pour corriger un pointage oublié.
 *
 * Les états ne sont jamais stockés : « prévu » sans départ réel, « en
 * déplacement » avec un départ et sans retour, « rentré » avec un retour. Les
 * kilomètres parcourus se soustraient. Une seule écriture, une seule vérité.
 */
@Service
public class MissionGroupService {

    /** Le registre papier reprend à 1740 : le premier numéro proposé suit ce point de départ. */
    private static final long FIRST_NUMBER = 1740;

    private static final int MAX_HISTORY = 20;

    private final MissionGroupDossierRepository dossiers;
    private final MissionGroupBusRepository buses;
    private final MissionGroupPhotoRepository photos;
    private final MissionGroupChecklistItemRepository checklist;
    private final MissionGroupAttachmentRepository attachments;
    private final ClientRepository clients;
    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final ServiceVehicleRepository serviceVehicles;
    private final InterventionRepository interventions;
    private final TechnicianRepository technicians;
    private final AuditService audit;
    private final PushNotificationService push;

    public MissionGroupService(MissionGroupDossierRepository dossiers,
                               MissionGroupBusRepository buses,
                               MissionGroupPhotoRepository photos,
                               MissionGroupChecklistItemRepository checklist,
                               MissionGroupAttachmentRepository attachments,
                               ClientRepository clients,
                               VehicleRepository vehicles,
                               UserRepository users,
                               ServiceVehicleRepository serviceVehicles,
                               InterventionRepository interventions,
                               TechnicianRepository technicians,
                               AuditService audit,
                               PushNotificationService push) {
        this.dossiers = dossiers;
        this.buses = buses;
        this.photos = photos;
        this.checklist = checklist;
        this.attachments = attachments;
        this.clients = clients;
        this.vehicles = vehicles;
        this.users = users;
        this.serviceVehicles = serviceVehicles;
        this.interventions = interventions;
        this.technicians = technicians;
        this.audit = audit;
        this.push = push;
    }

    // ------------------------------------------------------------------ lecture

    /**
     * La liste. Le technicien ne reçoit que les dossiers où il est l'homme
     * affecté ; l'atelier reçoit tout. Le filtre est fait ici, pas à l'écran.
     */
    public Page<DossierResponse> list(Pageable pageable, Jwt jwt, Authentication auth) {
        requireReader(auth);
        if (Access.isTechnician(auth)) {
            return dossiers.findByTechnicianUserIdOrderByCreatedAtDesc(subject(jwt), pageable)
                    .map(this::response);
        }
        return dossiers.findAllByOrderByCreatedAtDesc(pageable).map(this::response);
    }

    public DossierResponse get(String id, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = find(id);
        requireReaderOf(dossier, jwt, auth);
        return response(dossier);
    }

    /**
     * Le suivi du terrain : ses dossiers non rentrés, en lignes légères. La
     * cloche du téléphone relit souvent ; elle n'a pas besoin des bus, photos et
     * pièces jointes pour dire qu'un dossier est arrivé ou qu'un départ prévu
     * est passé. Aucune ligne d'un autre homme ne sort d'ici.
     */
    public List<MyDossierRow> mesDossiers(Jwt jwt, Authentication auth) {
        if (!Access.isTechnician(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cette liste est celle du technicien : elle ne montre que ses propres dossiers en cours.");
        }
        return dossiers.findByTechnicianUserIdAndReturnedAtIsNullAndCancelledAtIsNullOrderByCreatedAtDesc(subject(jwt))
                .stream()
                .map(d -> new MyDossierRow(d.getId(), d.getNumber(), d.getClientName(),
                        format(d.getCreatedAt()), format(d.getPlannedDepartureAt()),
                        format(d.getDepartedAt()), format(d.getReturnedAt())))
                .toList();
    }

    /** Le numéro suivant, calculé sur le plus grand numéro déjà écrit — jamais sur le nombre de lignes. */
    public String prochainNumero() {
        long plusHaut = 0;
        for (String numero : dossiers.findAllNumbers()) {
            long valeur = nombre(numero);
            if (valeur > plusHaut) {
                plusHaut = valeur;
            }
        }
        long prochain = Math.max(plusHaut + 1, FIRST_NUMBER);
        while (dossiers.existsByNumberIgnoreCase(String.valueOf(prochain))) {
            prochain++;
        }
        return String.valueOf(prochain);
    }

    /**
     * L'historique d'un bus. La plaque est normalisée des deux côtés (majuscules,
     * accents, espaces et tirets retirés) : « 230 TU 3342 » et « 230-TU-3342 »
     * sont le même bus. Un dossier préparé mais pas encore parti n'y figure pas :
     * l'historique raconte des déplacements faits, pas des projets.
     */
    public List<HistoryRow> historique(String registration, Authentication auth) {
        requireReader(auth);
        String cle = cleImmatriculation(registration);
        if (cle.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Écrivez l'immatriculation du bus pour lire son historique.");
        }
        List<Ligne> lignes = new ArrayList<>();
        for (MissionGroupBus bus : buses.findByRegistrationKeyOrderByCreatedAtDesc(cle)) {
            dossiers.findById(bus.getDossierId())
                    .filter(d -> d.getDepartedAt() != null)
                    .ifPresent(d -> lignes.add(new Ligne(bus, d)));
        }
        lignes.sort(Comparator.comparing((Ligne l) -> l.dossier().getDepartedAt()).reversed());
        return lignes.stream()
                .limit(MAX_HISTORY)
                .map(l -> new HistoryRow(l.dossier().getId(), l.dossier().getNumber(),
                        format(l.dossier().getDepartedAt()), l.bus().getRegistration(),
                        l.bus().getDescription(), l.bus().getDiagnosis(),
                        format(l.dossier().getReturnedAt()), traveled(l.dossier())))
                .toList();
    }

    // ----------------------------------------------------------------- écriture

    /**
     * Préparer le dossier : le client, ses bus et leurs problèmes, l'homme, la
     * voiture et le départ prévu. Aucune date réelle, aucun kilométrage : c'est
     * le technicien qui les posera devant la voiture.
     */
    @Transactional
    public DossierResponse creer(DossierRequest request, Jwt jwt, Authentication auth) {
        requirePreparer(auth);
        ClientDuDossier client = clientDuDossier(request);
        String clientId = client.id();

        AppUser homme = findTechnician(request.technicianUserId());
        ServiceVehicle voiture = findVehicle(request.serviceVehicleId());
        requirePeriodeCoherente(request.plannedDepartureAt(), request.plannedReturnAt(),
                request.plannedDepartureMileage(), request.plannedReturnMileage());
        List<String> conflits = conflitsAffectation(homme, voiture,
                request.plannedDepartureAt(), request.plannedReturnAt(), null);
        MissionCallChannel canal = canal(request.callChannel());

        List<LigneBus> choisis = busesDuClient(clientId, request.buses());

        String number = trim(request.number());
        if (number == null) {
            number = prochainNumero();
        }
        if (dossiers.existsByNumberIgnoreCase(number)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le numéro " + number + " est déjà porté par un dossier : "
                            + "proposez-en un autre ou reprenez le numéro du papier.");
        }

        LocalDateTime now = LocalDateTime.now();
        MissionGroupDossier dossier = new MissionGroupDossier();
        dossier.setId("mgd-" + UUID.randomUUID());
        dossier.setNumber(number);
        dossier.setClientId(clientId);
        dossier.setClientName(client.name());
        dossier.setClientAddress(client.address());
        dossier.setClientCity(client.city());
        dossier.setClientPhone(client.phone());
        dossier.setCallChannel(canal);
        dossier.setPlannedDepartureAt(request.plannedDepartureAt());
        dossier.setPlannedReturnAt(request.plannedReturnAt());
        dossier.setPlannedDepartureMileage(request.plannedDepartureMileage());
        dossier.setPlannedReturnMileage(request.plannedReturnMileage());
        dossier.setTechnicianUserId(homme.getId().toString());
        dossier.setTechnicianName(homme.getName());
        dossier.setServiceVehicleId(voiture.getId());
        dossier.setServiceVehicleRegistration(voiture.getRegistration());
        dossier.setCreatedBy(subject(jwt));
        dossier.setCreatedAt(now);
        dossiers.save(dossier);

        List<MissionGroupBus> ecrites = ecrireBuses(dossier, choisis, now);

        List<MissionGroupChecklistItem> fiche = ecrireFiche(dossier.getId(), request.checklist(), now);

        audit.record("CREATE", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " (" + ecrites.size() + " bus, fiche atelier : "
                        + fiche.size() + " lignes) pour " + client.name() + ", "
                        + homme.getName() + " et la voiture " + voiture.getRegistration(), jwt);
        noterConflits(dossier, conflits, "créé", jwt);
        apresValidation(() -> prevenirAffectation(dossier, false));
        return response(dossier, ecrites, List.of(), fiche, List.of());
    }

    /**
     * Modifier un dossier encore à l'atelier : le client, ses bus, l'homme, la
     * voiture et la période estimée se réécrivent tant que le départ réel n'est
     * pas pointé. Une fois parti, le papier est l'histoire du déplacement et ne
     * se réécrit plus. Une photo de bus, elle, est accrochée à une ligne que la
     * correction recrée : tant qu'il en existe une, le dossier reste tel quel.
     * Les photos de la voiture ne gênent pas la correction — elles ne désignent
     * aucune ligne.
     *
     * Un dossier annulé reste corrigeable : si l'homme ou la voiture ont été
     * engagés ailleurs entre-temps, l'atelier change l'affectation ici, puis le
     * rouvre. La correction ne rouvre rien d'elle-même — l'annulation et son
     * motif restent sur le dossier jusqu'au geste « Rouvrir ».
     */
    @Transactional
    public DossierResponse modifier(String id, DossierRequest request, Jwt jwt, Authentication auth) {
        requirePreparer(auth);
        MissionGroupDossier dossier = find(id);
        String ancienHomme = dossier.getTechnicianUserId();
        if (dossier.getDepartedAt() != null || dossier.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà parti : "
                            + "le papier préparé ne se modifie plus.");
        }
        if (photos.existsByDossierIdAndBusIdIsNotNull(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " porte des photos de bus : "
                            + "la correction recrée les lignes de bus et laisserait ces preuves "
                            + "sans support. Le dossier reste tel quel.");
        }
        ClientDuDossier client = clientDuDossier(request);
        String clientId = client.id();

        AppUser homme = findTechnician(request.technicianUserId());
        ServiceVehicle voiture = findVehicle(request.serviceVehicleId());
        requirePeriodeCoherente(request.plannedDepartureAt(), request.plannedReturnAt(),
                request.plannedDepartureMileage(), request.plannedReturnMileage());
        List<String> conflits = conflitsAffectation(homme, voiture,
                request.plannedDepartureAt(), request.plannedReturnAt(), id);
        MissionCallChannel canal = canal(request.callChannel());
        List<LigneBus> choisis = busesDuClient(clientId, request.buses());

        String number = trim(request.number());
        if (number == null) {
            number = dossier.getNumber();
        }
        if (!number.equalsIgnoreCase(dossier.getNumber())
                && dossiers.existsByNumberIgnoreCase(number)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le numéro " + number + " est déjà porté par un dossier : "
                            + "proposez-en un autre ou reprenez le numéro du papier.");
        }

        LocalDateTime now = LocalDateTime.now();
        dossier.setNumber(number);
        dossier.setClientId(clientId);
        dossier.setClientName(client.name());
        dossier.setClientAddress(client.address());
        dossier.setClientCity(client.city());
        dossier.setClientPhone(client.phone());
        dossier.setCallChannel(canal);
        dossier.setPlannedDepartureAt(request.plannedDepartureAt());
        dossier.setPlannedReturnAt(request.plannedReturnAt());
        dossier.setPlannedDepartureMileage(request.plannedDepartureMileage());
        dossier.setPlannedReturnMileage(request.plannedReturnMileage());
        dossier.setTechnicianUserId(homme.getId().toString());
        dossier.setTechnicianName(homme.getName());
        dossier.setServiceVehicleId(voiture.getId());
        dossier.setServiceVehicleRegistration(voiture.getRegistration());
        dossier.setUpdatedAt(now);
        dossiers.save(dossier);

        List<MissionGroupBus> anciennes = buses.findByDossierIdOrderByPositionAsc(id);
        buses.deleteAll(anciennes);
        List<MissionGroupChecklistItem> ancienneFiche =
                checklist.findByDossierIdOrderByPositionAsc(id);
        checklist.deleteAll(ancienneFiche);
        List<MissionGroupBus> ecrites = ecrireBuses(dossier, choisis, now);
        List<MissionGroupChecklistItem> fiche = ecrireFiche(id, request.checklist(), now);

        audit.record("UPDATE", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " modifié : " + ecrites.size()
                        + " bus, fiche atelier " + fiche.size() + " lignes, pour "
                        + client.name() + ", " + homme.getName() + " et la voiture "
                        + voiture.getRegistration()
                        + (dossier.getCancelledAt() == null ? ""
                        : " (dossier annulé : l'annulation reste posée)"), jwt);
        noterConflits(dossier, conflits, "modifié", jwt);
        // Une correction d'heure ne fait pas sonner le téléphone ; un autre homme
        // affecté, si. L'ancien n'a plus rien à faire de ce dossier. Sur un
        // dossier annulé, personne ne sonne : la réouverture préviendra.
        if (dossier.getCancelledAt() == null
                && !Objects.equals(ancienHomme, dossier.getTechnicianUserId())) {
            apresValidation(() -> prevenirAffectation(dossier, true));
        }
        return response(dossier, ecrites, List.of(), fiche,
                attachments.findByDossierIdOrderByUploadedAtAsc(id));
    }

    /**
     * Annuler un dossier encore à l'atelier. Une visite reportée ou une panne
     * disparue laissait sinon l'homme et la voiture pris pour toujours : seule
     * la rentrée rendait la place. L'annulation la rend, et le motif reste écrit
     * sur la ligne — c'est lui qui expliquera, plus tard, pourquoi la visite
     * préparée n'a pas eu lieu.
     *
     * Un dossier déjà parti ne s'annule plus : le papier est alors l'histoire
     * d'un déplacement, et ses preuves ne se retirent pas.
     */
    @Transactional
    public DossierResponse annuler(String id, AnnulationRequest request, Jwt jwt, Authentication auth) {
        requirePreparer(auth);
        MissionGroupDossier dossier = find(id);
        if (dossier.getCancelledAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà annulé"
                            + (dossier.getCancelReason() == null ? "."
                            : " (« " + dossier.getCancelReason() + " »)."));
        }
        if (dossier.getDepartedAt() != null || dossier.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà parti : le papier est "
                            + "l'histoire du déplacement, il ne s'annule plus.");
        }
        String motif = trim(request == null ? null : request.reason());
        if (motif == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un dossier ne s'annule pas sans motif : dites pourquoi la visite n'aura pas lieu. "
                            + "Le motif restera sur le dossier et se relira au contrôle.");
        }

        LocalDateTime now = LocalDateTime.now();
        dossier.setCancelledAt(now);
        dossier.setCancelReason(motif);
        dossier.setCancelledBy(jwt == null ? null : jwt.getClaimAsString("email"));
        dossier.setUpdatedAt(now);
        dossiers.save(dossier);

        audit.record("CANCEL", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " annulé : " + motif, jwt);
        prevenirAnnulation(dossier);
        return response(dossier);
    }

    /**
     * Rouvrir un dossier annulé par erreur. Entre l'annulation et aujourd'hui,
     * l'homme ou la voiture ont pu être engagés ailleurs : la disponibilité est
     * relue, mais elle ne bloque pas la réouverture. Un conflit éventuel est
     * écrit au journal (« CONFLICT_ACCEPTED ») et l'écran l'a déjà montré en rouge.
     *
     * Le motif d'annulation ne disparaît pas du contrôle : il passe au journal,
     * et le téléphone de l'homme reçoit de nouveau l'annonce du dossier.
     */
    @Transactional
    public DossierResponse rouvrir(String id, Jwt jwt, Authentication auth) {
        requirePreparer(auth);
        MissionGroupDossier dossier = find(id);
        if (dossier.getCancelledAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " n'est pas annulé : "
                            + "il n'y a rien à rouvrir.");
        }
        if (dossier.getDepartedAt() != null || dossier.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà parti : "
                            + "il ne se rouvre plus.");
        }

        verrouillerAffectation(dossier.getTechnicianUserId(), dossier.getServiceVehicleId());
        Occupation prise = occupation(dossier.getPlannedDepartureAt(),
                dossier.getPlannedReturnAt(), id);
        String hommeMotif = dossier.getTechnicianUserId() == null ? null
                : prise.hommes().get(dossier.getTechnicianUserId());
        String voitureMotif = dossier.getServiceVehicleId() == null ? null
                : prise.voitures().get(dossier.getServiceVehicleId());
        List<String> conflits = new ArrayList<>();
        if (hommeMotif != null) {
            conflits.add("Technicien « " + dossier.getTechnicianName() + " » : " + hommeMotif);
        }
        if (voitureMotif != null) {
            conflits.add("Voiture « " + dossier.getServiceVehicleRegistration() + " » : " + voitureMotif);
        }

        String ancienMotif = dossier.getCancelReason();
        dossier.setCancelledAt(null);
        dossier.setCancelReason(null);
        dossier.setCancelledBy(null);
        dossier.setUpdatedAt(LocalDateTime.now());
        dossiers.save(dossier);

        audit.record("REOPEN", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " rouvert"
                        + (ancienMotif == null ? "." : " (motif d'annulation : " + ancienMotif + ")."),
                jwt);
        noterConflits(dossier, conflits, "rouvert", jwt);
        apresValidation(() -> prevenirReouverture(dossier));
        return response(dossier);
    }

    /**
     * Ce qui est déjà pris sur une période, avec le motif à relire. Un dossier
     * rentré ne bloque plus rien ; un dossier sans période estimée est ignoré —
     * il n'a pas de fenêtre à comparer. Le dossier en cours de modification
     * s'exclut lui-même.
     *
     * L'homme et la voiture ne se lisent pas seulement dans ce registre : une
     * sortie de dépannage encore ouverte les tient aussi, jusqu'à sa clôture
     * (voir {@link #occupation}). C'est la même vérité que l'écran des sorties.
     */
    public AvailabilityResponse disponibilites(LocalDateTime depart, LocalDateTime retour,
                                               String excludeId, Authentication auth) {
        requirePreparer(auth);
        if (depart == null || retour == null || !retour.isAfter(depart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Renseignez le départ et le retour estimés pour lire les disponibilités.");
        }
        Occupation prise = occupation(depart, retour, excludeId);
        return new AvailabilityResponse(new ArrayList<>(prise.hommes().keySet()),
                new ArrayList<>(prise.voitures().keySet()),
                prise.hommes(), prise.voitures());
    }

    /** Un homme ou une voiture déjà engagé, et la phrase qui dit pourquoi. */
    private record Occupation(Map<String, String> hommes, Map<String, String> voitures) {}

    /**
     * Ce qui est pris sur une période : un autre dossier de déplacement qui se
     * chevauche, un homme qui a une sortie de dépannage encore ouverte, une
     * voiture qui n'est pas libre (sortie, atelier ou panne).
     *
     * Une sortie ouverte tient son homme jusqu'à sa clôture, même sans retour
     * pointé : c'est la règle de l'écran des sorties (« un homme, une sortie à
     * la fois »), et la voiture n'est reprise qu'au retour pointé. Un dossier
     * annulé ne tient plus rien : l'atelier a décidé qu'il n'aurait pas lieu.
     */
    private Occupation occupation(LocalDateTime depart, LocalDateTime retour, String excludeId) {
        Map<String, String> hommes = new LinkedHashMap<>();
        Map<String, String> voitures = new LinkedHashMap<>();

        for (MissionGroupDossier dossier : dossiers.findAll()) {
            if (dossier.getReturnedAt() != null || dossier.getCancelledAt() != null) {
                continue;
            }
            if (excludeId != null && excludeId.equals(dossier.getId())) {
                continue;
            }
            if (!seChevauchent(dossier, depart, retour)) {
                continue;
            }
            if (dossier.getTechnicianUserId() != null) {
                hommes.putIfAbsent(dossier.getTechnicianUserId(),
                        "déjà sur le dossier " + dossier.getNumber());
            }
            if (dossier.getServiceVehicleId() != null) {
                voitures.putIfAbsent(dossier.getServiceVehicleId(),
                        "déjà sur le dossier " + dossier.getNumber());
            }
        }

        List<Intervention> ouvertes =
                interventions.findByStatusInOrderByReportedAtDesc(InterventionStatus.OPEN);
        Map<String, UUID> comptes = comptesDesFiches(ouvertes);
        for (Intervention sortie : ouvertes) {
            if (sortie.getTechnicianId() != null) {
                UUID compte = comptes.get(sortie.getTechnicianId());
                if (compte != null) {
                    hommes.putIfAbsent(compte.toString(),
                            "dehors sur la sortie " + sortie.getNumber());
                }
            }
            // Au retour pointé, la voiture est rendue libre : la sortie ne la tient plus.
            if (sortie.getServiceVehicleId() != null
                    && sortie.getStatus() != InterventionStatus.RETURNED) {
                voitures.putIfAbsent(sortie.getServiceVehicleId(),
                        "engagée sur la sortie " + sortie.getNumber());
            }
        }

        for (ServiceVehicle voiture : serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(
                List.of(ServiceVehicleStatus.DISPATCHED, ServiceVehicleStatus.AT_WORKSHOP,
                        ServiceVehicleStatus.OUT_OF_ORDER))) {
            String motif = switch (voiture.getStatus()) {
                case AT_WORKSHOP -> "à l'atelier";
                case OUT_OF_ORDER -> "en panne";
                default -> "engagée sur une sortie";
            };
            voitures.putIfAbsent(voiture.getId(), motif);
        }
        return new Occupation(hommes, voitures);
    }

    /** Le compte du portail derrière chaque fiche technicien des sorties, en une lecture. */
    private Map<String, UUID> comptesDesFiches(List<Intervention> ouvertes) {
        List<String> fiches = ouvertes.stream().map(Intervention::getTechnicianId)
                .filter(Objects::nonNull).distinct().toList();
        if (fiches.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> comptes = new HashMap<>();
        for (Technician fiche : technicians.findAllById(fiches)) {
            if (fiche.getUserId() != null) {
                comptes.put(fiche.getId(), fiche.getUserId());
            }
        }
        return comptes;
    }

    /** Deux périodes qui se touchent sans se recouvrir laissent l'homme libre. */
    private boolean seChevauchent(MissionGroupDossier dossier,
                                  LocalDateTime depart, LocalDateTime retour) {
        // Une mission déjà partie engage son homme et sa voiture jusqu'au retour,
        // même si aucune fin estimée n'avait été notée.
        if (dossier.getDepartedAt() != null) {
            return true;
        }
        LocalDateTime debut = dossier.getPlannedDepartureAt();
        LocalDateTime fin = dossier.getPlannedReturnAt();
        if (debut == null || fin == null || depart == null || retour == null) {
            return false;
        }
        return debut.isBefore(retour) && depart.isBefore(fin);
    }

    /** Les lignes de bus d'un dossier, écrites dans l'ordre de saisie. */
    private List<MissionGroupBus> ecrireBuses(MissionGroupDossier dossier, List<LigneBus> lignes,
                                              LocalDateTime now) {
        List<MissionGroupBus> ecrites = new ArrayList<>();
        int ordre = 1;
        for (LigneBus ligne : lignes) {
            MissionGroupBus mission = new MissionGroupBus();
            mission.setId("mgb-" + UUID.randomUUID());
            mission.setDossierId(dossier.getId());
            mission.setPosition(ordre++);
            // Null pour une plaque saisie à la main : le bus n'est pas au portefeuille.
            mission.setVehicleId(ligne.vehicle() == null ? null : ligne.vehicle().getId());
            // La plaque est recopiée du portefeuille, ou telle que saisie par l'agent.
            mission.setRegistration(ligne.registration());
            mission.setRegistrationKey(cleImmatriculation(ligne.registration()));
            mission.setDescription(ligne.description());
            mission.setDiagnosis(ligne.diagnosis());
            mission.setCreatedAt(now);
            buses.save(mission);
            ecrites.add(mission);
        }
        return ecrites;
    }

    /**
     * La fiche atelier, écrite par l'atelier en préparant le dossier. Les lignes
     * vides sont ignorées ; l'ordre de saisie est l'ordre de lecture sur place.
     */
    private List<MissionGroupChecklistItem> ecrireFiche(String dossierId, List<String> lignes,
                                                        LocalDateTime now) {
        if (lignes == null || lignes.isEmpty()) {
            return List.of();
        }
        List<MissionGroupChecklistItem> ecrites = new ArrayList<>();
        int ordre = 1;
        for (String ligne : lignes) {
            String label = trim(ligne);
            if (label == null) {
                continue;
            }
            MissionGroupChecklistItem item = new MissionGroupChecklistItem();
            item.setId("mgc-" + UUID.randomUUID());
            item.setDossierId(dossierId);
            item.setPosition(ordre++);
            item.setLabel(label);
            item.setDone(false);
            checklist.save(item);
            ecrites.add(item);
        }
        return ecrites;
    }

    /**
     * Le départ réel, pointé par le technicien affecté ou par l'atelier : l'heure,
     * et deux photos déjà versées — la voiture et son compteur. Le chiffre est
     * facultatif : la photo prouve le départ, le chiffre ne sert qu'au calcul des
     * kilomètres. Un départ ne s'écrit pas deux fois, et ne se pointe pas sur un
     * dossier déjà rentré.
     */
    @Transactional
    public DossierResponse depart(String id, DepartRequest request, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = find(id);
        requireOperator(dossier, jwt, auth);
        if (dossier.getCancelledAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est annulé : "
                            + "il n'y a plus de départ à pointer.");
        }
        if (dossier.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà rentré : "
                            + "le départ ne se pointe plus.");
        }
        if (dossier.getDepartedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le départ du dossier " + dossier.getNumber() + " est déjà pointé : "
                            + "il ne s'écrit pas deux fois.");
        }
        requirePreuveDeLaVoiture(id, MissionGroupPhotoAngle.DEPART,
                MissionGroupPhotoAngle.COMPTEUR_DEPART, "départ", dossier.getNumber());

        LocalDateTime now = LocalDateTime.now();
        dossier.setDepartedAt(request.departedAt());
        dossier.setDepartureMileage(request.departureMileage());
        dossier.setUpdatedAt(now);
        dossiers.save(dossier);

        audit.record("UPDATE", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Départ du dossier " + dossier.getNumber() + " pointé avec la photo du compteur"
                        + (request.departureMileage() == null ? " (chiffre non relevé)"
                        : " : " + request.departureMileage() + " km"), jwt);
        return response(dossier);
    }

    /**
     * Enregistrer le retour. Le départ réel doit avoir été pointé, la date ne
     * peut pas le précéder, et le compteur de la voiture ne peut pas reculer. Les
     * diagnostics des bus s'écrivent ici, bus par bus.
     */
    @Transactional
    public DossierResponse retour(String id, RetourRequest request, Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = find(id);
        requireOperator(dossier, jwt, auth);
        if (dossier.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà rentré : "
                            + "un retour ne s'écrit pas deux fois.");
        }
        if (dossier.getDepartedAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le retour ne se pointe pas avant le départ : notez d'abord le départ réel "
                            + "du dossier " + dossier.getNumber() + ".");
        }
        requirePreuveDeLaVoiture(id, MissionGroupPhotoAngle.RETOUR,
                MissionGroupPhotoAngle.COMPTEUR_RETOUR, "retour", dossier.getNumber());
        requireRetourCoherent(dossier.getDepartedAt(), request.returnedAt(),
                dossier.getDepartureMileage(), request.returnMileage(), dossier.getNumber());

        List<MissionGroupBus> lignes = buses.findByDossierIdOrderByPositionAsc(id);
        requireConstatsComplets(lignes, dossier.getNumber());

        LocalDateTime now = LocalDateTime.now();
        dossier.setReturnedAt(request.returnedAt());
        dossier.setReturnMileage(request.returnMileage());
        dossier.setUpdatedAt(now);
        dossiers.save(dossier);

        if (request.buses() != null && !request.buses().isEmpty()) {
            Map<String, MissionGroupBus> parId = new HashMap<>();
            for (MissionGroupBus ligne : lignes) {
                parId.put(ligne.getId(), ligne);
            }
            for (BusDiagnosisRequest changement : request.buses()) {
                MissionGroupBus ligne = parId.get(changement.id());
                if (ligne == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le bus " + changement.id() + " n'appartient pas au dossier "
                                    + dossier.getNumber() + ".");
                }
                if (changement.description() != null) {
                    String description = trim(changement.description());
                    if (description == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Le problème d'un bus ne peut pas être vidé par le retour.");
                    }
                    ligne.setDescription(description);
                }
                if (changement.diagnosis() != null) {
                    ligne.setDiagnosis(trim(changement.diagnosis()));
                }
                ligne.setUpdatedAt(now);
                buses.save(ligne);
            }
        }

        audit.record("UPDATE", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Retour du dossier " + dossier.getNumber() + " pointé avec la photo du compteur"
                        + (request.returnMileage() == null ? " (chiffre non relevé)"
                        : " : " + request.returnMileage() + " km"), jwt);
        return response(dossier);
    }

    /**
     * Le retour ne s'écrit pas sur un constat vide : le code d'erreur, la pièce
     * en cause et les observations sont ce que l'atelier relit le lendemain.
     * Une ligne incomplète retient le dossier ; elle ne s'efface pas en rentrant.
     */
    private void requireConstatsComplets(List<MissionGroupBus> lignes, String numero) {
        List<String> incomplets = new ArrayList<>();
        for (MissionGroupBus ligne : lignes) {
            List<String> manques = new ArrayList<>();
            if (trim(ligne.getDefectCode()) == null) {
                manques.add("le code d'erreur");
            }
            if (trim(ligne.getCausingPart()) == null) {
                manques.add("la pièce en cause");
            }
            if (trim(ligne.getObservations()) == null) {
                manques.add("les observations");
            }
            if (!manques.isEmpty()) {
                incomplets.add(ligne.getRegistration() + " (" + String.join(", ", manques) + ")");
            }
        }
        if (!incomplets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le retour du dossier " + numero + " attend le constat de chaque bus : "
                            + String.join(" ; ", incomplets)
                            + ". Écrivez le constat sur place, puis comptez le retour.");
        }
    }

    /**
     * Le constat d'un bus, écrit sur place : le code d'erreur, la pièce en cause
     * et les observations. Les deux photos du bus — avant et après le travail —
     * doivent être versées avant : un constat sans ses clichés ne dit pas dans
     * quel état le bus a été trouvé ni laissé.
     */
    @Transactional
    public DossierResponse constat(String id, String busId, ConstatRequest request,
                                   Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = find(id);
        requireOperator(dossier, jwt, auth);
        if (dossier.getCancelledAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est annulé : "
                            + "il n'y a plus de constat à écrire.");
        }
        MissionGroupBus ligne = buses.findByDossierIdOrderByPositionAsc(id).stream()
                .filter(b -> Objects.equals(b.getId(), busId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce bus n'appartient pas au dossier " + dossier.getNumber() + "."));
        requirePreuveDuBus(id, busId, ligne.getRegistration());

        ligne.setDefectCode(trim(request.defectCode()));
        ligne.setCausingPart(trim(request.causingPart()));
        ligne.setObservations(trim(request.observations()));
        ligne.setUpdatedAt(LocalDateTime.now());
        buses.save(ligne);

        audit.record("UPDATE", "MISSION_GROUP_BUS", ligne.getId(),
                "Constat du bus " + ligne.getRegistration() + " (dossier "
                        + dossier.getNumber() + ") : code "
                        + (ligne.getDefectCode() == null ? "non lu" : ligne.getDefectCode())
                        + ", pièce " + (ligne.getCausingPart() == null ? "non désignée"
                        : ligne.getCausingPart()), jwt);
        return response(dossier);
    }

    /**
     * Cocher une ligne de la fiche atelier. Le retour n'est jamais bloqué par une
     * ligne oubliée : elle reste rouge dans le dossier, et l'atelier la voit.
     */
    @Transactional
    public DossierResponse cocher(String id, String itemId, CocherRequest request,
                                  Jwt jwt, Authentication auth) {
        MissionGroupDossier dossier = find(id);
        requireOperator(dossier, jwt, auth);
        if (dossier.getCancelledAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est annulé : "
                            + "la fiche ne se coche plus.");
        }
        MissionGroupChecklistItem item = checklist.findByDossierIdOrderByPositionAsc(id).stream()
                .filter(i -> Objects.equals(i.getId(), itemId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cette ligne n'appartient pas à la fiche du dossier "
                                + dossier.getNumber() + "."));
        item.setDone(request.done());
        item.setDoneAt(request.done() ? LocalDateTime.now() : null);
        item.setDoneBy(request.done() ? acteur(jwt) : null);
        checklist.save(item);

        audit.record("UPDATE", "MISSION_GROUP_CHECKLIST", item.getId(),
                "Fiche du dossier " + dossier.getNumber() + " : « " + item.getLabel() + " » "
                        + (item.isDone() ? "cochée" : "décochée"), jwt);
        return response(dossier);
    }

    // ------------------------------------------------------------------- outils

    private MissionGroupDossier find(String id) {
        return dossiers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ce dossier n'existe pas."));
    }

    private AppUser findTechnician(String rawUserId) {
        UUID compte;
        try {
            compte = UUID.fromString(trim(rawUserId));
        } catch (IllegalArgumentException | NullPointerException pasUnCompte) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'homme affecté n'est pas un compte du portail : rechoisissez-le dans la liste.");
        }
        AppUser homme = users.findByIdForUpdate(compte)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce compte n'existe pas : rechoisissez l'homme dans la liste."));
        if (homme.getRole() != Role.TECHNICIEN || !homme.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'homme affecté doit être un technicien actif : rechoisissez-le dans la liste.");
        }
        return homme;
    }

    private ServiceVehicle findVehicle(String rawVehicleId) {
        ServiceVehicle voiture = serviceVehicles.findByIdForUpdate(trim(rawVehicleId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cette voiture de déplacement n'existe pas : rechoisissez-la dans la liste."));
        if (!voiture.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La voiture « " + voiture.getRegistration() + " » n'est plus en service : "
                            + "rechoisissez-en une autre.");
        }
        return voiture;
    }

    /**
     * Tient l'homme puis la voiture jusqu'à la validation, toujours dans cet
     * ordre : deux préparations croisées ne s'attendent pas en rond. Un compte
     * disparu ne se tient pas — plus personne ne peut l'affecter.
     */
    private void verrouillerAffectation(String technicianUserId, String serviceVehicleId) {
        if (technicianUserId != null) {
            try {
                users.findByIdForUpdate(UUID.fromString(technicianUserId));
            } catch (IllegalArgumentException compteEfface) {
                // Le dossier garde un nom, pas une ligne à verrouiller.
            }
        }
        if (serviceVehicleId != null) {
            serviceVehicles.findByIdForUpdate(serviceVehicleId);
        }
    }

    /**
     * Le client d'un dossier, tel qu'il sera recopié sur le papier : soit celui du
     * portefeuille (ses coordonnées sont relues sur sa fiche, sauf celles que
     * l'atelier a corrigées pour ce déplacement), soit un client saisi à la main
     * quand il n'est pas dans la liste. Dans ce cas le nom est obligatoire : sans
     * lui, le dossier ne se relit pas. L'adresse et le téléphone, eux, sont ce
     * qui permet à l'homme d'appeler et de tracer son itinéraire.
     */
    private ClientDuDossier clientDuDossier(DossierRequest request) {
        String clientId = trim(request.clientId());
        String adresse = trim(request.clientAddress());
        String ville = trim(request.clientCity());
        String telephone = trim(request.clientPhone());
        if (clientId != null) {
            Client client = clients.findById(clientId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Ce client n'est pas au portefeuille : rechoisissez-le dans la liste."));
            return new ClientDuDossier(clientId, client.getName(),
                    adresse != null ? adresse : trim(client.getAddress()),
                    ville != null ? ville : trim(client.getCity()),
                    telephone != null ? telephone : trim(client.getPhone()));
        }
        String nom = trim(request.clientName());
        if (nom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choisissez le client dans le portefeuille ou écrivez son nom : "
                            + "un dossier sans client ne se relit pas.");
        }
        return new ClientDuDossier(null, nom, adresse, ville, telephone);
    }

    /** Le client recopié sur le dossier ; {@code id} est vide pour un client hors portefeuille. */
    private record ClientDuDossier(String id, String name, String address, String city, String phone) {}

    /** Une ligne de bus validée : le bus du portefeuille, absent quand la plaque est saisie à la main. */
    private record LigneBus(Vehicle vehicle, String registration, String description, String diagnosis) {}

    /**
     * Les bus d'un dossier : chacun est soit choisi dans le portefeuille du client,
     * soit désigné par son immatriculation saisie à la main (seul choix possible
     * pour un client hors portefeuille, qui n'a pas de bus en fiche). Chacun a son problème
     * écrit et n'apparaît qu'une fois (comparé par plaque normalisée). L'écran
     * propose les bus du client ; le serveur le revérifie, parce qu'une requête
     * ne se croit pas.
     */
    private List<LigneBus> busesDuClient(String clientId, List<BusLineRequest> demandes) {
        if (demandes == null || demandes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un dossier groupe porte au moins un bus : ajoutez la ligne du bus travaillé.");
        }
        List<LigneBus> choisis = new ArrayList<>();
        Set<String> dejaPris = new HashSet<>();
        for (BusLineRequest ligne : demandes) {
            String description = trim(ligne.description());
            if (description == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Chaque bus a son problème écrit : sans lui, le dossier ne dit pas ce qu'on va faire.");
            }
            String busId = trim(ligne.vehicleId());
            Vehicle bus = null;
            String plaque;
            if (busId != null) {
                bus = vehicles.findById(busId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Ce bus n'est pas au portefeuille : rechoisissez-le dans la liste."));
                if (clientId == null || !Objects.equals(clientId, bus.getClientId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le bus « " + bus.getRegistration() + " » n'appartient pas à ce client : "
                                    + "le dossier ne mélange pas deux portefeuilles.");
                }
                plaque = bus.getRegistration();
            } else {
                plaque = trim(ligne.registration());
                if (plaque == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Chaque ligne porte son véhicule : choisissez-le dans ceux du client "
                                    + "ou écrivez son immatriculation.");
                }
            }
            String cle = cleImmatriculation(plaque);
            if (cle.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "L'immatriculation écrite n'est pas lisible : vérifiez-la.");
            }
            if (!dejaPris.add(cle)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le même bus est écrit deux fois sur ce dossier : une ligne par bus.");
            }
            choisis.add(new LigneBus(bus, plaque, description, trim(ligne.diagnosis())));
        }
        return choisis;
    }

    /**
     * Les conflits d'affectation, sans rien refuser : un homme ou une voiture déjà
     * engagé sur la période n'empêche pas de préparer le dossier. L'écran les
     * montre en rouge avec leur motif ; le serveur se contente de les relire ici
     * (liste vide = tout est libre) pour les écrire au journal.
     */
    private List<String> conflitsAffectation(AppUser homme, ServiceVehicle voiture,
                                             LocalDateTime depart, LocalDateTime retour,
                                             String excludeId) {
        List<String> conflits = new ArrayList<>();
        Occupation prise = occupation(depart, retour, excludeId);
        String hommeMotif = prise.hommes().get(homme.getId().toString());
        if (hommeMotif != null) {
            conflits.add("Technicien « " + homme.getName() + " » : " + hommeMotif);
        }
        String voitureMotif = prise.voitures().get(voiture.getId());
        if (voitureMotif != null) {
            conflits.add("Voiture « " + voiture.getRegistration() + " » : " + voitureMotif);
        }
        return conflits;
    }

    /** Un dossier accepté malgré un conflit laisse une trace : qui, quand, et quoi. */
    private void noterConflits(MissionGroupDossier dossier, List<String> conflits,
                               String geste, Jwt jwt) {
        if (conflits.isEmpty()) {
            return;
        }
        audit.record("CONFLICT_ACCEPTED", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " " + geste + " malgré : "
                        + String.join(" ; ", conflits), jwt);
    }

    /**
     * La période estimée se tient : le retour prévu vient après le départ prévu,
     * et le compteur estimé ne recule pas. Une absence de retour est acceptée —
     * un dossier ancien n'en a pas — mais l'écran, lui, le demande pour calculer
     * les disponibilités.
     */
    private void requirePeriodeCoherente(LocalDateTime depart, LocalDateTime retour,
                                         Long kmDepart, Long kmRetour) {
        if (retour != null && !retour.isAfter(depart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le retour estimé doit venir après le départ estimé : "
                            + "l'atelier ne prévoit pas un retour avant le départ.");
        }
        if (kmDepart != null && kmRetour != null && kmRetour < kmDepart) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le kilométrage estimé de retour (" + kmRetour + " km) est inférieur à celui "
                            + "du départ (" + kmDepart + " km) : une prévision ne recule pas.");
        }
    }

    private MissionCallChannel canal(String raw) {
        try {
            return MissionCallChannel.parse(raw);
        } catch (IllegalArgumentException inconnu) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, inconnu.getMessage());
        }
    }

    /** Préparer un dossier : l'atelier seul. */
    private void requirePreparer(Authentication auth) {
        if (!Access.usesMissionGroupe(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La mission groupe est préparée par le guichet et l'atelier : "
                            + "ce compte n'a pas le droit d'ouvrir un dossier.");
        }
    }

    private void requireReader(Authentication auth) {
        if (!Access.readsMissionGroupe(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La mission groupe est réservée au guichet, à l'atelier et au technicien affecté.");
        }
    }

    /** L'atelier lit tout ; le technicien ne lit que le dossier où il est l'homme affecté. */
    private void requireReaderOf(MissionGroupDossier dossier, Jwt jwt, Authentication auth) {
        requireReader(auth);
        if (!Access.isTechnician(auth)) {
            return;
        }
        if (!Objects.equals(subject(jwt), dossier.getTechnicianUserId())) {
            // Un technicien n'a pas à savoir que le dossier d'un autre existe.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ce dossier n'existe pas.");
        }
    }

    /** Pointer départ ou retour : l'atelier, ou le technicien affecté à ce dossier. */
    private void requireOperator(MissionGroupDossier dossier, Jwt jwt, Authentication auth) {
        if (Access.usesMissionGroupe(auth)) {
            return;
        }
        if (Access.isTechnician(auth)
                && Objects.equals(subject(jwt), dossier.getTechnicianUserId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Seul le technicien affecté à ce dossier — ou l'atelier — pointe le départ et le retour.");
    }

    private void requireRetourCoherent(LocalDateTime depart, LocalDateTime retour,
                                       Long kmDepart, Long kmRetour, String numero) {
        String dossier = numero == null ? "ce dossier" : "le dossier " + numero;
        if (retour.isBefore(depart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le retour de " + dossier + " ne peut pas précéder son départ.");
        }
        // Le compteur ne s'oppose que si les deux chiffres ont été relevés : la
        // photo prouve le passage, le chiffre n'est qu'une lecture facultative.
        if (kmDepart != null && kmRetour != null && kmRetour < kmDepart) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le kilométrage de retour de " + dossier + " (" + kmRetour
                            + " km) est inférieur à celui du départ (" + kmDepart
                            + " km) : le compteur ne recule pas.");
        }
    }

    /** Le départ et le retour se prouvent par la voiture et son compteur, pas par un chiffre tapé. */
    private void requirePreuveDeLaVoiture(String dossierId, MissionGroupPhotoAngle vehicule,
                                          MissionGroupPhotoAngle compteur, String moment,
                                          String numero) {
        String manquantes = null;
        if (!photos.existsByDossierIdAndAngle(dossierId, vehicule)) {
            manquantes = "la voiture";
        }
        if (!photos.existsByDossierIdAndAngle(dossierId, compteur)) {
            manquantes = manquantes == null ? "le compteur" : manquantes + " et le compteur";
        }
        if (manquantes != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le " + moment + " du dossier " + numero + " se prouve par deux photos : "
                            + "il manque " + manquantes + ". Photographiez la voiture et son "
                            + "compteur, puis enregistrez le " + moment + ".");
        }
    }

    /** Un constat de bus se prouve par le bus avant et le bus après le travail. */
    private void requirePreuveDuBus(String dossierId, String busId, String plaque) {
        boolean avant = photos.existsByDossierIdAndBusIdAndAngle(dossierId, busId,
                MissionGroupPhotoAngle.BUS_AVANT);
        boolean apres = photos.existsByDossierIdAndBusIdAndAngle(dossierId, busId,
                MissionGroupPhotoAngle.BUS_APRES);
        if (!avant || !apres) {
            String manquantes = !avant && !apres ? "le bus avant et le bus après le travail"
                    : !avant ? "le bus avant le travail" : "le bus après le travail";
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le constat du bus " + plaque + " se prouve par deux photos : il manque "
                            + manquantes + ". Prenez les clichés, puis enregistrez le constat.");
        }
    }

    private static String acteur(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String nom = jwt.getClaimAsString("name");
        return nom != null ? nom : jwt.getClaimAsString("email");
    }

    private DossierResponse response(MissionGroupDossier dossier) {
        return response(dossier,
                buses.findByDossierIdOrderByPositionAsc(dossier.getId()),
                photos.findByDossierIdOrderByUploadedAtAsc(dossier.getId()),
                checklist.findByDossierIdOrderByPositionAsc(dossier.getId()),
                attachments.findByDossierIdOrderByUploadedAtAsc(dossier.getId()));
    }

    private DossierResponse response(MissionGroupDossier dossier, List<MissionGroupBus> lignes,
                                     List<MissionGroupPhoto> preuves,
                                     List<MissionGroupChecklistItem> fiche,
                                     List<MissionGroupAttachment> pieces) {
        boolean rentre = dossier.getReturnedAt() != null;
        boolean parti = dossier.getDepartedAt() != null;
        boolean annule = dossier.getCancelledAt() != null;
        boolean constatComplet = !lignes.isEmpty() && lignes.stream().allMatch(this::constatEcrit);
        String state = annule ? "annule" : rentre ? "rentre" : parti ? "en_deplacement" : "prevu";
        String stateLabel = annule ? "Annulée"
                : rentre ? "Rentré — disponible"
                : parti ? "En déplacement" : "Départ prévu";
        // Les étapes du papier, déduites : rien ne s'écrit deux fois.
        String status = annule ? "annulee" : rentre ? "cloturee" : !parti ? "planifiee"
                : constatComplet ? "constat" : "en_cours";
        String statusLabel = annule ? "Annulée" : rentre ? "Clôturée" : !parti ? "Planifiée"
                : constatComplet ? "Constat renseigné" : "En cours";
        return new DossierResponse(
                dossier.getId(), dossier.getNumber(), dossier.getClientId(), dossier.getClientName(),
                dossier.getClientAddress(), dossier.getClientCity(), dossier.getClientPhone(),
                dossier.getCallChannel() == null ? null
                        : dossier.getCallChannel().name().toLowerCase(Locale.ROOT),
                dossier.getCallChannel() == null ? null : dossier.getCallChannel().label(),
                format(dossier.getPlannedDepartureAt()), format(dossier.getPlannedReturnAt()),
                dossier.getPlannedDepartureMileage(), dossier.getPlannedReturnMileage(),
                dossier.getTechnicianUserId(), dossier.getTechnicianName(),
                dossier.getServiceVehicleId(), dossier.getServiceVehicleRegistration(),
                format(dossier.getDepartedAt()), dossier.getDepartureMileage(),
                format(dossier.getReturnedAt()), dossier.getReturnMileage(), traveled(dossier),
                state, stateLabel, status, statusLabel,
                format(dossier.getCancelledAt()), dossier.getCancelReason(), dossier.getCancelledBy(),
                lignes.stream().map(b -> new BusResponse(b.getId(), b.getVehicleId(),
                        b.getRegistration(), b.getDescription(), b.getDiagnosis(),
                        b.getDefectCode(), b.getCausingPart(), b.getObservations())).toList(),
                preuves.stream().map(PhotoResponse::from).toList(),
                fiche.stream().map(i -> new ChecklistItemResponse(i.getId(), i.getPosition(),
                        i.getLabel(), i.isDone(), format(i.getDoneAt()), i.getDoneBy())).toList(),
                fiche.stream().anyMatch(i -> !i.isDone()),
                pieces.stream().map(AttachmentResponse::from).toList(),
                dossier.getCreatedBy(), format(dossier.getCreatedAt()));
    }

    /** Le constat d'un bus est écrit quand les trois mots le sont. */
    private boolean constatEcrit(MissionGroupBus ligne) {
        return trim(ligne.getDefectCode()) != null
                && trim(ligne.getCausingPart()) != null
                && trim(ligne.getObservations()) != null;
    }

    private Long traveled(MissionGroupDossier dossier) {
        if (dossier.getReturnedAt() == null || dossier.getReturnMileage() == null
                || dossier.getDepartureMileage() == null) {
            return null;
        }
        return dossier.getReturnMileage() - dossier.getDepartureMileage();
    }

    /** ISO sans nanosecondes : Angular lit la même heure que celle écrite sur le papier. */
    private static String format(LocalDateTime moment) {
        return moment == null ? null : moment.withNano(0).toString();
    }

    /**
     * L'annonce part après la validation, pas pendant : un envoi lent ne retient
     * ni la transaction ni le verrou de l'homme et de la voiture. Hors transaction
     * (tests unitaires), le geste s'exécute tout de suite.
     */
    private void apresValidation(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * Le téléphone de l'homme affecté sonne : un dossier lui arrive. Le texte
     * reste court — ce que le téléphone affiche fermé — et le toucher ouvre le
     * dossier lui-même, pas une liste où il faudrait le retrouver.
     */
    private void prevenirAffectation(MissionGroupDossier dossier, boolean reaffectation) {
        if (dossier.getTechnicianUserId() == null) {
            return;
        }
        push.annoncer(dossier.getTechnicianUserId(), new PushNotificationService.Message(
                reaffectation
                        ? "Dossier " + dossier.getNumber() + " vous est affecté"
                        : "Nouveau dossier " + dossier.getNumber(),
                dossier.getClientName() + " — départ prévu " + quand(dossier.getPlannedDepartureAt()) + ".",
                "/?dossier=" + dossier.getId(),
                "dossier-" + dossier.getId()));
    }

    /**
     * Le dossier n'aura pas lieu : le téléphone de l'homme affecté le dit, avec
     * le même repère que l'affectation — la notification du dossier est
     * remplacée, pas doublée. Sans cette annonce, l'homme partirait pour rien.
     */
    private void prevenirAnnulation(MissionGroupDossier dossier) {
        if (dossier.getTechnicianUserId() == null) {
            return;
        }
        push.annoncer(dossier.getTechnicianUserId(), new PushNotificationService.Message(
                "Dossier " + dossier.getNumber() + " annulé",
                dossier.getClientName() + " — " + dossier.getCancelReason(),
                "/?dossier=" + dossier.getId(),
                "dossier-" + dossier.getId()));
    }

    /** Le dossier revient : le même repère remplace l'annonce d'annulation. */
    private void prevenirReouverture(MissionGroupDossier dossier) {
        if (dossier.getTechnicianUserId() == null) {
            return;
        }
        push.annoncer(dossier.getTechnicianUserId(), new PushNotificationService.Message(
                "Dossier " + dossier.getNumber() + " rouvert",
                dossier.getClientName() + " — départ prévu " + quand(dossier.getPlannedDepartureAt()) + ".",
                "/?dossier=" + dossier.getId(),
                "dossier-" + dossier.getId()));
    }

    /** « aujourd'hui à 08:00 », « le 19/11 à 08:00 » : la même phrase qu'à l'écran. */
    private static String quand(LocalDateTime moment) {
        if (moment == null) {
            return "à préciser";
        }
        String heure = String.format("%02d:%02d", moment.getHour(), moment.getMinute());
        if (moment.toLocalDate().equals(LocalDate.now())) {
            return "aujourd'hui à " + heure;
        }
        return "le " + String.format("%02d/%02d", moment.getDayOfMonth(), moment.getMonthValue())
                + " à " + heure;
    }

    private static String subject(Jwt jwt) {
        return jwt == null ? null : jwt.getSubject();
    }

    private static String trim(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    /**
     * La plaque telle qu'on la compare : majuscules, sans accents, sans espaces ni
     * tirets. C'est une clé de lecture, pas une réécriture de ce que l'agent a tapé.
     */
    static String cleImmatriculation(String registration) {
        if (registration == null) {
            return "";
        }
        String sansAccents = Normalizer.normalize(registration, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sansAccents.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    private static long nombre(String numero) {
        if (numero == null) {
            return 0;
        }
        try {
            return Long.parseLong(numero.trim());
        } catch (NumberFormatException pasUnNombre) {
            return 0;
        }
    }

    private record Ligne(MissionGroupBus bus, MissionGroupDossier dossier) {}
}
