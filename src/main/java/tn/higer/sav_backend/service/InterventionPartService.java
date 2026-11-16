package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.InterventionResponse;
import tn.higer.sav_backend.dto.InterventionPartDtos;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandRequest;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandResponse;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartReceptionRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionEventSource;
import tn.higer.sav_backend.model.InterventionEventType;
import tn.higer.sav_backend.model.InterventionPartRequest;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.repository.InterventionPartRequestRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.security.Access;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * La pièce demandée sur une sortie — la marche qui manquait entre « le technicien a
 * vu quelle pièce est morte » et « le guichet la commande ».
 *
 * Trois règles, et c'est ce qui fait de ce module autre chose qu'un formulaire :
 *  1. la demande se pose <b>sur la sortie</b>, dehors, devant le bus. Le bus, le
 *     dossier et la mission sont repris de la sortie par le serveur, jamais retapés :
 *     une case de plus à l'écran est une case où l'on peut dire deux choses
 *     différentes ;
 *  2. l'état ne se saisit pas ({@link PartRequestState}) : il se déduit des gestes, et
 *     chaque geste a sa route — la demande ouvre « À commander », la commande du
 *     guichet ouvre « Commandée », la réception du magasin calcule « Partielle » ou
 *     « Livrée » selon le cumul reçu en face du cumul commandé ;
 *  3. qui demande s'écrit : l'adresse du compte reste sur la ligne, comme au journal
 *     de la sortie, pour qu'un litige de commande se remonte à un nom.
 *
 * Le lecteur passe par {@link InterventionService#full} plutôt que de reformater
 * sortie, dossier et bus lui-même : le module Dépannage est seul à dire à quoi
 * ressemble une ligne de sortie, et une seconde mise en page dériverait.
 */
@Service
public class InterventionPartService {

    /** Les deux statuts où l'homme est dehors et peut voir une pièce morte. */
    private static final List<InterventionStatus> SUR_PLACE =
            List.of(InterventionStatus.ON_SITE, InterventionStatus.IN_PROGRESS);

    private final InterventionPartRequestRepository pieces;
    private final InterventionRepository interventions;
    private final MissionRepository missions;
    private final InterventionService sortie;
    private final MissionService demandes;
    private final AuditService audit;

    public InterventionPartService(InterventionPartRequestRepository pieces,
                                  InterventionRepository interventions,
                                  MissionRepository missions,
                                  InterventionService sortie,
                                  MissionService demandes,
                                  AuditService audit) {
        this.pieces = pieces;
        this.interventions = interventions;
        this.missions = missions;
        this.sortie = sortie;
        this.demandes = demandes;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- lecture

    /** Les pièces demandées pendant cette sortie, dans l'ordre où elles ont été vues. */
    @Transactional(readOnly = true)
    public List<PartDemandResponse> pourSortie(String interventionId, Authentication auth, Jwt jwt) {
        Intervention i = sortie.requireVisible(interventionId, auth, jwt);
        return lignes(pieces.findByInterventionIdOrderByCreatedAtAsc(i.getId()),
                Map.of(i.getId(), i));
    }

    /**
     * Les pièces que cette réclamation attend. Lecture d'écran du dossier : le guichet
     * doit voir « une pièce commandée le 12, rien reçu » sans relire les sorties une
     * par une — et sans que le poste ait à retenir quel numéro de DP- va avec quel
     * dossier.
     */
    @Transactional(readOnly = true)
    public List<PartDemandResponse> pourDossier(String savCaseId, Authentication auth, Jwt jwt) {
        sortie.requireMember(auth);
        List<InterventionPartRequest> rows = pieces.findBySavCaseIdOrderByCreatedAtAsc(savCaseId);
        List<String> sortiesVisees = rows.stream().map(InterventionPartRequest::getInterventionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, Intervention> parSortie = sortiesVisees.isEmpty() ? Map.of()
                : interventions.findAllById(sortiesVisees).stream()
                        .collect(Collectors.toMap(Intervention::getId, Function.identity()));
        return lignes(rows, parSortie);
    }

    // ---------------------------------------------------------------- écriture

    /**
     * L'homme dehors demande la pièce. Le service refuse trois choses, et chacune
     * s'écrit en français parce qu'un refus muet ne se corrige pas :
     *  - une sortie qui n'est pas sur place (la pièce se voit devant le bus, pas
     *    depuis l'atelier — une demande d'avant serait une commande au hasard) ;
     *  - une quantité qui n'est pas au moins un (rien à commander) ;
     *  - une ligne sans désignation ni raison (le guichet commanderait à l'aveugle).
     */
    @Transactional
    public PartDemandResponse demander(String interventionId, PartDemandRequest r,
                                       Jwt jwt, Authentication auth) {
        Intervention i = sortie.requireVisible(interventionId, auth, jwt);
        sortie.requireTechnicianOrManager(i, jwt, auth);
        if (!SUR_PLACE.contains(i.getStatus())) {
            boolean pasEncoreArrive = i.getStatus() == InterventionStatus.REPORTED
                    || i.getStatus() == InterventionStatus.DISPATCHED;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    pasEncoreArrive
                            ? "Une pièce se demande devant le bus : enregistrez d'abord « j'arrive » puis"
                              + " le diagnostic. Rien ne part en commande tant que l'homme n'est pas arrivé."
                            : "Cette sortie est déjà rentrée à l'atelier : une pièce qui manque encore se"
                              + " déclare sur le dossier SAV, et l'atelier rouvre une sortie pour la poser."
                              + " Une demande ajoutée après coup ne correspondrait plus à ce que vous avez vu.");
        }
        InterventionPartRequest p = new InterventionPartRequest();
        p.setId("ipr-" + UUID.randomUUID());
        remplir(p, r);
        // Les trois liens viennent de la sortie, et de personne d'autre.
        p.setInterventionId(i.getId());
        p.setVehicleId(i.getVehicleId());
        p.setSavCaseId(i.getSavCaseId());
        p.setMissionId(missions.findByInterventionId(i.getId()).map(Mission::getId).orElse(null));
        // L'état n'est pas une case de l'écran : la demande, seule, l'ouvre.
        p.setState(PartRequestState.A_COMMANDER);
        p.setDemandedBy(qui(jwt));
        p.setCreatedAt(LocalDateTime.now());
        pieces.save(p);
        sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                "Pièce demandée sur place : " + p.getDesignation()
                        + " (réf. " + (p.getReference() == null ? "non lue sur la pièce" : p.getReference())
                        + ", " + p.getQuantityOrdered() + " à commander)");
        audit.record("CREATE", "INTERVENTION_PART", p.getId(),
                "Sortie " + i.getNumber() + " : pièce demandée — " + p.getDesignation()
                        + " x" + p.getQuantityOrdered(), jwt);
        return ligne(p, sortie.full(i));
    }

    /**
     * Rectifier sa demande <b>tant qu'elle n'est pas commandée</b>. Passé ce moment ce
     * n'est plus la même affaire : le guichet a engagé une commande chez le fournisseur
     * sur ces chiffres, et les changer ici rendrait la réception incohérente. Le refus
     * s'écrit, il ne se devine pas.
     */
    @Transactional
    public PartDemandResponse corriger(String pieceId, PartDemandRequest r,
                                       Jwt jwt, Authentication auth) {
        InterventionPartRequest p = pieceDe(pieceId);
        Intervention i = sortieRequire(p.getInterventionId());
        sortie.requireTechnicianOrManager(i, jwt, auth);
        if (p.getState() != PartRequestState.A_COMMANDER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette pièce est « " + p.getState().label() + " » : sa demande n'est plus libre."
                            + " Ce qui a été commandé se rectifie par une seconde demande, jamais en"
                            + " effaçant la première.");
        }
        remplir(p, r);
        p.setUpdatedAt(LocalDateTime.now());
        pieces.save(p);
        sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                "Demande de pièce rectifiée : " + p.getDesignation() + " x" + p.getQuantityOrdered());
        audit.record("UPDATE", "INTERVENTION_PART", p.getId(),
                "Sortie " + i.getNumber() + " : demande de pièce rectifiée — " + p.getDesignation(), jwt);
        return ligne(p, sortie.full(i));
    }

    /**
     * Le guichet passe la commande. Un clic, aucune case : la date se prend à
     * l'horloge du serveur et le nom au jeton, exactement comme au départ d'une
     * sortie. Une « date de commande » saisie à la main serait le premier endroit où
     * l'on peut faire croire que la pièce est partie trois semaines plus tôt.
     *
     * La sortie n'a plus besoin d'être dehors : la commande se passe à l'atelier,
     * souvent après le retour de l'homme. Ce qui garde la ligne, c'est son état —
     * « À commander » — pas l'endroit où elle est née.
     */
    @Transactional
    public PartDemandResponse commander(String pieceId, Jwt jwt, Authentication auth) {
        InterventionPartRequest p = pieceDe(pieceId);
        Intervention i = sortieRequire(p.getInterventionId());
        requireGuichet(auth);
        if (p.getState() != PartRequestState.A_COMMANDER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette pièce est déjà « " + p.getState().label() + " » : la commande a été passée,"
                            + " ce qui reste à faire est la réception, pas une seconde commande.");
        }
        LocalDateTime now = LocalDateTime.now();
        p.setState(PartRequestState.COMMANDEE);
        p.setOrderedAt(now);
        p.setOrderedBy(qui(jwt));
        p.setUpdatedAt(now);
        pieces.save(p);
        sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                "Commande passée au guichet : " + p.getDesignation() + " x" + p.getQuantityOrdered()
                        + " (réf. " + (p.getReference() == null ? "non lue sur la pièce" : p.getReference())
                        + ")");
        audit.record("UPDATE", "INTERVENTION_PART", p.getId(),
                "Sortie " + i.getNumber() + " : commande passée — " + p.getDesignation()
                        + " x" + p.getQuantityOrdered(), jwt);
        return ligne(p, sortie.full(i));
    }

    /**
     * Le magasin réceptionne ce qui est arrivé, et <b>l'état se calcule ici</b> :
     * reçu inférieur à commandé = « Partielle », reçu égal = « Livrée ». Les deux
     * mots ne sont écrits nulle part ailleurs, et rien de l'écran ne les envoie.
     *
     * Trois refus, chacun pour une faute différente :
     *  - réceptionner avant d'avoir commandé : le magasin n'a rien à recevoir, et le
     *    dossier passerait à « Livrée » sans qu'un ordre existe ;
     *  - recevoir plus que ce qui est commandé : le chiffre de trop se retrouve un
     *    jour dans un papier envoyé à Higer ;
     *  - une ligne déjà livrée ou posée : la suite de l'histoire n'est plus ici.
     *
     * « Partielle » laisse la porte ouverte : la seconde livraison s'ajoute au cumul,
     * et c'est le cumul qui décide de l'état.
     */
    @Transactional
    public PartDemandResponse receptionner(String pieceId, PartReceptionRequest r,
                                           Jwt jwt, Authentication auth) {
        InterventionPartRequest p = pieceDe(pieceId);
        Intervention i = sortieRequire(p.getInterventionId());
        requireGuichet(auth);
        if (p.getState() == PartRequestState.A_COMMANDER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette pièce n'est pas encore commandée : le guichet passe d'abord la commande, sans"
                            + " quoi il n'y a rien à réceptionner.");
        }
        if (p.getState() == PartRequestState.LIVREE || p.getState() == PartRequestState.POSEE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette pièce est « " + p.getState().label() + " » : tout ce qui était commandé est"
                            + " arrivé. Ce qui manque encore se demande par une nouvelle ligne, sur une"
                            + " sortie devant le bus.");
        }
        if (r == null || r.quantity() == null || r.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Indiquez la quantité arrivée à ce passage : à zéro, cette écriture ne dit rien.");
        }
        int deja = p.getQuantityReceived() == null ? 0 : p.getQuantityReceived();
        int cumul = deja + r.quantity();
        if (cumul > p.getQuantityOrdered()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vous ne pouvez pas recevoir plus que ce qui est commandé : " + p.getQuantityOrdered()
                            + " commandée(s), " + deja + " déjà reçue(s), " + r.quantity()
                            + " de plus. Relisez le bon de livraison.");
        }
        LocalDateTime now = LocalDateTime.now();
        p.setQuantityReceived(cumul);
        p.setReceivedAt(now);
        p.setReceivedBy(qui(jwt));
        // Le seul endroit du portail où ces deux mots s'écrivent.
        p.setState(cumul == p.getQuantityOrdered() ? PartRequestState.LIVREE : PartRequestState.PARTIELLE);
        p.setUpdatedAt(now);
        pieces.save(p);
        sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                "Réception au magasin : " + r.quantity() + " sur " + p.getQuantityOrdered()
                        + " commandées pour " + p.getDesignation()
                        + " — « " + p.getState().label() + " »");
        audit.record("UPDATE", "INTERVENTION_PART", p.getId(),
                "Sortie " + i.getNumber() + " : pièce réceptionnée — " + p.getDesignation() + " ("
                        + cumul + "/" + p.getQuantityOrdered() + ", " + p.getState().label() + ")", jwt);
        // « Partielle » n'ouvre rien : il manque encore une partie de la commande, et
        // une mission de pose ouverte sur un magasin à moitié livré serait une sortie
        // pour rien. Le geste ne se déclenche que sur « Livrée ».
        if (p.getState() == PartRequestState.LIVREE) {
            ouvrirLaMissionDePose(p, i, jwt);
        }
        return ligne(p, sortie.full(i));
    }

    /**
     * La pièce est toute arrivée : le serveur ouvre la mission « changer la pièce ».
     * Le lien est écrit sur la ligne ({@code fitting_mission_id}) et nulle part
     * ailleurs : c'est lui qui, à la clôture de la seconde sortie, dira laquelle de ces
     * pièces est « Posée ». La garde contre une seconde mission tient à l'état de la
     * ligne, pas ici : une fois « Livrée », elle ne se réceptionne plus.
     *
     * <p>Deux cas où la mission ne s'ouvre pas, et les deux s'écrivent au journal :
     *  - la ligne n'a pas de réclamation au dos — une sortie née d'un rappel de
     *    forfait. Une mission répond à une demande, et il n'y en a pas à laquelle
     *    répondre ;
     *  - le dossier rattaché n'existe plus. La base de l'atelier a déjà douze sorties
     *    qui portent l'identifiant d'un dossier effacé ({@code DELETE /api/sav-cases}
     *    ne nettoie aucun pointeur). Dans les deux cas, la réception reste acquise :
     *    ce que le magasin a écrit est la quantité et l'état, pas la mission. Refuser
     *    la livraison parce qu'un lien est cassé serait punir l'homme qui reçoit la
     *    boîte d'un papier qu'un autre a supprimé — et la boîte resterait au magasin.
     *
     * Rien n'est inventé pour remplir la case.</p>
     */
    private void ouvrirLaMissionDePose(InterventionPartRequest p, Intervention i, Jwt jwt) {
        if (p.getSavCaseId() == null) {
            sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                    "Pièce livrée au magasin, mais cette sortie n'a pas de réclamation au dossier :"
                            + " la mission « changer la pièce » ne s'est pas ouverte toute seule."
                            + " Ouvrez-la sur le dossier du bus, sinon la pièce restera au magasin.");
            return;
        }
        if (!demandes.dossierConnu(p.getSavCaseId())) {
            sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                    "Pièce livrée au magasin, mais le dossier rattaché à cette sortie n'existe plus"
                            + " (il a été supprimé) : aucune mission « changer la pièce » n'a pu être"
                            + " ouverte. La réception est bien enregistrée — ouvrez la demande de pose"
                            + " sur le dossier actuel du bus.");
            return;
        }
        Mission pose = demandes.ouvrirPourLaPiece(new MissionService.PoseDePiece(p.getSavCaseId(),
                i.getNumber(), p.getDesignation(), p.getReference(), p.getQuantityReceived()), jwt);
        p.setFittingMissionId(pose.getId());
        p.setUpdatedAt(LocalDateTime.now());
        pieces.save(p);
        sortie.event(i.getId(), InterventionEventType.PART, InterventionEventSource.SERVER, jwt,
                "Pièce livrée : mission " + pose.getNumber() + " « changer la pièce » ouverte sur"
                        + " le même dossier — elle attend sa décision");
    }

    // ------------------------------------------------------------------ détails

    /** Le guichet et l'atelier, jamais le compte de technicien : liste à {@link Access}. */
    private static void requireGuichet(Authentication auth) {
        if (!Access.ordersAndReceivesParts(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Passer une commande et réceptionner une pièce se fait au guichet ou au magasin :"
                            + " votre compte ne peut pas écrire ces deux cases.");
        }
    }

    /** Les quatre cases de l'homme, et rien de plus : l'état et les liens ne se saisissent pas. */
    private static void remplir(InterventionPartRequest p, PartDemandRequest r) {
        String designation = trim(r.designation());
        String raison = trim(r.reason());
        if (designation == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La désignation s'écrit : « pièce » ne suffit pas à un magasin pour commander.");
        }
        if (raison == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La raison s'écrit : sans elle, personne ne peut dire six mois après pourquoi cette"
                            + " pièce a été commandée pour ce bus.");
        }
        if (r.quantity() == null || r.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La quantité doit être d'au moins une pièce : à zéro, il n'y a rien à commander.");
        }
        p.setReference(trim(r.reference()));
        p.setDesignation(designation);
        p.setReason(raison);
        p.setQuantityOrdered(r.quantity());
    }

    /** Une liste de pièces et les sorties qu'elles pointent, lues en bloc : pas de N+1 à l'écran. */
    private List<PartDemandResponse> lignes(List<InterventionPartRequest> rows,
                                           Map<String, Intervention> parSortie) {
        Map<String, String> poses = numerosDeMissionsDePose(rows);
        Map<String, String> montees = numerosDeSorties(rows, parSortie);
        return rows.stream()
                .map(p -> {
                    Intervention vue = p.getInterventionId() == null ? null : parSortie.get(p.getInterventionId());
                    if (vue == null) {
                        return null;
                    }
                    InterventionResponse rendue = sortie.full(vue);
                    return InterventionPartDtos.ligne(p, rendue.number(), rendue.missionNumber(),
                            rendue.savCaseNumber(), rendue.vehicleLabel(),
                            lire(poses, p.getFittingMissionId()), lire(montees, p.getFittedInterventionId()));
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** Les missions ouvertes par les livraisons de cette page, relues d'un bloc avec leur numéro. */
    private Map<String, String> numerosDeMissionsDePose(List<InterventionPartRequest> rows) {
        List<String> ids = rows.stream().map(InterventionPartRequest::getFittingMissionId)
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : missions.findAllById(ids).stream()
                .collect(Collectors.toMap(Mission::getId, Mission::getNumber));
    }

    /** Les secondes sorties, d'un bloc — et une sortie déjà chargée pour sa ligne ne se relit pas. */
    private Map<String, String> numerosDeSorties(List<InterventionPartRequest> rows,
                                                 Map<String, Intervention> parSortie) {
        Map<String, String> numeros = new HashMap<>();
        parSortie.forEach((id, s) -> numeros.put(id, s.getNumber()));
        List<String> ids = rows.stream().map(InterventionPartRequest::getFittedInterventionId)
                .filter(Objects::nonNull).distinct().filter(id -> !numeros.containsKey(id)).toList();
        if (!ids.isEmpty()) {
            interventions.findAllById(ids).forEach(s -> numeros.put(s.getId(), s.getNumber()));
        }
        return numeros;
    }

    /**
     * La ligne qui vient d'être écrite, rendue telle quelle. Ses deux liens de pose se
     * relisent à la source : une seule ligne, deux lectures, et surtout pas un numéro
     * inventé quand la mission ou la seconde sortie n'existe pas encore.
     */
    private PartDemandResponse ligne(InterventionPartRequest p, InterventionResponse vue) {
        return InterventionPartDtos.ligne(p, vue.number(), vue.missionNumber(), vue.savCaseNumber(),
                vue.vehicleLabel(),
                p.getFittingMissionId() == null ? null
                        : missions.findById(p.getFittingMissionId()).map(Mission::getNumber).orElse(null),
                p.getFittedInterventionId() == null ? null
                        : interventions.findById(p.getFittedInterventionId())
                                .map(Intervention::getNumber).orElse(null));
    }

    /** Une carte de numéros, lue sans NPE sur une clé nulle — la leçon du 500 du 26/10. */
    private static String lire(Map<String, String> carte, String cle) {
        return cle == null ? null : carte.get(cle);
    }

    private Intervention sortieRequire(String interventionId) {
        return interventions.findById(interventionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "La sortie de cette demande de pièce est introuvable."));
    }

    private InterventionPartRequest pieceDe(String id) {
        return pieces.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cette demande de pièce est introuvable."));
    }

    /** L'adresse du compte, comme au journal de la sortie : une demande sans nom ne se remonte pas. */
    private static String qui(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String email = jwt.getClaimAsString("email");
        return email == null || email.isBlank() ? jwt.getSubject() : email;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
