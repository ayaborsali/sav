package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.EnvoyerRequest;
import tn.higer.sav_backend.dto.InterventionDtos.InterventionResponse;
import tn.higer.sav_backend.dto.MissionDtos.MissionEnvoiRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionReasonRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionCountersResponse;
import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.dto.MissionDtos.PapierDeTravail;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionIssue;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanAlert;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.SoldPlanAlertRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * La mission : la réponse de l'atelier à une demande qui attend une décision.
 *
 * C'est <b>la seule porte</b> par laquelle un homme et une voiture sortent, et par
 * laquelle un papier de l'atelier se met à courir. Avant ce lot, trois portes
 * écrivaient la même histoire : la case du dossier, le bouton du rappel, l'écran
 * des missions. Une mission répond donc à l'une des deux origines possibles :
 *  - un {@link SavCase} — un client a parlé ; qui, par quel canal, à quelle heure
 *    et ce qu'il attend restent sur le dossier (V20), jamais recopiés ici ;
 *  - un {@link SoldPlanAlert} — un palier de forfait vendu a tourné, personne n'a
 *    appelé, et c'est le compteur ou la date qui demande la sortie.
 *
 * Les trois réponses : {@link MissionIssue#HOMME} (une sortie DP- naît par la
 * méthode serveur de son origine), {@link MissionIssue#ATELIER} (un ordre OR-
 * brouillon), {@link MissionIssue#DEVIS} (un devis DV- brouillon). Aucun montant
 * n'est deviné : les papiers s'ouvrent vides.
 *
 * « Soldée » ne s'écrit pas : elle se lit derrière, sur le papier du bout de la
 * chaîne. Une mission ne porte aucun montant, elle affiche des numéros.
 */
@Service
public class MissionService {

    /** Largeur normale du compteur MT-, comme les sorties et les ordres. */
    private static final int LARGEUR = 3;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MissionRepository missions;
    private final SavCaseRepository savCases;
    private final InterventionRepository interventions;
    private final MaintenanceRepository documents;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final SoldPlanAlertRepository rappels;
    private final SoldPlanStepRepository suivis;
    private final InterventionService sortie;
    private final AuditService audit;
    private final double tvaRate;

    /**
     * Le délai contractuel, en heures. Une valeur de configuration, pas une case de
     * l'écran : 48 h vient du contrat de maintenance signé, et un chiffre que chacun
     * peut changer à la souris cesse d'être une règle pour devenir une opinion.
     * Une valeur négative ou nulle éteint la montre — la case reste vide plutôt que
     * d'afficher un délai que personne n'a promis.
     */
    private final long delaiHeures;

    /**
     * Les dernières heures avant la limite sont dites « limites » à l'écran, pour
     * qu'un retard se voie avant de surprendre. Réglable : un client qui promet
     * 24 h n'a pas le même tiers d'alerte qu'un contrat de 48 h.
     */
    private final long seuilAlerteHeures;

    public MissionService(MissionRepository missions, SavCaseRepository savCases,
                          InterventionRepository interventions, MaintenanceRepository documents,
                          VehicleRepository vehicles, ClientRepository clients,
                          SoldPlanAlertRepository rappels, SoldPlanStepRepository suivis,
                          InterventionService sortie, AuditService audit,
                          @Value("${app.tva.rate:19}") double tvaRate,
                          @Value("${app.sav.delai-intervention-heures:48}") long delaiHeures,
                          @Value("${app.sav.delai-alerte-heures:12}") long seuilAlerteHeures) {
        this.missions = missions;
        this.savCases = savCases;
        this.interventions = interventions;
        this.documents = documents;
        this.vehicles = vehicles;
        this.clients = clients;
        this.rappels = rappels;
        this.suivis = suivis;
        this.sortie = sortie;
        this.audit = audit;
        this.tvaRate = tvaRate;
        this.delaiHeures = delaiHeures;
        this.seuilAlerteHeures = seuilAlerteHeures;
    }

    @Transactional(readOnly = true)
    public Page<MissionResponse> list(Pageable pageable) {
        Page<Mission> page = missions.findAllByOrderByCreatedAtDesc(pageable);
        Contexte contexte = contexteDe(page.getContent());
        return page.map(m -> response(m, contexte));
    }

    @Transactional(readOnly = true)
    public MissionResponse get(String id) {
        Mission m = missionDe(id);
        return response(m, contexteDe(List.of(m)));
    }

    /**
     * Noter une demande. Le geste est distinct de la décision : appeler depuis le
     * guichet ne doit pas obliger quelqu'un à savoir déjà si un homme va sortir.
     *
     * Sur un rappel, le geste est <b>idempotent</b> : la question est posée par une
     * date ou un compteur, pas par une personne, et deux clics ne doivent pas
     * aligner deux missions sur le même palier.
     */
    @Transactional
    public MissionResponse creer(MissionRequest r, Jwt jwt, Authentication auth) {
        requireRecorder(auth);
        boolean surDossier = nonBlanc(r.savCaseId());
        boolean surRappel = nonBlanc(r.alertId());
        if (surDossier == surRappel) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une mission répond soit à un dossier SAV (un client a appelé), soit à un rappel de"
                            + " palier (le compteur ou la date a tourné) — jamais aux deux, jamais à rien."
                            + " Choisissez l'origine de la demande.");
        }
        SavCase dossier = surDossier ? dossierDe(r.savCaseId()) : null;
        SoldPlanAlert rappel = surRappel ? rappelDe(r.alertId()) : null;
        if (rappel != null) {
            Mission dejaOuverte = missionOuverte(rappel.getId());
            if (dejaOuverte != null) {
                return response(dejaOuverte, contexteDe(List.of(dejaOuverte)));
            }
        }

        Mission m = new Mission();
        m.setId("mss-" + UUID.randomUUID());
        m.setNumber(nextNumber());
        m.setSavCaseId(dossier == null ? null : dossier.getId());
        m.setAlertId(rappel == null ? null : rappel.getId());
        m.setState(MissionState.A_TRAITER);
        m.setNote(trim(r.note()));
        m.setCreatedBy(subject(jwt));
        m.setCreatedAt(LocalDateTime.now());
        missions.save(m);
        audit.record("CREATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " ouverte sur "
                        + (dossier != null ? "le dossier " + dossier.getCaseNumber()
                                : "le rappel « " + rappel.getKind().label() + " »"), jwt);
        return response(m, contexteDe(List.of(m)));
    }

    /**
     * La case « un technicien doit aller sur place » du dossier enregistre une
     * demande, elle ne fait plus sortir qui que ce soit : c'est ici que la seconde
     * porte s'est refermée. Sans doublon — cocher, décocher, recocher ne doit pas
     * aligner trois missions sur le même appel — et sans rien effacer : une décision
     * prise par un homme ne se dédit pas parce qu'on a décoché une case.
     *
     * Pas de garde de rôle ici : qui peut éditer le dossier peut noter sa demande,
     * et c'est {@link #envoyerUnHomme} qui exige le chef d'atelier.
     *
     * Rend la demande qui tient : celle déjà ouverte, ou la nouvelle. L'écran du
     * dossier affiche ce numéro — sans lui, cocher la case ne laisse rien de visible
     * et le poste croit n'avoir rien fait.
     */
    @Transactional
    public Mission enregistrerDepuisDossier(SavCase dossier, Jwt jwt) {
        Mission dejaOuverte = missionOuverteSurDossier(dossier.getId());
        if (dejaOuverte != null) {
            return dejaOuverte;
        }
        Mission m = new Mission();
        m.setId("mss-" + UUID.randomUUID());
        m.setNumber(nextNumber());
        m.setSavCaseId(dossier.getId());
        m.setState(MissionState.A_TRAITER);
        m.setNote("Déclaré par le dossier " + dossier.getCaseNumber());
        m.setCreatedBy(subject(jwt));
        m.setCreatedAt(LocalDateTime.now());
        missions.save(m);
        audit.record("CREATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " ouverte sur le dossier " + dossier.getCaseNumber()
                        + " (le dossier déclare qu'un technicien doit aller sur place)", jwt);
        return m;
    }

    /**
     * Ce qu'une pièce arrivée au magasin sait déjà : la réclamation qu'elle attend,
     * la sortie où elle est née, et ce qu'il faut monter dessus.
     */
    public record PoseDePiece(String savCaseId, String sortieNumber, String designation,
                              String reference, Integer quantite) {}

    /**
     * La pièce est livrée : quelqu'un doit maintenant la monter. Cette demande n'est
     * pas tapée par un humain, elle naît du fait — et le trou du portail était
     * exactement là : une ligne « Livrée » qui n'envoyait rien, pendant que le bus
     * attendait dehors.
     *
     * La mission est <b>ouverte, pas décidée</b> : « À traiter », sans issue. Qui part
     * dehors et qui entre à l'atelier reste un choix d'homme ; le portail ouvre la
     * question, il ne la résout pas. Elle répond à la <b>même réclamation</b>, donc
     * elle relit le même bus et le même client — rien n'est recopié dessus.
     *
     * Pas de garde de rôle ici : ce n'est pas un geste d'écran, c'est la suite d'une
     * réception déjà autorisée au guichet. Et pas d'idempotence cherchée sur le
     * dossier — un dossier peut porter deux missions, c'est même ce qu'on attend de
     * lui quand une pièce manque après un premier passage. Ce qui empêche la seconde
     * mission est ailleurs : une ligne « Livrée » ne se réceptionne plus, donc elle
     * n'ouvre qu'une fois.
     */
    @Transactional
    public Mission ouvrirPourLaPiece(PoseDePiece piece, Jwt jwt) {
        SavCase dossier = dossierDe(piece.savCaseId());
        Mission m = new Mission();
        m.setId("mss-" + UUID.randomUUID());
        m.setNumber(nextNumber());
        m.setSavCaseId(dossier.getId());
        m.setState(MissionState.A_TRAITER);
        m.setNote("Changer la pièce " + (piece.reference() == null ? "« " + piece.designation() + " »"
                        : "« " + piece.designation() + " » (réf. " + piece.reference() + ")")
                + " — " + piece.quantite() + " arrivée(s) au magasin, vue sur la sortie " + piece.sortieNumber());
        m.setCreatedBy(subject(jwt));
        m.setCreatedAt(LocalDateTime.now());
        missions.save(m);
        audit.record("CREATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " ouverte par la livraison de la pièce « "
                        + piece.designation() + " » sur le dossier " + dossier.getCaseNumber(), jwt);
        return m;
    }

    /**
     * Le dossier est-il encore là ? Lu par le service des pièces avant d'ouvrir une
     * demande de pose : une boîte qui arrive au magasin ne doit pas se refuser parce
     * qu'un papier a été supprimé six mois plus tôt. La réponse se donne avant toute
     * écriture — après coup, il serait trop tard pour ne rien poser.
     */
    public boolean dossierConnu(String savCaseId) {
        return nonBlanc(savCaseId) && savCases.existsById(savCaseId);
    }

    /**
     * Un homme et une voiture de déplacement sortent : la sortie naît par la méthode
     * serveur de son origine. Cette réponse-là n'exclut pas l'autre — un bus dont
     * l'homme a constaté qu'il fallait chiffrer le travail garde sa sortie ouverte
     * et reçoit son devis en plus.
     */
    @Transactional
    public MissionResponse envoyerUnHomme(String id, MissionEnvoiRequest r, Jwt jwt, Authentication auth) {
        return decider(id, MissionIssue.HOMME, r, jwt, auth);
    }

    /** Le bus vient à l'atelier : un ordre de travail (OR-) brouillon est ouvert. */
    @Transactional
    public MissionResponse traiterAtelier(String id, Jwt jwt, Authentication auth) {
        return decider(id, MissionIssue.ATELIER, null, jwt, auth);
    }

    /**
     * Le travail n'est pas couvert — après garantie, hors forfait, pièce à fournir :
     * un devis (DV-) brouillon est ouvert, vide. Le portail ne fabrique aucun prix :
     * le barème de l'atelier s'écrit sur le devis par un humain.
     */
    @Transactional
    public MissionResponse etablirDevis(String id, Jwt jwt, Authentication auth) {
        return decider(id, MissionIssue.DEVIS, null, jwt, auth);
    }

    /** Refuser la demande, motif écrit. Une mission refusée ne se rattrape pas : nouvelle demande, nouvelle mission. */
    @Transactional
    public MissionResponse refuser(String id, MissionReasonRequest r, Jwt jwt, Authentication auth) {
        requireDecider(auth);
        Mission m = missionDe(id);
        String motif = raisonExigee(r, "refuser");
        if (m.getState() == MissionState.REFUSEE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La mission " + m.getNumber() + " est déjà refusée.");
        }
        if (m.getState() == MissionState.EN_COURS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La mission " + m.getNumber() + " est déjà en cours (" + papierDe(m)
                            + ") : on ne refuse pas une demande après avoir sorti l'homme ou ouvert le papier."
                            + " Menez le papier jusqu'au bout.");
        }
        m.setState(MissionState.REFUSEE);
        m.setReason(motif);
        decrit(m, jwt);
        missions.save(m);
        audit.record("UPDATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " refusée — " + motif, jwt);
        return response(m, contexteDe(List.of(m)));
    }

    /** Reporter la demande, motif écrit. Elle reste due : « Reportée » n'éteint rien. */
    @Transactional
    public MissionResponse reporter(String id, MissionReasonRequest r, Jwt jwt, Authentication auth) {
        requireDecider(auth);
        Mission m = missionDe(id);
        String motif = raisonExigee(r, "reporter");
        if (m.getState() == MissionState.EN_COURS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La mission " + m.getNumber() + " est déjà en cours (" + papierDe(m)
                            + ") : un papier sorti ne se reporte pas.");
        }
        if (m.getState() == MissionState.REFUSEE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La mission " + m.getNumber() + " est refusée. Pour la reprendre, ouvrez une nouvelle demande.");
        }
        m.setState(MissionState.REPORTEE);
        m.setReason(motif);
        decrit(m, jwt);
        missions.save(m);
        audit.record("UPDATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " reportée — " + motif, jwt);
        return response(m, contexteDe(List.of(m)));
    }

    /**
     * Reprendre une demande reportée : elle retourne « À traiter ». Le motif du
     * report reste écrit dans la case raison — un fait passé ne s'édite pas, et c'est
     * lui qui explique pourquoi la mission a dormi.
     */
    @Transactional
    public MissionResponse reprendre(String id, Jwt jwt, Authentication auth) {
        requireDecider(auth);
        Mission m = missionDe(id);
        if (m.getState() != MissionState.REPORTEE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Seule une mission reportée se reprend. Celle-ci est « "
                            + labelDe(m.getState()) + " ».");
        }
        m.setState(MissionState.A_TRAITER);
        decrit(m, jwt);
        missions.save(m);
        audit.record("UPDATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " reprise après le report", jwt);
        return response(m, contexteDe(List.of(m)));
    }

    // ----------------------------------------------------------------------------------------------
    // La décision, en un seul endroit pour les trois issues
    // ----------------------------------------------------------------------------------------------

    /**
     * La décision, en un seul endroit pour les trois issues.
     *
     * Une demande a deux places de réponse, pas trois : la sortie d'un côté
     * ({@code interventionId}), le papier de l'atelier de l'autre
     * ({@code maintenanceItemId}). « Envoyer un homme » puis « Établir un devis »
     * occupe les deux places et garde les deux papiers — c'est le cours réel d'une
     * panne : l'homme constate sur place, l'atelier chiffre. Deux réponses ne sont
     * pas une seconde demande : rien n'est recopié, la mission reste une ligne.
     *
     * Ce qui ne peut pas coexister, ce sont les deux papiers d'atelier : ordre et
     * devis écrivent dans la même case, le second effacerait le travail du premier.
     * Là, le refus est écrit et dit quoi clôturer.
     */
    private MissionResponse decider(String id, MissionIssue voulue, MissionEnvoiRequest envoi,
                                    Jwt jwt, Authentication auth) {
        requireDecider(auth);
        Mission m = missionDe(id);
        if (m.getState() == MissionState.REFUSEE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La mission " + m.getNumber() + " est refusée, motif écrit : « " + m.getReason()
                            + " ». Une nouvelle demande donne une nouvelle mission.");
        }
        boolean secondeReponse = m.getState() == MissionState.EN_COURS;
        MaintenanceItem papierEnCours = papierDejaOuvert(m);
        // Un devis que le client a accepté DONNE l'ordre de travail : les deux papiers
        // ne tiennent pas dans la même case, mais le second est la suite du premier,
        // et le lien s'écrit en base (V35). Un devis encore brouillon, lui, occupe la
        // case — l'atelier n'a rien à exécuter tant que le client n'a pas dit oui.
        boolean ordreApresLeDevis = voulue == MissionIssue.ATELIER && estUnDevisAccepte(papierEnCours);
        if (secondeReponse) {
            if (m.getIssue() == voulue) {
                // Deux clics ne font pas deux papiers : la même demande renvoie celui qui court.
                return response(m, contexteDe(List.of(m)));
            }
            if (placePrise(m, voulue) && !ordreApresLeDevis) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La mission " + m.getNumber() + " a déjà son papier d'atelier (« "
                                + labelDe(m.getIssue()) + " », " + papierDe(m) + "). Un ordre de travail"
                                + " et un devis écrivent dans la même case : clôturez ce papier avant d'en"
                                + " ouvrir un autre, ou refaites une demande.");
            }
            // Une réponse de plus ne remplace pas la première : « Envoyer un homme » écrit
            // sur la sortie, le devis écrit sur le papier d'atelier. La même panne a le
            // droit aux deux — l'homme dehors chiffre, l'atelier exécute.
        }

        String papier;
        if (voulue == MissionIssue.HOMME) {
            InterventionResponse creee = envoyerLaSortie(m, envoi, jwt, auth);
            m.setInterventionId(creee.id());
            papier = "sortie " + creee.number();
        } else {
            if (voulue == MissionIssue.DEVIS) {
                exigerUnAppelEnGarantieAvantLeDevis(m);
            }
            MaintenanceItem papierAtelier = ordreApresLeDevis
                    ? ouvrirPapier(m, MaintenanceType.OPERATION, papierEnCours, jwt)
                    : papierDejaOuvert(m);
            if (papierAtelier == null) {
                papierAtelier = ouvrirPapier(m, typeDe(voulue), null, jwt);
            }
            m.setMaintenanceItemId(papierAtelier.getId());
            papier = motDuPapier(papierAtelier) + " " + papierAtelier.getNumber();
        }
        m.setIssue(voulue);
        m.setState(MissionState.EN_COURS);
        decrit(m, jwt);
        missions.save(m);
        audit.record("UPDATE", "MISSION", m.getId(),
                "Mission " + m.getNumber() + " — " + labelDe(voulue) + ", " + papier
                        + (secondeReponse ? " (seconde réponse sur la même demande, " + papierDe(m) + ")" : ""),
                jwt);
        return response(m, contexteDe(List.of(m)));
    }

    /**
     * La case que vise l'issue. « Envoyer un homme » écrit sur {@code interventionId},
     * « Traiter à l'atelier » et « Établir un devis » écrivent tous deux sur
     * {@code maintenanceItemId} : les deux papiers d'atelier ne peuvent pas tenir
     * ensemble, le second effacerait le premier. La sortie et le devis, si.
     */
    private static boolean placePrise(Mission m, MissionIssue voulue) {
        return voulue != MissionIssue.HOMME && m.getMaintenanceItemId() != null;
    }

    /**
     * Un devis que le client a dit bon. Depuis V36 le mot est un état, pas une
     * supposition : « accepté » se lit sur le papier, avec sa date et la preuve jointe.
     * Un devis envoyé attend encore, un devis refusé a dit non — ni l'un ni l'autre
     * n'ouvre un ordre. L'accord ne se devine plus derrière « tout ce qui n'est pas
     * brouillon », parce qu'un accord qui n'existe que dans une case oublierait d'être
     * écrit, et l'ordre partirait sur un devis que personne n'a signé.
     */
    private static boolean estUnDevisAccepte(MaintenanceItem papier) {
        return papier != null && papier.getType() == MaintenanceType.DEVIS
                && papier.getStatus() == MaintenanceStatus.VALIDATED;
    }

    /**
     * Un devis est une facture annoncée au client. Tant que le bus était sous garantie
     * <b>le jour de la panne</b>, la réparation se demande au constructeur : la même
     * réparation chiffrée chez le client ET chez Higer est une perte pour l'un ou pour
     * l'autre, et personne n'a décidé lequel. Le refus se fait ici, à l'ouverture du
     * papier, et non à l'écran : l'écran Missions ne tient pas la date de fin de
     * garantie, elle est sur la fiche du bus, et un poste ne recharge pas une fiche
     * pour prendre une décision.
     *
     * <b>La garantie se juge au jour de la panne, jamais à aujourd'hui</b> (décision du
     * 29/10). Une garantie expirée la semaine dernière n'efface pas le droit de dépôt
     * sur une panne qui était couverte — c'est même le cas le plus fréquent, l'appel
     * en garantie se dépose après coup. Et l'inverse tient aussi : un bus garanti
     * aujourd'hui ne l'était peut-être pas au jour où il est tombé en panne, et là le
     * devis est légitime. Le jour retenu est celui que le dossier porte déjà (voir
     * {@link AgPaperAssemblyService#jourDeLIncident}) ; à défaut, l'ouverture du
     * dossier, qui est le jour où la panne a été annoncée. Le refus nomme la date
     * relue et d'où elle vient : l'atelier doit pouvoir corriger la bonne case.
     *
     * Le nom du bus, le numéro du dossier et la date se lisent dans le message —
     * « ouvrez un appel en garantie » sans dire lequel ne se travaille pas.
     *
     * Des faits relus sur le serveur laissent malgré tout le devis passer :
     * — le dossier est une <b>visite</b> de la famille « Visites » <b>sans</b> forfait
     *   marqué dessus : le constructeur ne rembourse pas une visite périodique, donc
     *   rien ne peut y obliger un appel en garantie ;
     * — le constructeur a déjà répondu (« Refusé », ou « Contesté » après refus) : la
     *   prise en charge est fermée, c'est le client qui paiera, le devis a sa place ;
     * — la fiche du bus ne porte pas de date : le portail n'invente pas une garantie
     *   pour refuser un papier ; ou cette date est <b>antérieure</b> au jour de la panne.
     *
     * Une demande née d'un <b>rappel</b> n'a pas de dossier : la garde ne se juge pas
     * sur elle. Un palier dû se chiffrer au client est légitime dès qu'il sort de la
     * main-d'œuvre vendue (pièce fournie par Saphir, consommable, déplacement, travaux
     * correctifs, prestation en plus) — c'est l'article 5 du contrat, et le devis est
     * alors le seul papier qui demande l'accord écrit.
     *
     * « Traiter à l'atelier » n'est pas gardé : un ordre de travail ne facture
     * personne, il dit seulement que l'atelier exécute — en garantie comme ailleurs.
     */
    private void exigerUnAppelEnGarantieAvantLeDevis(Mission m) {
        SavCase dossier = m.getSavCaseId() == null ? null
                : savCases.findById(m.getSavCaseId()).orElse(null);
        if (dossier == null) {
            return;
        }
        ClaimState reponseDuConstructeur = dossier.getClaimState();
        if (reponseDuConstructeur == ClaimState.REFUSE
                || reponseDuConstructeur == ClaimState.CONTESTE) {
            return;
        }
        // Une visite périodique n'est pas une panne : elle est vendue dans le forfait,
        // le constructeur ne la rembourse pas, donc rien ne peut y obliger un appel en
        // garantie avant le devis (décision du 29/10 — la garde ne regardait pas la
        // famille du dossier et refusait un devis sur une visite d'un bus couvert).
        if (dossier.getType() == SavCaseType.VISITES) {
            return;
        }
        Vehicle bus = dossier.getVehicleId() == null ? null
                : vehicles.findById(dossier.getVehicleId()).orElse(null);
        LocalDate fin = bus == null ? null : bus.getWarrantyEndDate();
        if (fin == null) {
            return;
        }
        LocalDate panne = AgPaperAssemblyService.jourDeLIncident(dossier);
        String dOuVientCetteDate = dossier.getAgIncidentDate() != null
                ? "date de l’incident écrite sur le papier AG"
                : dossier.getCalledAt() != null
                        ? "appel du client enregistré sur le dossier"
                        : null;
        if (panne == null) {
            // Ni papier ni appel daté : le jour où le dossier a été ouvert est le seul
            // jour où la panne a été annoncée que le portail connaisse.
            panne = dossier.getCreatedAt();
            dOuVientCetteDate = "ouverture du dossier";
        }
        if (panne == null) {
            panne = LocalDate.now();
            dOuVientCetteDate = "aucune date sur le dossier, jour du jour retenu";
        }
        if (fin.isBefore(panne)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Le bus " + FicheLabel.bus(bus) + " était sous garantie au jour de la panne ("
                        + panne.format(JOUR) + ", " + dOuVientCetteDate + "), et sa garantie court jusqu'au "
                        + fin.format(JOUR) + " (dossier " + dossier.getCaseNumber() + "). Un devis chiffre cette panne chez le"
                        + " client alors qu'elle se demande au constructeur : ouvrez l'appel en garantie sur"
                        + " ce dossier. Le devis ne se rouvre qu'une fois la réponse du constructeur écrite"
                        + " sur le dossier — « " + ClaimState.REFUSE.label() + " » ou « "
                        + ClaimState.CONTESTE.label() + " ».");
    }

    /**
     * L'homme qui part, par la porte de l'origine. Un dossier appelle
     * {@code createFromSavCase} ; un rappel appelle {@code envoyerPourRappel}, qui
     * écrit le bus, le palier et le motif « entretien » sur la sortie. Ces deux
     * méthodes existent depuis avant la mission : elles sont réutilisées, pas
     * redoublées — une troisième écriture d'une sortie ferait deux histoires.
     */
    private InterventionResponse envoyerLaSortie(Mission m, MissionEnvoiRequest envoi,
                                                 Jwt jwt, Authentication auth) {
        if (m.getSavCaseId() != null) {
            return sortie.createFromSavCase(m.getSavCaseId(), jwt, auth);
        }
        String voiture = envoi == null ? null : trim(envoi.serviceVehicleId());
        String homme = envoi == null ? null : trim(envoi.technicianUserId());
        if (voiture == null || homme == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une sortie d'entretien part avec une voiture de déplacement et un homme : "
                            + "choisissez-les sur cette ligne. Le portail n'envoie personne sans moyen de transport.");
        }
        UUID compte;
        try {
            compte = UUID.fromString(homme);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compte indiqué n'est pas un homme du portail : rechoisissez-le dans la liste.");
        }
        return sortie.envoyerPourRappel(m.getAlertId(), new EnvoyerRequest(voiture, compte), jwt, auth);
    }

    private static MaintenanceType typeDe(MissionIssue issue) {
        return issue == MissionIssue.DEVIS ? MaintenanceType.DEVIS : MaintenanceType.OPERATION;
    }

    /**
     * Le papier de l'atelier né d'une mission : le bus, le client et la demande
     * viennent de l'origine, en lecture. Aucune ligne, aucun montant — le papier est
     * un brouillon que l'atelier remplit, et le portail ne devine pas un prix.
     *
     * Quand l'origine est un rappel, le forfait et son palier s'écrivent dessus :
     * c'est ce lien qui, à la livraison, marquera la visite périodique « faite » et
     * éteindra le rappel. Sans lui, l'atelier aurait travaillé sans que le forfait
     * le sache.
     *
     * Deux liens d'origine s'écrivent ici et nulle part ailleurs (V35) :
     * {@code mission_id} — la demande qui a fait ouvrir le papier, ce qui permet de
     * rendre « les papiers de ce travail » au lieu d'un seul numéro écrasé par le
     * suivant — et {@code source_item_id} quand le papier sort d'un devis que le
     * client a accepté.
     */
    private MaintenanceItem ouvrirPapier(Mission m, MaintenanceType type, MaintenanceItem source, Jwt jwt) {
        Origine o = origineDe(m);
        MaintenanceItem item = new MaintenanceItem();
        item.setId("maint-" + UUID.randomUUID());
        item.setType(type);
        item.setNumber(type == MaintenanceType.DEVIS ? nextNumeroDevis() : nextNumeroOrdre());
        item.setVehicleId(o.busId());
        item.setClientId(o.clientId() == null ? "" : o.clientId());
        item.setDate(LocalDate.now());
        item.setStatus(MaintenanceStatus.DRAFT);
        item.setSoldPlanId(o.soldPlanId());
        item.setPlanStepId(o.planStepId());
        item.setMissionId(m.getId());
        item.setSourceItemId(source == null ? null : source.getId());
        item.setDescription("Mission " + m.getNumber() + " — " + o.libelle()
                + (source == null ? "" : " (après le devis « " + source.getNumber() + " » accepté)"));
        MaintenanceTotals.appliquer(item, tvaRate);
        documents.save(item);
        audit.record("CREATE", "MAINTENANCE", item.getId(),
                "Ouverture du « " + motDuPapier(item) + " » « " + item.getNumber() + " » par la mission "
                        + m.getNumber() + (source == null ? "" : ", depuis le devis « " + source.getNumber() + " »"),
                jwt);
        return item;
    }

    /** Ce que l'origine (dossier ou rappel) sait déjà : bus, client, palier visé, phrase à recopier en description. */
    private record Origine(String busId, String clientId, String soldPlanId, String planStepId, String libelle) {}

    private Origine origineDe(Mission m) {
        SavCase dossier = m.getSavCaseId() == null ? null
                : savCases.findById(m.getSavCaseId()).orElse(null);
        if (dossier != null) {
            return new Origine(dossier.getVehicleId(), dossier.getClientId(), null, null,
                    "dossier " + dossier.getCaseNumber() + " : "
                            + (dossier.getTitle() == null ? "" : dossier.getTitle()));
        }
        SoldPlanAlert rappel = rappelDe(m.getAlertId());
        SoldPlanStep suivi = suiviDe(rappel);
        SoldPlan forfait = suivi.getSoldPlan();
        return new Origine(forfait.getVehicleId(), forfait.getClientId(), forfait.getId(),
                suivi.getStep().getId(),
                "rappel du palier « " + suivi.getStep().getLabel() + " » du forfait « "
                        + forfait.getPlan().getName() + " »");
    }

    /** Le même compteur que celui de l'écran Maintenance : deux portes de numérotation donneraient deux OR- identiques. */
    private String nextNumeroOrdre() {
        return numeroDePapier("OR-");
    }

    /** DV- comme OR- : le compteur de l'année, et le même garde-fou contre un numéro déjà tapé à la main. */
    private String nextNumeroDevis() {
        return numeroDePapier("DV-");
    }

    private String numeroDePapier(String prefix) {
        String annee = prefix + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(annee, documents.findNumbersByPrefix(annee), 3,
                documents::existsByNumberIgnoreCase);
    }

    private String nextNumber() {
        String prefix = "MT-" + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(prefix, missions.findNumbersByPrefix(prefix), LARGEUR,
                missions::existsByNumberIgnoreCase);
    }

    // ----------------------------------------------------------------------------------------------
    // Le lecteur : une ligne d'écran, sans rien recopier dans la base
    // ----------------------------------------------------------------------------------------------

    private MissionResponse response(Mission m, Contexte c) {
        SavCase dossier = lire(c.dossiers, m.getSavCaseId());
        SoldPlanAlert rappel = lire(c.rappels, m.getAlertId());
        SoldPlanStep suivi = rappel == null ? null : lire(c.suivis, rappel.getSoldPlanStepId());
        Intervention sortieLiee = lire(c.sorties, m.getInterventionId());
        MaintenanceItem papier = lire(c.papiers, m.getMaintenanceItemId());

        String busId = dossier != null ? dossier.getVehicleId()
                : (suivi == null ? null : suivi.getSoldPlan().getVehicleId());
        String clientId = dossier != null ? dossier.getClientId()
                : (suivi == null ? null : suivi.getSoldPlan().getClientId());
        Vehicle bus = lire(c.bus, busId);
        Client client = lire(c.clients, clientId);

        MissionState vu = m.getState();
        if (vu == MissionState.EN_COURS && papierFini(sortieLiee, papier)) {
            vu = MissionState.SOLDEE;
        }

        // Le nom de l'homme dehors et le mot d'étape de la sortie appartiennent au
        // module Dépannage : il relit sa propre sortie par son propre lecteur, au
        // lieu que l'écran Missions tienne une seconde liste du personnel.
        InterventionResponse sortieVue = sortieLiee == null ? null : sortie.full(sortieLiee);
        LocalDateTime depart = instantDuDepart(dossier, rappel);
        Sla montre = montreDe(vu, depart);

        return new MissionResponse(
                m.getId(), m.getNumber(),
                nom(vu), labelDe(vu),
                nom(m.getIssue()), m.getIssue() == null ? null : m.getIssue().label(),
                m.getSavCaseId(),
                dossier == null ? null : dossier.getCaseNumber(),
                dossier == null ? null : dossier.getTitle(),
                m.getAlertId(), libelleRappel(suivi),
                client == null ? null : client.getName(),
                FicheLabel.bus(bus),
                bus == null ? null : trim(bus.getVin()),
                bus == null ? null : trim(bus.getRegistration()),
                dossier == null ? null : dossier.getCallerName(),
                dossier == null || dossier.getCallerChannel() == null
                        ? null : dossier.getCallerChannel().label(),
                dossier == null || dossier.getCalledAt() == null
                        ? null : dossier.getCalledAt().toString(),
                montre.state(), montre.label(),
                m.getInterventionId(), sortieLiee == null ? null : sortieLiee.getNumber(),
                sortieVue == null ? null : sortieVue.status(),
                sortieVue == null ? null : sortieVue.technicianName(),
                m.getMaintenanceItemId(), papier == null ? null : papier.getNumber(),
                papier == null ? null : motDuPapier(papier),
                m.getReason(), m.getNote(), m.getDecidedBy(),
                m.getDecidedAt() == null ? null : m.getDecidedAt().toString(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toString(),
                papiersDeLaDemande(m, c));
    }

    /**
     * La chaîne de la demande, dans l'ordre où elle a été écrite : un devis accepté
     * puis l'ordre qui en sort tiennent les deux sur la ligne. La case
     * {@code maintenanceItemId} de la mission ne peut en nommer qu'un — le papier qui
     * court —, et c'est précisément ce qui faisait disparaître le devis de l'écran
     * quand l'ordre prenait sa place. Le lien {@code mission_id} (V35) tient la liste.
     */
    private static List<PapierDeTravail> papiersDeLaDemande(Mission m, Contexte c) {
        List<MaintenanceItem> chaine = c.papiersParMission.getOrDefault(m.getId(), List.of());
        if (chaine.isEmpty()) {
            // Un papier ouvert avant V35 ne porte pas encore la demande : la ligne
            // reste au moins fidèle à la case qui court, plutôt que vide.
            MaintenanceItem quiCourt = lire(c.papiers, m.getMaintenanceItemId());
            return quiCourt == null ? List.of() : List.of(enPapierDeTravail(quiCourt));
        }
        return chaine.stream().map(MissionService::enPapierDeTravail).toList();
    }

    private static PapierDeTravail enPapierDeTravail(MaintenanceItem papier) {
        return new PapierDeTravail(papier.getId(), papier.getNumber(),
                papier.getType() == null ? null : papier.getType().label(),
                papier.getStatus() == null ? null : papier.getStatus().label());
    }

    /**
     * Le point de départ de la montre, et lui seul : l'heure où le client a parlé,
     * sinon la date où le rappel a tourné. Une mission qui n'a ni l'un ni l'autre ne
     * peut pas mesurer un délai — la case reste vide et l'écrit, au lieu d'inventer
     * une heure que personne n'a horodatée.
     */
    private static LocalDateTime instantDuDepart(SavCase dossier, SoldPlanAlert rappel) {
        if (dossier != null && dossier.getCalledAt() != null) {
            return dossier.getCalledAt();
        }
        return rappel == null ? null : rappel.getDetectedAt();
    }

    /**
     * La montre du contrat : ce qui reste, ou ce qui est dépassé.
     *
     * Elle s'arrête quand la demande a reçu sa réponse — une mission soldée a rendu
     * le bus, une mission refusée a donné sa réponse au client. « Reportée » ne
     * l'arrête pas : le client attend toujours, et compter une demande reportée comme
     * honorée serait le moyen le plus simple de ne jamais être en retard.
     *
     * Trois mots seulement, et le calcul se fait là : l'écran ne fait pas de maths,
     * sinon le délai changerait d'un poste à l'autre.
     */
    private Sla montreDe(MissionState etat, LocalDateTime depart) {
        if (etat == MissionState.SOLDEE || etat == MissionState.REFUSEE) {
            return new Sla("termine", "Terminé");
        }
        if (delaiHeures <= 0) {
            return new Sla("aucun", "délai non promis");
        }
        if (depart == null) {
            return new Sla("aucun", "départ non horodaté");
        }
        long ecoulee = Duration.between(depart, LocalDateTime.now()).toMinutes();
        long restant = delaiHeures * 60 - ecoulee;
        if (restant < 0) {
            return new Sla("depasse", "- " + heuresArrondies(-restant) + " h");
        }
        long restantHeures = heuresArrondies(restant);
        String state = restant <= seuilAlerteHeures * 60 ? "limite" : "dans_delai";
        return new Sla(state, restantHeures == 0 ? "moins d'1 h rest." : restantHeures + " h rest.");
    }

    /** Des heures entières, jamais une virgule : le guichet ne compte pas en décimales. */
    private static long heuresArrondies(long minutes) {
        return minutes / 60;
    }

    /**
     * Les quatre chiffres du bandeau, et le délai qui les produit.
     *
     * Chaque ligne passe par le même lecteur que la liste, à dessein : un bandeau
     * qui compterait dans sa propre requête finirait par annoncer trois demandes
     * pendant que la liste en montre deux.
     */
    @Transactional(readOnly = true)
    public MissionCountersResponse compteurs() {
        List<Mission> vivantes = missions.findByStateInOrderByCreatedAtDesc(
                List.of(MissionState.A_TRAITER, MissionState.EN_COURS, MissionState.REPORTEE));
        Contexte contexte = contexteDe(vivantes);
        long attente = 0, depasse = 0, dehors = 0, atelier = 0;
        for (Mission m : vivantes) {
            MissionResponse ligne = response(m, contexte);
            boolean rendu = "soldee".equals(ligne.state());
            if ("a_traiter".equals(ligne.state()) || "reportee".equals(ligne.state())) {
                attente++;
            }
            if ("depasse".equals(ligne.slaState())) {
                depasse++;
            }
            // Les deux chiffres se lisent sur les papiers qui courent, pas sur la seule
            // dernière issue : une demande qui a un homme dehors ET un devis compte
            // dans les deux cases. Compter l'issue en effacerait une.
            if (ligne.interventionNumber() != null && !estRendue(ligne.interventionStatus())) {
                dehors++;
            }
            if (ligne.papierNumero() != null && !rendu) {
                atelier++;
            }
        }
        return new MissionCountersResponse(attente, depasse, dehors, atelier,
                delaiHeures > 0 ? delaiHeures : 0);
    }

    /** La montre, en deux mots : l'état qui colore la pastille, le texte qui se lit. */
    private record Sla(String state, String label) {}


    /** La phrase du rappel, lue sur sa ligne d'origine : rien n'est recopié dans la mission. */
    private static String libelleRappel(SoldPlanStep suivi) {
        if (suivi == null) {
            return null;
        }
        SoldPlan forfait = suivi.getSoldPlan();
        return "palier « " + suivi.getStep().getLabel() + " » du forfait « "
                + forfait.getPlan().getName() + " »";
    }

    /** Le papier du bout de la chaîne a fini son travail : la mission est soldée, sans que personne l'écrive. */
    private static boolean papierFini(Intervention sortieLiee, MaintenanceItem papier) {
        if (sortieLiee == null && papier == null) {
            return false;
        }
        // Une demande peut avoir deux réponses qui courent : l'homme dehors ET le
        // devis. Elle n'est rendue que quand les deux sont finis — la clôture du
        // seul devis laisserait un homme dehors sous un « Soldée », et inversement.
        boolean sortieRendue = sortieLiee == null || sortieLiee.getStatus() == InterventionStatus.CLOSED;
        // « Signé » vaut fini pour la mission : le mot appartient au bon de livraison
        // (V39), et un papier que le client a signé est un papier dont le travail est rendu.
        boolean atelierFini = papier == null || papier.getStatus() == MaintenanceStatus.COMPLETED
                || papier.getStatus() == MaintenanceStatus.DELIVERED
                || papier.getStatus() == MaintenanceStatus.SIGNED;
        return sortieRendue && atelierFini;
    }

    /** Le dernier mot d'une sortie : « closed ». Les sept étapes d'avant engagent encore l'homme dehors. */
    private static boolean estRendue(String statutSortie) {
        return "closed".equals(statutSortie);
    }

    /** Les dossiers, rappels, bus, clients et papiers d'une page, en lectures groupées. */
    private Contexte contexteDe(List<Mission> lignes) {
        List<String> dossierIds = ids(lignes, Mission::getSavCaseId);
        Map<String, SavCase> dossiers = dossierIds.isEmpty() ? Map.of()
                : savCases.findAllById(dossierIds).stream()
                        .collect(Collectors.toMap(SavCase::getId, Function.identity()));

        List<String> rappelIds = ids(lignes, Mission::getAlertId);
        Map<String, SoldPlanAlert> rappelsOuverts = rappelIds.isEmpty() ? Map.of()
                : this.rappels.findAllById(rappelIds).stream()
                        .collect(Collectors.toMap(SoldPlanAlert::getId, Function.identity()));

        List<String> suiviIds = rappelsOuverts.values().stream().map(SoldPlanAlert::getSoldPlanStepId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, SoldPlanStep> suivisParId = suiviIds.isEmpty() ? Map.of()
                : suivis.findAllById(suiviIds).stream()
                        .collect(Collectors.toMap(SoldPlanStep::getId, Function.identity()));

        // Le bus et le client se lisent soit sur le dossier, soit sur le forfait du rappel.
        List<String> busIds = dossiers.values().stream().map(SavCase::getVehicleId)
                .filter(Objects::nonNull).distinct().toList();
        List<String> busDeRappel = suivisParId.values().stream()
                .map(s -> s.getSoldPlan().getVehicleId()).filter(Objects::nonNull)
                .filter(id -> !busIds.contains(id)).toList();
        Map<String, Vehicle> bus = index(vehicles, concat(busIds, busDeRappel), Vehicle::getId);

        List<String> clientIds = dossiers.values().stream()
                .map(SavCase::getClientId).filter(MissionService::nonBlanc).distinct().toList();
        List<String> clientDeRappel = suivisParId.values().stream()
                .map(s -> s.getSoldPlan().getClientId()).filter(MissionService::nonBlanc)
                .filter(id -> !clientIds.contains(id)).toList();
        Map<String, Client> clientsParId = index(clients, concat(clientIds, clientDeRappel), Client::getId);

        List<String> sortieIds = ids(lignes, Mission::getInterventionId);
        Map<String, Intervention> sorties = index(interventions, sortieIds, Intervention::getId);

        List<String> papierIds = ids(lignes, Mission::getMaintenanceItemId);
        Map<String, MaintenanceItem> papiers = index(documents, papierIds, MaintenanceItem::getId);

        // Toute la chaîne des demandes, en une seule lecture : un devis et l'ordre qui
        // en sort portent le même mission_id, et la case unique de la mission n'en
        // montre qu'un.
        List<String> demandeIds = ids(lignes, Mission::getId);
        Map<String, List<MaintenanceItem>> papiersParMission = demandeIds.isEmpty() ? Map.of()
                : documents.findByMissionIdInOrderByDateAscNumberAsc(demandeIds).stream()
                        .collect(Collectors.groupingBy(MaintenanceItem::getMissionId));

        return new Contexte(dossiers, rappelsOuverts, suivisParId, bus, clientsParId, sorties, papiers,
                papiersParMission);
    }

    private record Contexte(Map<String, SavCase> dossiers, Map<String, SoldPlanAlert> rappels,
                            Map<String, SoldPlanStep> suivis, Map<String, Vehicle> bus,
                            Map<String, Client> clients, Map<String, Intervention> sorties,
                            Map<String, MaintenanceItem> papiers,
                            Map<String, List<MaintenanceItem>> papiersParMission) {}

    /** Une carte chargée en bloc : une liste d'identifiants vide ne casse pas la page (Map.of(), jamais null). */
    private static <E> Map<String, E> index(
            org.springframework.data.jpa.repository.JpaRepository<E, String> repo,
            List<String> ids, Function<E, String> cle) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repo.findAllById(ids).stream().collect(Collectors.toMap(cle, Function.identity()));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private static List<String> ids(List<Mission> lignes, Function<Mission, String> prise) {
        return lignes.stream().map(prise).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * Une carte vide est immuable ({@code Map.of()}), et demander sa clé à une carte
     * immuable avec une clé nulle lève un NPE : c'est le 500 du 26/10 sur
     * « GET /api/sav-cases », où un dossier sans parent faisait tomber toute la page.
     * Une mission peut n'avoir encore ni dossier, ni bus, ni client — la ligne se lit
     * avec des cases vides, pas avec une erreur.
     */
    private static <K, V> V lire(Map<K, V> carte, K cle) {
        return cle == null ? null : carte.get(cle);
    }

    // ----------------------------------------------------------------------------------------------
    // Gardes et petits lecteurs
    // ----------------------------------------------------------------------------------------------

    /** Noter une demande : le guichet (ADMIN, gestionnaire SAV) et l'atelier. */
    private void requireRecorder(Authentication auth) {
        if (!Access.recordsMissions(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Enregistrer une demande d'intervention est réservé au guichet et à l'atelier.");
        }
    }

    /** Trancher : le chef d'atelier et l'administrateur, comme pour une sortie. */
    private void requireDecider(Authentication auth) {
        if (!Access.decidesMissions(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seuls le chef d'atelier et l'administrateur décident d'envoyer un homme,"
                            + " d'ouvrir un ordre, d'établir un devis, de refuser ou de reporter.");
        }
    }

    private Mission missionDe(String id) {
        return missions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission introuvable."));
    }

    private SavCase dossierDe(String savCaseId) {
        if (!nonBlanc(savCaseId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une mission répond à un dossier SAV : choisissez le dossier de la demande"
                            + " (qui a appelé, par quel canal, à quelle heure s'y lit déjà).");
        }
        return savCases.findById(savCaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le dossier indiqué est introuvable : la mission ne peut pas répondre à"
                                + " une demande qui n'existe pas."));
    }

    /** Le rappel visé, et le refus de répondre à une question qui n'est plus posée. */
    private SoldPlanAlert rappelDe(String alertId) {
        if (!nonBlanc(alertId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucun rappel indiqué : la mission ne répond à rien.");
        }
        SoldPlanAlert a = rappels.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le rappel indiqué est introuvable : le scan des forfaits reposera la question."));
        if (a.isResolved()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce rappel est déjà fermé" + (a.isWaived() ? " (levé par " + a.getWaivedByEmail() + ")" : "")
                            + " : il n'attend plus de mission.");
        }
        return a;
    }

    private SoldPlanStep suiviDe(SoldPlanAlert rappel) {
        return suivis.findById(rappel.getSoldPlanStepId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le palier de ce rappel n'appartient plus au forfait : le scan reposera la question"
                                + " sur la ligne qui tient aujourd'hui."));
    }

    /** La mission qui court déjà sur ce dossier : À traiter, reportée ou engagée, mais pas refusée. */
    private Mission missionOuverteSurDossier(String savCaseId) {
        return missions.findBySavCaseIdOrderByCreatedAtDesc(savCaseId).stream()
                .filter(MissionService::courtEncore).findFirst().orElse(null);
    }

    private Mission missionOuverte(String alertId) {
        return missions.findByAlertIdOrderByCreatedAtDesc(alertId).stream()
                .filter(MissionService::courtEncore).findFirst().orElse(null);
    }

    private static boolean courtEncore(Mission m) {
        return m.getState() != MissionState.REFUSEE;
    }

    private MaintenanceItem papierDejaOuvert(Mission m) {
        return m.getMaintenanceItemId() == null ? null
                : documents.findById(m.getMaintenanceItemId()).orElse(null);
    }

    private String papierDe(Mission m) {
        Intervention i = m.getInterventionId() == null ? null
                : interventions.findById(m.getInterventionId()).orElse(null);
        MaintenanceItem papier = papierDejaOuvert(m);
        String sortie = i == null ? null : "sortie " + i.getNumber();
        String atelier = papier == null ? null : motDuPapier(papier) + " " + papier.getNumber();
        // Une demande peut tenir deux papiers : n'en nommer qu'un ferait croire à un
        // papier qui n'existe pas, ou en cacher un qui court.
        if (sortie != null && atelier != null) {
            return sortie + " et " + atelier;
        }
        if (sortie != null) {
            return sortie;
        }
        return atelier == null ? "aucun papier encore" : atelier;
    }

    /**
     * Le mot juste sur l'écran : un devis n'est pas un ordre, et l'atelier ne cherche
     * pas le même papier. Le mot vient de l'enum du serveur — une liste tenue ici,
     * comme avant, finirait par se contredire avec « les papiers de ce travail ».
     */
    private static String motDuPapier(MaintenanceItem item) {
        return item.getType() == null ? "papier" : item.getType().label();
    }

    private static String raisonExigee(MissionReasonRequest r, String verbe) {
        String motif = r == null ? null : trim(r.reason());
        if (motif == null || motif.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pour " + verbe + " une demande, la raison s'écrit : sans elle, plus personne"
                            + " ne peut dire six mois après ce qui a été décidé et pourquoi.");
        }
        return motif;
    }

    private void decrit(Mission m, Jwt jwt) {
        m.setDecidedBy(subject(jwt));
        m.setDecidedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
    }

    private static String subject(Jwt jwt) {
        return jwt == null ? null : jwt.getSubject();
    }

    private static String nom(Enum<?> valeur) {
        return valeur == null ? null : valeur.name().toLowerCase(Locale.ROOT);
    }

    private static String labelDe(MissionState etat) {
        return etat == null ? null : etat.label();
    }

    private static String labelDe(MissionIssue issue) {
        return issue == null ? null : issue.label();
    }

    private static boolean nonBlanc(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
