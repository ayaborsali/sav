package tn.higer.sav_backend.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusDiagnosisRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusLineRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.ChecklistItemResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.CocherRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.ConstatRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DepartRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.AvailabilityResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.HistoryRow;
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
 *    ceux du client, problème écrit bus par bus, homme et voiture affectés,
 *    départ prévu. Le numéro est proposé (1740, 1741…) mais l'agent peut le
 *    remplacer par celui du registre papier ; un numéro déjà pris est refusé ;
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
                               AuditService audit) {
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
        String clientId = trim(request.clientId());
        if (clientId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choisissez le client dans le portefeuille : un dossier sans client ne se relit pas.");
        }
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce client n'est pas au portefeuille : rechoisissez-le dans la liste."));

        AppUser homme = findTechnician(request.technicianUserId());
        ServiceVehicle voiture = findVehicle(request.serviceVehicleId());
        requirePeriodeCoherente(request.plannedDepartureAt(), request.plannedReturnAt(),
                request.plannedDepartureMileage(), request.plannedReturnMileage());
        requireHommeEtVoitureLibres(homme, voiture, request.plannedDepartureAt(),
                request.plannedReturnAt(), null);
        MissionCallChannel canal = canal(request.callChannel());

        List<Vehicle> choisis = busesDuClient(clientId, request.buses());

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
        dossier.setClientName(client.getName());
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

        List<MissionGroupBus> ecrites = ecrireBuses(dossier, choisis, request.buses(), now);

        List<MissionGroupChecklistItem> fiche = ecrireFiche(dossier.getId(), request.checklist(), now);

        audit.record("CREATE", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " (" + ecrites.size() + " bus, fiche atelier : "
                        + fiche.size() + " lignes) pour " + client.getName() + ", "
                        + homme.getName() + " et la voiture " + voiture.getRegistration(), jwt);
        return response(dossier, ecrites, List.of(), fiche, List.of());
    }

    /**
     * Modifier un dossier encore à l'atelier : le client, ses bus, l'homme, la
     * voiture et la période estimée se réécrivent tant que le départ réel n'est
     * pas pointé. Une fois parti, le papier est l'histoire du déplacement et ne
     * se réécrit plus ; une fois photographié, il porte des preuves qu'une
     * réécriture laisserait orphelines.
     */
    @Transactional
    public DossierResponse modifier(String id, DossierRequest request, Jwt jwt, Authentication auth) {
        requirePreparer(auth);
        MissionGroupDossier dossier = find(id);
        if (dossier.getDepartedAt() != null || dossier.getReturnedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " est déjà parti : "
                            + "le papier préparé ne se modifie plus.");
        }
        if (!photos.findByDossierIdOrderByUploadedAtAsc(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getNumber() + " porte déjà des photos : "
                            + "il ne se modifie plus.");
        }
        String clientId = trim(request.clientId());
        if (clientId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choisissez le client dans le portefeuille : un dossier sans client ne se relit pas.");
        }
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce client n'est pas au portefeuille : rechoisissez-le dans la liste."));

        AppUser homme = findTechnician(request.technicianUserId());
        ServiceVehicle voiture = findVehicle(request.serviceVehicleId());
        requirePeriodeCoherente(request.plannedDepartureAt(), request.plannedReturnAt(),
                request.plannedDepartureMileage(), request.plannedReturnMileage());
        requireHommeEtVoitureLibres(homme, voiture, request.plannedDepartureAt(),
                request.plannedReturnAt(), id);
        MissionCallChannel canal = canal(request.callChannel());
        List<Vehicle> choisis = busesDuClient(clientId, request.buses());

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
        dossier.setClientName(client.getName());
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
        List<MissionGroupBus> ecrites = ecrireBuses(dossier, choisis, request.buses(), now);
        List<MissionGroupChecklistItem> fiche = ecrireFiche(id, request.checklist(), now);

        audit.record("UPDATE", "MISSION_GROUP_DOSSIER", dossier.getId(),
                "Dossier " + dossier.getNumber() + " modifié : " + ecrites.size()
                        + " bus, fiche atelier " + fiche.size() + " lignes, pour "
                        + client.getName() + ", " + homme.getName() + " et la voiture "
                        + voiture.getRegistration(), jwt);
        return response(dossier, ecrites, List.of(), fiche,
                attachments.findByDossierIdOrderByUploadedAtAsc(id));
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
                new ArrayList<>(prise.voitures().keySet()));
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
     * la fois »), et la voiture n'est reprise qu'au retour pointé.
     */
    private Occupation occupation(LocalDateTime depart, LocalDateTime retour, String excludeId) {
        Map<String, String> hommes = new LinkedHashMap<>();
        Map<String, String> voitures = new LinkedHashMap<>();

        for (MissionGroupDossier dossier : dossiers.findAll()) {
            if (dossier.getReturnedAt() != null) {
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
    private List<MissionGroupBus> ecrireBuses(MissionGroupDossier dossier, List<Vehicle> choisis,
                                              List<BusLineRequest> demandes, LocalDateTime now) {
        List<MissionGroupBus> ecrites = new ArrayList<>();
        int ordre = 1;
        for (Vehicle bus : choisis) {
            BusLineRequest ligne = demandes.stream()
                    .filter(l -> Objects.equals(trim(l.vehicleId()), bus.getId()))
                    .findFirst().orElseThrow();
            MissionGroupBus mission = new MissionGroupBus();
            mission.setId("mgb-" + UUID.randomUUID());
            mission.setDossierId(dossier.getId());
            mission.setPosition(ordre++);
            mission.setVehicleId(bus.getId());
            // La plaque est recopiée du portefeuille : l'écran ne la retape jamais.
            mission.setRegistration(bus.getRegistration());
            mission.setRegistrationKey(cleImmatriculation(bus.getRegistration()));
            mission.setDescription(trim(ligne.description()));
            mission.setDiagnosis(trim(ligne.diagnosis()));
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
        AppUser homme = users.findById(compte)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce compte n'existe pas : rechoisissez l'homme dans la liste."));
        if (homme.getRole() != Role.TECHNICIEN || !homme.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'homme affecté doit être un technicien actif : rechoisissez-le dans la liste.");
        }
        return homme;
    }

    private ServiceVehicle findVehicle(String rawVehicleId) {
        ServiceVehicle voiture = serviceVehicles.findById(trim(rawVehicleId))
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
     * Les bus d'un dossier, pris dans le portefeuille du client : chacun a son
     * problème écrit et n'apparaît qu'une fois. L'écran ne propose que ceux du
     * client ; le serveur le revérifie, parce qu'une requête ne se croit pas.
     */
    private List<Vehicle> busesDuClient(String clientId, List<BusLineRequest> demandes) {
        if (demandes == null || demandes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un dossier groupe porte au moins un bus : ajoutez la ligne du bus travaillé.");
        }
        List<Vehicle> choisis = new ArrayList<>();
        Set<String> dejaPris = new HashSet<>();
        for (BusLineRequest ligne : demandes) {
            String busId = trim(ligne.vehicleId());
            if (busId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Chaque ligne porte son bus, choisi dans ceux du client.");
            }
            if (trim(ligne.description()) == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Chaque bus a son problème écrit : sans lui, le dossier ne dit pas ce qu'on va faire.");
            }
            if (!dejaPris.add(busId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le même bus est écrit deux fois sur ce dossier : une ligne par bus.");
            }
            Vehicle bus = vehicles.findById(busId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Ce bus n'est pas au portefeuille : rechoisissez-le dans la liste."));
            if (!Objects.equals(clientId, bus.getClientId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le bus « " + bus.getRegistration() + " » n'appartient pas à ce client : "
                                + "le dossier ne mélange pas deux portefeuilles.");
            }
            choisis.add(bus);
        }
        return choisis;
    }

    /**
     * Le dernier verrou : l'écran ne propose que les libres, mais deux guichets
     * peuvent lire la même liste au même moment. Le serveur refuse donc le
     * dossier qui prendrait un homme ou une voiture déjà engagés.
     */
    private void requireHommeEtVoitureLibres(AppUser homme, ServiceVehicle voiture,
                                             LocalDateTime depart, LocalDateTime retour,
                                             String excludeId) {
        Occupation prise = occupation(depart, retour, excludeId);
        String hommeMotif = prise.hommes().get(homme.getId().toString());
        if (hommeMotif != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le technicien « " + homme.getName() + " » n'est pas libre : " + hommeMotif
                            + ". Choisissez un autre homme ou une autre période.");
        }
        String voitureMotif = prise.voitures().get(voiture.getId());
        if (voitureMotif != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La voiture « " + voiture.getRegistration() + " » n'est pas libre : "
                            + voitureMotif + ". Choisissez-en une autre.");
        }
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
        boolean constatComplet = !lignes.isEmpty() && lignes.stream().allMatch(this::constatEcrit);
        String state = rentre ? "rentre" : parti ? "en_deplacement" : "prevu";
        String stateLabel = rentre ? "Rentré — disponible"
                : parti ? "En déplacement" : "Départ prévu";
        // Les quatre étapes du papier, déduites : rien ne s'écrit deux fois.
        String status = rentre ? "cloturee" : !parti ? "planifiee"
                : constatComplet ? "constat" : "en_cours";
        String statusLabel = rentre ? "Clôturée" : !parti ? "Planifiée"
                : constatComplet ? "Constat renseigné" : "En cours";
        return new DossierResponse(
                dossier.getId(), dossier.getNumber(), dossier.getClientId(), dossier.getClientName(),
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
