package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.DeclarationQualiteRequest;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Le dossier SAV est créé par le serveur, jamais tapé dans un navigateur.
 *
 * Trois raisons, dans l'ordre : deux postes qui saisissent en même jour peuvent
 * inventer le même numéro (le front produisait un « 1234 » aléatoire, ce qui
 * promet une collision), un numéro libre peut être écrasé par distraction, et
 * surtout le document déposé chez Higer doit se retrouver à la virgule près.
 *
 * Ce service porte aussi la liaison sortie de dépannage → dossier : clôturer une
 * intervention peut ouvrir le dossier correspondant, pré-rempli avec ce que la
 * sortie sait déjà (bus, client, technicien, panne, priorité).
 *
 * Le technicien est la raison pour laquelle cette liaison se lit ici et s'écrit
 * ici : c'est le compte de la personne (table `users`), plus une chaîne recopiée
 * à l'écran. Une sortie et le dossier qui la suit parlent du même homme, et un
 * dossier suivi par une sortie garde le nom de cette sortie. Le refus est posé
 * côté serveur, parce qu'un champ grisé à l'écran ne protège rien dès qu'une
 * requête est fabriquée à la main.
 *
 * La sortie tient aussi le statut du dossier : {@link #suivreSortie} est la seule
 * écriture qui fait avancer un dossier étape par étape derrière l'homme qui est
 * dehors. Elle ne recule pas et n'écrase jamais une décision d'atelier.
 *
 * Et un dossier ne s'efface pas pendant que quelque chose y répond encore :
 * {@link #verifierSuppression}. Cette garde devait déjà exister ; son absence a
 * laissé, dans la base du guichet, des sorties et des demandes pointant un dossier
 * qui n'est plus. Rien ne casse bruyamment : l'écran affiche un tiret à la place du
 * numéro, et la livraison d'une pièce ne sait plus pour quel dossier ouvrir sa
 * mission de pose. Une référence qui mène nulle part n'est pas une erreur, c'est un
 * silence — donc elle se refuse à l'écriture, pas à la lecture.
 */
@Service
public class SavCaseService {

    private static final int SEQUENCE_WIDTH = 3;

    /** Le déroulé d'un dossier qui suit une sortie, dans l'ordre où il se lit. */
    private static final List<SavCaseStatus> CHAINE = List.of(
            SavCaseStatus.OPEN, SavCaseStatus.IN_PROGRESS, SavCaseStatus.ON_SITE,
            SavCaseStatus.RETURNED, SavCaseStatus.CLOSED);

    /** Un seul endroit pour dire quel tronçon de numéro porte chaque famille. */
    static String code(SavCaseType type) {
        return switch (type) {
            case VISITES -> "VIS";
            case GARANTIES -> "WAR";
            case QUALITE -> "QAL";
            case PIECES -> "PIE";
            case RECLAMATIONS -> "REC";
        };
    }

    private final SavCaseRepository savCases;
    private final InterventionRepository sorties;
    /** Les demandes qui répondent au dossier, lues pour la garde de suppression. */
    private final MissionRepository demandes;
    private final TechnicianRepository technicians;
    private final TechnicianDirectory directory;
    private final ItemPartService parts;
    private final AuditService audit;

    /** La seule chose qu'une « visite périodique » puisse viser : un forfait vendu. */
    private final SoldPlanRepository forfaits;
    private final SoldPlanStepRepository paliers;

    public SavCaseService(SavCaseRepository savCases, InterventionRepository sorties,
                          MissionRepository demandes,
                          TechnicianRepository technicians, TechnicianDirectory directory,
                          ItemPartService parts, AuditService audit,
                          SoldPlanRepository forfaits, SoldPlanStepRepository paliers) {
        this.savCases = savCases;
        this.sorties = sorties;
        this.demandes = demandes;
        this.technicians = technicians;
        this.directory = directory;
        this.parts = parts;
        this.audit = audit;
        this.forfaits = forfaits;
        this.paliers = paliers;
    }

    /**
     * Numéro du prochain dossier de cette famille : SAV-WAR-2026-001.
     * L'année est celle de la création, le compteur reprend le plus HAUT numéro
     * existant de la famille — voir {@link NumberSequence}.
     */
    public String nextNumber(SavCaseType type) {
        String prefix = "SAV-" + code(type) + "-" + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(prefix, savCases.findNumbersByPrefix(prefix),
                SEQUENCE_WIDTH, savCases::existsByCaseNumberIgnoreCase);
    }

    /**
     * Dossier né d'une sortie de dépannage. Le type est GARANTIES seulement si
     * l'ordre le dit : par défaut c'est une réclamation de terrain, car une panne
     * signalée par le client est ce que le dossier raconte.
     *
     * L'homme vient de la sortie pareillement : {@code technician} est la fiche
     * affectée le jour du départ. Le dossier n'a plus à le rechoisir.
     */
    @Transactional
    public SavCase createFromIntervention(Intervention i, Technician technician, SavCaseType type, Jwt jwt) {
        SavCaseType chosen = type != null ? type : SavCaseType.RECLAMATIONS;
        SavCase c = new SavCase();
        c.setId("sav-" + UUID.randomUUID());
        c.setCaseNumber(nextNumber(chosen));
        c.setType(chosen);
        c.setTitle("Dépannage " + i.getNumber()
                + (i.getDefectCode() != null && !i.getDefectCode().isBlank() ? " — " + i.getDefectCode() : ""));
        c.setVehicleId(i.getVehicleId());
        c.setClientId(i.getClientId() != null ? i.getClientId() : "");
        c.setStatus(SavCaseStatus.OPEN);
        c.setPriority(i.getPriority());
        c.setCreatedAt(LocalDate.now());
        c.setDescription(describe(i));
        c.setTechnicianUserId(technician == null ? null : technician.getUserId());
        c.setTechnician(technician == null ? null : technician.getName());
        savCases.save(c);
        return c;
    }

    /**
     * La sortie déclarée en qualité par un clic sur sa ligne — et seulement par lui.
     *
     * La feuille de l'atelier annonçait « un problème identifié ouvre un dossier
     * qualité ». La décision du 29/10 est qu'<b>rien ne s'ouvre tout seul</b> : un
     * diagnostic qui enfanterait un dossier qualité imposerait une criticité que
     * personne n'a choisie. L'humain clique, le serveur relit.
     *
     * Le dossier naît par la même porte que le dépannage
     * ({@link #createFromIntervention}) : code défaut dans le titre, diagnostic, pièce
     * en cause et ville sous le bus dans la description, homme hérité de la sortie et
     * bloqué. Ce qui ne se relit nulle part — classification, criticité, actions — vient
     * du corps du clic, et reste vide si l'atelier ne l'a pas écrit.
     *
     * <b>Un dossier par sortie.</b> Le second clic nomme le dossier déjà ouvert au lieu
     * d'ouvrir un jumeau que l'atelier devrait fondre à la main.
     */
    @Transactional
    public SavCase declarerEnQualite(Intervention sortie, Technician technicien,
                                     DeclarationQualiteRequest declaration, Jwt jwt) {
        savCases.findFirstByDeclaredFromInterventionIdOrderByCreatedAtDesc(sortie.getId())
                .ifPresent(deja -> {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "La sortie " + sortie.getNumber() + " est déjà déclarée en qualité : le dossier "
                                    + deja.getCaseNumber() + " porte ce problème. Ouvrez-le plutôt que d'en "
                                    + "créer un second pour la même anomalie vue devant le même bus.");
                });
        SavCase c = createFromIntervention(sortie, technicien, SavCaseType.QUALITE, jwt);
        c.setTitle("Qualité vue en sortie " + sortie.getNumber() + " — "
                + (nonBlanc(sortie.getDefectCode()) != null ? sortie.getDefectCode().trim() : "sans code défaut"));
        c.setDeclaredFromInterventionId(sortie.getId());
        // Le jour où le problème est déclaré, pas le jour où la sortie a eu lieu : la
        // fiche de référence date l'anomalie de sa déclaration.
        c.setDeclarationDate(LocalDate.now());
        if (declaration != null) {
            c.setClassification(nonBlanc(declaration.classification()));
            c.setSeverity(nonBlanc(declaration.severity()));
            c.setCorrectiveActions(nonBlanc(declaration.correctiveActions()));
            c.setPreventiveActions(nonBlanc(declaration.preventiveActions()));
        }
        savCases.save(c);
        return c;
    }

    /**
     * Le dossier garantie né d'une réclamation — LA porte de filiation, et une seule.
     *
     * Le type d'un dossier ne change jamais : transformer la réclamation en claim
     * effacerait la preuve que le client s'était plaint d'abord, et ferait bouger
     * les compteurs des cinq familles après coup. Ici la réclamation reste ce
     * qu'elle est, et le claim qui part chez le constructeur porte son numéro.
     *
     * Le serveur recopie tout ce que la réclamation sait déjà — client, bus, panne,
     * priorité, homme, accord constructeur, et la trace de l'appel. Rien n'est
     * redemandé à l'atelier : deux saisies de la même panne, c'est deux versions
     * qui se ressemblent et ne se recoupent plus.
     *
     * Une réclamation n'enfante qu'un seul claim. Deux dossiers garantie pour la
     * même panne, c'est deux demandes de remboursement pour un même travail —
     * précisément ce qu'un contrôleur constructeur cherche.
     *
     * Les pièces posées pendant la sortie de cette réclamation suivent le claim :
     * c'est elles que le constructeur réclamera, et l'atelier ne doit pas les
     * rechoisir une par une.
     */
    @Transactional
    public SavCase openClaimFrom(SavCase parent, Jwt jwt) {
        if (parent.getType() != SavCaseType.RECLAMATIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + parent.getCaseNumber() + " n'est pas une réclamation du client :"
                            + " un appel en garantie se demande à partir de ce qu'il a signalé.");
        }
        savCases.findFirstByParentCaseIdAndType(parent.getId(), SavCaseType.GARANTIES).ifPresent(deja -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette réclamation a déjà donné le dossier garantie " + deja.getCaseNumber()
                            + ". Une panne ne se réclame pas deux fois : complétez ce dossier-là.");
        });

        SavCase claim = new SavCase();
        claim.setId("sav-" + UUID.randomUUID());
        claim.setType(SavCaseType.GARANTIES);
        claim.setCaseNumber(nextNumber(SavCaseType.GARANTIES));
        claim.setTitle("Claim garantie — " + parent.getTitle());
        claim.setVehicleId(parent.getVehicleId());
        claim.setClientId(parent.getClientId());
        claim.setStatus(SavCaseStatus.OPEN);
        claim.setPriority(parent.getPriority());
        claim.setCreatedAt(LocalDate.now());
        claim.setDescription(heritage(parent));
        claim.setParentCaseId(parent.getId());
        claim.setWarrantyApprovalNumber(parent.getWarrantyApprovalNumber());
        claim.setCallerName(parent.getCallerName());
        claim.setCallerChannel(parent.getCallerChannel());
        claim.setCalledAt(parent.getCalledAt());
        claim.setExpectedAction(parent.getExpectedAction());
        claim.setTechnicianUserId(parent.getTechnicianUserId());
        claim.setTechnician(parent.getTechnician());
        claim.setClaimState(ClaimState.A_DEPOSER);
        savCases.save(claim);

        Intervention sortie = latestSortie(parent);
        int rattachees = sortie == null ? 0 : parts.rattacherAuDossier(sortie.getMaintenanceItemId(), claim.getId());
        audit.record("CREATE", "SAV_CASE", claim.getId(),
                "Dossier garantie " + claim.getCaseNumber() + " ouvert depuis la réclamation "
                        + parent.getCaseNumber()
                        + (rattachees > 0 ? " — " + rattachees + " pièce(s) posée(s) rattachée(s)" : ""), jwt);
        return claim;
    }

    /** Ce que le claim hérite de la réclamation, écrit une seule fois. */
    private static String heritage(SavCase parent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ouvert depuis la réclamation ").append(parent.getCaseNumber()).append('.');
        if (parent.getCallerName() != null && !parent.getCallerName().isBlank()) {
            sb.append(" Demandé par ").append(parent.getCallerName());
            if (parent.getCallerChannel() != null) {
                sb.append(" (").append(parent.getCallerChannel().label()).append(')');
            }
            sb.append('.');
        }
        if (parent.getDescription() != null && !parent.getDescription().isBlank()) {
            sb.append(" ").append(parent.getDescription());
        }
        if (parent.getExpectedAction() != null && !parent.getExpectedAction().isBlank()) {
            sb.append(" Le client attend : ").append(parent.getExpectedAction()).append('.');
        }
        return sb.toString();
    }

    /**
     * La personne désignée sur un dossier, contrôlée puis écrite par le serveur.
     *
     * Deux situations, et elles ne se ressemblent pas :
     *  - des sorties sont rattachées au dossier : elles ont fait le travail, c'est
     *    leur homme qui signe. Un autre choix est refusé, la sortie en cause étant
     *    nommée dans le message pour que l'atelier comprenne d'où vient ce nom ;
     *  - aucune sortie : saisie d'atelier, et la personne choisie doit être un
     *    compte technicien du portail — son dossier de paie est relié ou créé au
     *    passage, taux laissés vides tant que Saphir ne les a pas donnés.
     *
     * Un {@code technicianUserId} à null ne veut pas dire « effacer » : un dossier
     * d'avant, portant un nom libre sans compte derrière, garde ce nom à l'écran
     * plutôt que de le perdre. Le lien se remplace, il ne se vide pas.
     */
    @Transactional
    public void applyTechnician(SavCase c, UUID technicianUserId, Jwt jwt) {
        Intervention sortie = latestSortie(c);
        if (sortie != null) {
            Technician tech = ficheOf(sortie);
            UUID fromSortie = tech == null ? null : tech.getUserId();
            if (technicianUserId != null && !technicianUserId.equals(fromSortie)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le dossier " + c.getCaseNumber() + " suit la sortie " + sortie.getNumber()
                                + ", menée par " + nameOf(sortie) + " : le technicien du dossier vient"
                                + " de cette sortie. Changez-le sur la sortie, pas ici.");
            }
            c.setTechnicianUserId(fromSortie);
            if (tech != null) {
                c.setTechnician(tech.getName());
            }
            return;
        }
        if (technicianUserId == null) {
            return;
        }
        Technician tech = directory.ficheFor(technicianUserId, jwt);
        c.setTechnicianUserId(tech.getUserId());
        c.setTechnician(tech.getName());
    }

    /**
     * Reprendre l'homme de la sortie qui a fait le travail.
     *
     * La fiche verrouille le champ dès qu'une sortie est au dos du dossier, et la
     * sortie partie ne se retire plus : un dossier portant le nom d'un autre que
     * celui qui est allé devant le bus ne se corrigeait alors nulle part à l'écran.
     * Cette porte est le clic d'un humain, elle nomme la sortie dont elle prend le
     * nom, et la trace reste au journal — le papier déposé chez le constructeur ne
     * se réécrit pas en silence.
     */
    @Transactional
    public SavCase reprendreTechnicienDeLaSortie(String id, Jwt jwt) {
        SavCase c = savCases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        Intervention sortie = latestSortie(c);
        if (sortie == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + c.getCaseNumber() + " ne suit aucune sortie : son technicien se choisit sur sa fiche.");
        }
        Technician tech = ficheOf(sortie);
        if (tech == null || tech.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sortie " + sortie.getNumber() + " n'a pas d'homme désigné : le dossier n'a rien à reprendre.");
        }
        String avant = nonBlanc(c.getTechnician()) == null ? "personne" : c.getTechnician();
        if (tech.getUserId().equals(c.getTechnicianUserId()) && tech.getName().equals(avant)) {
            return c;
        }
        c.setTechnicianUserId(tech.getUserId());
        c.setTechnician(tech.getName());
        c.setUpdatedAt(LocalDate.now());
        savCases.save(c);
        journal(c, "Le dossier reprend l'homme de la sortie " + sortie.getNumber() + " : "
                + avant + " → " + tech.getName(), jwt);
        return c;
    }

    /**
     * La « visite périodique » d'un dossier est celle du forfait vendu, et rien
     * d'autre.
     *
     * Avant ces deux cases, le mot désignait deux choses qui ne se connaissaient
     * pas : le rappel sorti du compteur du bus d'un côté, le papier de visite tapé à
     * l'atelier de l'autre. Rattaché au palier, le dossier de visite dit « je suis
     * la visite des 40 000 km » et le bandeau des rappels peut le lire sur sa ligne.
     *
     * Le rattachement se vérifie, jamais cru sur parole : le forfait doit exister,
     * le palier doit être l'un des siens, et le bus du dossier doit être celui où le
     * forfait a été vendu — un palier n'existe que dans le forfait où il a été signé.
     * Les deux cases voyagent ensemble : un forfait sans palier ne répond pas à la
     * question « quelle visite ? ».
     *
     * Hors famille « visites », rien n'est écrit. Le bloc ne se remplit pas sur une
     * réclamation : une visite héritée d'une plainte se relirait comme une preuve.
     */
    @Transactional
    public void marquerLaVisite(SavCase c, String soldPlanId, String planStepId) {
        if (c.getType() != SavCaseType.VISITES) {
            c.setSoldPlanId(null);
            c.setPlanStepId(null);
            return;
        }
        String forfait = nonBlanc(soldPlanId);
        String palier = nonBlanc(planStepId);
        if (forfait == null && palier == null) {
            c.setSoldPlanId(null);
            c.setPlanStepId(null);
            return;
        }
        if (forfait == null || palier == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + c.getCaseNumber() + " annonce une visite périodique à moitié : "
                            + "choisissez le forfait ET le palier, ou ne rattachez rien.");
        }
        SoldPlan vendu = forfaits.findById(forfait).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le dossier " + c.getCaseNumber() + " ne peut pas viser le forfait " + forfait
                                + " : il n'existe pas. Une visite périodique se rattache à un forfait vendu,"
                                + " pas à un numéro tapé à la main."));
        if (!Objects.equals(vendu.getVehicleId(), c.getVehicleId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le forfait « " + vendu.getPlan().getName() + " » a été vendu sur un autre bus que"
                            + " celui du dossier " + c.getCaseNumber() + " : la visite périodique d'un bus"
                            + " ne se rattache pas au suivi d'un autre bus.");
        }
        String client = nonBlanc(c.getClientId());
        if (client != null && !Objects.equals(vendu.getClientId(), client)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le forfait « " + vendu.getPlan().getName() + " » n'a pas été vendu au client du"
                            + " dossier " + c.getCaseNumber() + " : vérifiez le client avant de promettre"
                            + " une visite couverte.");
        }
        SoldPlanStep suivi = paliers.findBySoldPlanIdAndStepId(forfait, palier).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce palier n'appartient pas au forfait « " + vendu.getPlan().getName() + " » :"
                                + " choisissez-le dans la liste des paliers de ce forfait."));
        c.setSoldPlanId(vendu.getId());
        c.setPlanStepId(suivi.getStep().getId());
    }

    /**
     * Une sortie rejoint un dossier qui existe déjà : le dossier prend son homme.
     *
     * Deux cas licites et un refus. Le dossier ne désignait personne → il hérite.
     * Il désignait déjà la même personne → rien à écrire. Il désignait quelqu'un
     * d'autre → refus : deux noms sur le même travail veulent dire que l'un des
     * deux est faux, et c'est précisément ce que le module doit empêcher.
     */
    @Transactional
    public void adoptTechnicianFrom(SavCase dossier, Intervention sortie, Technician tech) {
        if (tech == null || tech.getUserId() == null) {
            return;
        }
        UUID actuel = dossier.getTechnicianUserId();
        if (tech.getUserId().equals(actuel)) {
            return;
        }
        if (actuel != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getCaseNumber() + " est déjà suivi par "
                            + (dossier.getTechnician() == null ? "un autre technicien" : dossier.getTechnician())
                            + ", alors que la sortie " + sortie.getNumber() + " est menée par " + tech.getName()
                            + ". Ce n'est pas la même personne : rattachez la sortie à un autre dossier,"
                            + " ou corrigez le technicien de la sortie avant de la clôturer.");
        }
        dossier.setTechnicianUserId(tech.getUserId());
        dossier.setTechnician(tech.getName());
        savCases.save(dossier);
    }

    /**
     * Le dossier suit la sortie, et seulement en avant.
     *
     * Ce que l'atelier voit à l'écran : « Ouvert » quand la panne est appelée,
     * « En cours » dès que la voiture et l'homme sont affectés, « Technicien sur
     * place » quand il est arrivé, « Retour à l'atelier » quand il rentre,
     * « Clôturé » quand le chef valide la sortie. Personne ne retape le statut du
     * dossier — c'est ce qui faisait mentir la colonne.
     *
     * Trois garde-fous, dans l'ordre :
     *  - une étape ne passe jamais devant une décision d'atelier (attente pièces,
     *    accord, refus) ; seule la clôture vient au-dessus, parce qu'elle est la
     *    fin du travail ;
     *  - le statut ne recule pas : une sortie plus ancienne qui écrirait après coup
     *    ne peut pas remettre un dossier fini en route. La sortie en cours du dossier
     *    est la seule autorisée à le rouvrir — elle parle de son propre travail ;
     *  - un dossier ne se dit pas clôturé si une autre sortie rattachée est encore
     *    dehors. Ce serait écrire une fausseté dans une pièce déposée chez Higer.
     *
     * Un refus d'avancer laisse une ligne au journal : l'atelier lit pourquoi le
     * dossier n'a pas bougé, au lieu de croire que le bouton n'a rien fait.
     */
    @Transactional
    public void suivreSortie(Intervention sortie, Jwt jwt) {
        SavCaseStatus voulu = etatPour(sortie.getStatus());
        if (voulu == null) {
            return;
        }
        SavCase dossier = dossierDe(sortie);
        if (dossier == null) {
            return;
        }
        SavCaseStatus actuel = dossier.getStatus();
        if (actuel == null) {
            actuel = SavCaseStatus.OPEN;
        }
        if (actuel == voulu) {
            return;
        }
        Intervention laPlusRecente = latestSortie(dossier);
        boolean laSortieQuiParle = laPlusRecente != null && laPlusRecente.getId().equals(sortie.getId());

        if (voulu == SavCaseStatus.CLOSED) {
            Intervention dehors = autreSortieOuverte(dossier, sortie.getId());
            if (dehors != null) {
                journal(dossier, "Le dossier " + dossier.getCaseNumber() + " ne se ferme pas : la sortie "
                        + dehors.getNumber() + " est encore dehors. Clôturez-la, le dossier se fermera avec elle.",
                        jwt);
                return;
            }
        } else if (estDecision(actuel)) {
            journal(dossier, "Le dossier " + dossier.getCaseNumber() + " reste à « " + label(actuel)
                    + " » : c'est une décision d'atelier, une étape de la sortie " + sortie.getNumber()
                    + " ne l'écrase pas.", jwt);
            return;
        } else if (rang(voulu) < rang(actuel) && !laSortieQuiParle) {
            journal(dossier, "Le dossier " + dossier.getCaseNumber() + " ne revient pas en arrière : la sortie "
                    + sortie.getNumber() + " n'est pas la plus récente rattachée au dossier.", jwt);
            return;
        }

        dossier.setStatus(voulu);
        dossier.setUpdatedAt(LocalDate.now());
        daterCloture(dossier);
        savCases.save(dossier);
        journal(dossier, "Statut du dossier " + dossier.getCaseNumber() + " : « " + label(actuel) + " » → « "
                + label(voulu) + " », la sortie " + sortie.getNumber() + " avance avec lui.", jwt);
    }

    /**
     * Le jour de la clôture, écrit derrière le statut et jamais à la main.
     *
     * Le papier de référence demande une « date de clôture » dans trois de ses
     * onglets, et {@code updatedAt} n'est pas cette date : il bouge à chaque faute
     * corrigée dans un texte. Un dossier fermé en juin puis relu en octobre se
     * fermait en octobre.
     *
     * Elle se retire si le dossier repart dehors (la sortie la plus récente peut
     * le rouvrir) : laisser une date de fermeture sur un travail en cours, c'est
     * dater un mensonge.
     */
    public static void daterCloture(SavCase dossier) {
        if (dossier.getStatus() == SavCaseStatus.CLOSED) {
            if (dossier.getClosedAt() == null) {
                dossier.setClosedAt(LocalDate.now());
            }
        } else if (dossier.getClosedAt() != null) {
            dossier.setClosedAt(null);
        }
    }

    /**
     * Un dossier ne s'efface pas pendant que quelque chose y répond encore.
     *
     * Une sortie (DP-) comme une demande (MT-) porte l'identifiant du dossier qui
     * l'a vue naître, et rien ne le lui retire : il n'y a pas de clé étrangère ici,
     * le lien est tenu par le service. Effacer le dossier détache donc la ligne sans
     * avertir personne. Ce n'est pas un accident visible — c'est un travail qui
     * devient muet : le numéro rendu à l'écran passe à « — », et surtout la
     * réception d'une pièce ne sait plus sur quel dossier ouvrir sa mission de pose
     * (V32 lit {@code sav_case_id} pour cela).
     *
     * Le refus nomme ce qui retient le dossier : celui qui veut effacer doit d'abord
     * savoir où clore.
     */
    @Transactional(readOnly = true)
    public void verifierSuppression(SavCase dossier) {
        List<String> retient = new ArrayList<>();
        for (Intervention sortie : sorties.findBySavCaseIdOrderByReportedAtAsc(dossier.getId())) {
            retient.add("la sortie " + sortie.getNumber());
        }
        demandes.findBySavCaseIdOrderByCreatedAtDesc(dossier.getId())
                .forEach(demande -> retient.add("la demande " + demande.getNumber()));
        if (!retient.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getCaseNumber() + " ne se supprime pas : "
                            + String.join(" et ", retient) + " y répondent encore. Clôturez-les d'abord, "
                            + "ils gardent la trace du travail fait.");
        }
    }

    /** De quelle étape de la sortie le dossier hérite. « reportée » ne change encore rien. */
    private static SavCaseStatus etatPour(InterventionStatus etatSortie) {
        return switch (etatSortie) {
            case REPORTED -> null;
            case DISPATCHED -> SavCaseStatus.IN_PROGRESS;
            case ON_SITE, IN_PROGRESS, REPAIRED, TOWED -> SavCaseStatus.ON_SITE;
            case RETURNED -> SavCaseStatus.RETURNED;
            case CLOSED -> SavCaseStatus.CLOSED;
        };
    }

    /** Attente pièces, accord, refus : ce que l'atelier a décidé, pas ce qu'une sortie traverse. */
    private static boolean estDecision(SavCaseStatus status) {
        return status == SavCaseStatus.PENDING_PARTS
                || status == SavCaseStatus.APPROVED
                || status == SavCaseStatus.REJECTED;
    }

    /** Position dans le déroulé ; hors chaîne (décision) = après tout le monde, donc jamais dépassée. */
    private static int rang(SavCaseStatus status) {
        int i = CHAINE.indexOf(status);
        return i < 0 ? CHAINE.size() : i;
    }

    /** Le dossier rattaché à la sortie, s'il existe encore. */
    private SavCase dossierDe(Intervention sortie) {
        return sortie.getSavCaseId() == null ? null
                : savCases.findById(sortie.getSavCaseId()).orElse(null);
    }

    /** Une autre sortie du dossier est-elle encore en cours ? (celle qui parle est exclue) */
    private Intervention autreSortieOuverte(SavCase dossier, String sortieId) {
        if (dossier.getId() == null) {
            return null;
        }
        return sorties.findBySavCaseIdOrderByReportedAtAsc(dossier.getId()).stream()
                .filter(other -> !other.getId().equals(sortieId))
                .filter(other -> InterventionStatus.OPEN.contains(other.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private void journal(SavCase dossier, String detail, Jwt jwt) {
        audit.record("UPDATE", "SAV_CASE", dossier.getId(), detail, jwt);
    }

    /** Une case qui dit quelque chose : les blancs laissés par l'écran ne comptent pas. */
    private static String nonBlanc(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    /** Le mot que l'atelier lit à l'écran, écrit une seule fois côté serveur. */
    private static String label(SavCaseStatus status) {
        return switch (status) {
            case OPEN -> "Ouvert";
            case IN_PROGRESS -> "En cours";
            case ON_SITE -> "Technicien sur place";
            case PENDING_PARTS -> "Attente pièces";
            case APPROVED -> "Approuvé";
            case REJECTED -> "Rejeté";
            case RETURNED -> "Retour à l'atelier";
            case CLOSED -> "Clôturé";
        };
    }

    /** La sortie la plus récente rattachée au dossier, ou null si aucune. */
    private Intervention latestSortie(SavCase c) {
        if (c.getId() == null) {
            return null;
        }
        List<Intervention> rows = sorties.findBySavCaseIdOrderByReportedAtAsc(c.getId());
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private Technician ficheOf(Intervention sortie) {
        return sortie.getTechnicianId() == null ? null
                : technicians.findById(sortie.getTechnicianId()).orElse(null);
    }

    private String nameOf(Intervention sortie) {
        Technician tech = ficheOf(sortie);
        return tech == null ? "un technicien sans fiche au dossier" : tech.getName();
    }

    /** Ce que la sortie sait déjà, écrit une seule fois : l'atelier ne le retape pas. */
    private static String describe(Intervention i) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sortie de dépannage ").append(i.getNumber());
        if (i.getLocationCity() != null && !i.getLocationCity().isBlank()) {
            sb.append(" à ").append(i.getLocationCity());
        }
        if (i.getLocationAddress() != null && !i.getLocationAddress().isBlank()) {
            sb.append(" (").append(i.getLocationAddress()).append(')');
        }
        if (i.getDescription() != null && !i.getDescription().isBlank()) {
            sb.append(". Panne signalée : ").append(i.getDescription());
        }
        if (i.getDiagnosis() != null && !i.getDiagnosis().isBlank()) {
            sb.append(". Diagnostic : ").append(i.getDiagnosis());
        }
        if (i.getCausingPart() != null && !i.getCausingPart().isBlank()) {
            sb.append(". Pièce en cause : ").append(i.getCausingPart());
        }
        if (i.getResultNote() != null && !i.getResultNote().isBlank()) {
            sb.append(". Intervenu : ").append(i.getResultNote());
        }
        return sb.toString();
    }
}
