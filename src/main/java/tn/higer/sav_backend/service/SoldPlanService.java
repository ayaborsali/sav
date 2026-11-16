package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SoldPlanDtos.ChargedLineDto;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanRequest;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanResponse;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanSummary;
import tn.higer.sav_backend.model.BusType;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanOperation;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Les forfaits vendus : un plan du catalogue, un client, un bus, des dates.
 *
 * C'est ici que se décident les trois règles qui, ratées, coûtent de l'argent ou
 * du temps à l'atelier :
 *
 * 1. le bus doit appartenir au client à qui on vend le forfait — sinon le suivi
 *    d'un bus se range chez un autre client et la facturation part de travers ;
 * 2. un seul forfait ACTIF du même plan pour le même bus sur une période qui se
 *    chevauche — deux forfaits actifs, c'est deux fois les mêmes paliers à faire,
 *    et personne ne sait lequel l'atelier honore ;
 * 3. à la vente, chaque palier du plan reçoit sa ligne de suivi à TODO. Sans
 *    cette génération, « paliers faits 3 sur 8 » ne veut rien dire et le palier
 *    fait se devine.
 *
 * Un forfait vendu ne change ni de plan, ni de client, ni de bus. Ce ne sont pas
 * des cases d'un formulaire : ce sont les trois termes de l'engagement. Le
 * modifier, c'est annuler le forfait et en vendre un autre, avec une piste
 * d'audit qui dit les deux gestes.
 *
 * On n'efface jamais : une suppression laisse des paliers à DONE et des documents
 * liés sans parent. L'annulation est un statut, et un forfait annulé libère la
 * période pour une nouvelle vente.
 *
 * Depuis la phase C, ce service est aussi l'endroit où un document de maintenance
 * (devis, BL, ordre de travail) se rattache à un forfait et fait avancer un
 * palier. C'est ici et pas dans le contrôleur de maintenance que la règle tient,
 * parce que le palier est une ligne du forfait : un seul service écrit cet
 * avancement, sinon deux écrans le contrediraient.
 *
 * Depuis H1, une sortie d'entretien peut aussi faire un palier : sa clôture passe par
 * {@link #papierPourPalier}, qui crée le document puis demande le même avancement.
 * Le geste change de porte d'entrée, la règle ne change pas — et ce service ne parle
 * jamais à la machine à états du dépannage, il ne reçoit que des valeurs.
 */
@Service
public class SoldPlanService {

    /**
     * Un nombre au début d'une case de quantité : « 23 L », « 2 », « 6.5 kg ».
     * La virgule française est admise (« 0,5 L »), les deux nombres d'une
     * intervalle (« 5-6 L ») ne le sont pas — ils se lisent à la main.
     */
    private static final Pattern NOMBRE = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private final SoldPlanRepository soldPlans;
    private final MaintenancePlanRepository plans;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final MaintenanceRepository documents;
    private final AuditService audit;
    private final SoldPlanAlertService rappels;

    /** Le papier né d'une sortie porte le même taux de TVA qu'un papier saisi à l'écran. */
    private final double tvaRate;

    public SoldPlanService(SoldPlanRepository soldPlans, MaintenancePlanRepository plans,
                           VehicleRepository vehicles, ClientRepository clients,
                           MaintenanceRepository documents, AuditService audit,
                           SoldPlanAlertService rappels,
                           @Value("${app.tva.rate:19}") double tvaRate) {
        this.soldPlans = soldPlans;
        this.plans = plans;
        this.vehicles = vehicles;
        this.clients = clients;
        this.documents = documents;
        this.audit = audit;
        this.rappels = rappels;
        this.tvaRate = tvaRate;
    }

    /**
     * La liste, avec trois filtres facultatifs. Les filtres sont appliqués en
     * Java plutôt que dans une requête à paramètres vides : un « status is null »
     * sur un paramètre d'énumération n'est pas typable par Hibernate, et le
     * portefeuille de forfaits vendus reste petit — c'est le catalogue qui compte
     * des dizaines de lignes, pas les ventes.
     */
    @Transactional(readOnly = true)
    public List<SoldPlanSummary> list(String vehicleId, String clientId, String status, boolean pricesVisible) {
        SoldPlanStatus wanted = parseStatus(status, "filtrer");
        List<SoldPlan> rows = soldPlans.findAllByOrderByStartDateDesc().stream()
                .filter(p -> vehicleId == null || vehicleId.isBlank() || vehicleId.trim().equals(p.getVehicleId()))
                .filter(p -> clientId == null || clientId.isBlank() || clientId.trim().equals(p.getClientId()))
                .filter(p -> wanted == null || p.getStatus() == wanted)
                .toList();
        Names names = namesOf(rows);
        return rows.stream()
                .map(p -> SoldPlanSummary.of(p, names.clientName(p.getClientId()),
                        names.vehicleVin(p.getVehicleId()), names.vehicleRegistration(p.getVehicleId()),
                        pricesVisible))
                .toList();
    }

    @Transactional(readOnly = true)
    public SoldPlanResponse get(String id, boolean pricesVisible) {
        SoldPlan sold = require(id);
        return response(sold, pricesVisible);
    }

    @Transactional
    public SoldPlanResponse create(SoldPlanRequest request, Jwt jwt, boolean pricesVisible) {
        String planId = requireId(request.planId(), "le plan de maintenance");
        String clientId = requireId(request.clientId(), "le client");
        String vehicleId = requireId(request.vehicleId(), "le bus");
        SoldPlanStatus demande = parseStatus(request.status(), "vendre");
        if (demande != null && demande != SoldPlanStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un forfait nait actif : il se termine ou s'annule après la vente, pas au moment de la vente.");
        }

        // La forme de la demande d'abord, les fiches ensuite : un écran qui oublie la
        // date de début doit lire « il manque la date », pas « client introuvable ».
        LocalDate start = requireDate(request.startDate(), "la date de début du forfait");
        LocalDate end = request.endDate();
        if (end != null && end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fin du forfait (" + end + ") est avant son début (" + start + ").");
        }

        Client client = clients.findById(clientId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Ce client est introuvable : vend le forfait à un client déjà enregistré."));
        Vehicle vehicle = vehicles.findById(vehicleId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Ce bus est introuvable : le forfait se vend sur un bus du portefeuille."));
        MaintenancePlan plan = plans.findById(planId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Ce plan de maintenance est introuvable : choisis-le dans le catalogue."));

        if (plan.getSteps().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le plan « " + plan.getName() + " » n'a aucun palier : il n'y a rien à vendre ni à suivre. "
                            + "Complète la grille du catalogue avant de proposer ce forfait.");
        }
        if (!plan.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le plan « " + plan.getName() + " » est retiré du catalogue : on ne le vend plus. "
                            + "Il reste lisible pour les forfaits déjà vendus.");
        }
        if (!clientId.equals(vehicle.getClientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bus " + label(vehicle) + " n'appartient pas à « " + client.getName()
                            + " ». Un forfait se vend au client qui possède le bus — sinon son suivi part chez un autre.");
        }
        verifieLaGamme(vehicle, plan);
        refuseLaMemePeriode(vehicle, plan, start, end);

        SoldPlan sold = new SoldPlan();
        sold.setId("sp-" + UUID.randomUUID());
        sold.setPlan(plan);
        sold.setClientId(clientId);
        sold.setVehicleId(vehicleId);
        sold.setStartDate(start);
        sold.setEndDate(end);
        sold.setPriceHt(MaintenancePlanService.money(request.priceHt(), "prix du forfait"));
        sold.setStatus(SoldPlanStatus.ACTIVE);
        sold.setCreatedAt(LocalDateTime.now());
        sold.setSteps(paliersAFAire(plan, sold));

        soldPlans.save(sold);
        audit.record("CREATE", "SOLD_PLAN", sold.getId(),
                "Vente du forfait « " + plan.getName() + " » à « " + client.getName() + " » pour le bus "
                        + label(vehicle) + " (" + sold.getSteps().size() + " paliers, du " + start
                        + (end == null ? " jusqu'à clôture" : " au " + end) + ")", jwt);
        return response(sold, client.getName(), vehicle.getVin(), vehicle.getRegistration(), pricesVisible);
    }

    /**
     * Correction d'un forfait : les dates, le prix, et la sortie d'ACTIF vers
     * TERMINÉ ou ANNULÉ. Plan, client et bus sont figés (voir le commentaire de la
     * classe) ; un forfait fermé ne se rouvre pas, parce que le rouvrir remettrait
     * en jeu des paliers déjà comptés comme faits et relancerait une facturation
     * close.
     */
    @Transactional
    public SoldPlanResponse update(String id, SoldPlanRequest request, Jwt jwt, boolean pricesVisible) {
        SoldPlan sold = require(id);
        freeze("le plan", request.planId(), sold.getPlan().getId(), sold);
        freeze("le client", request.clientId(), sold.getClientId(), sold);
        freeze("le bus", request.vehicleId(), sold.getVehicleId(), sold);

        LocalDate start = request.startDate() != null ? request.startDate() : sold.getStartDate();
        LocalDate end = request.endDate() != null ? request.endDate() : sold.getEndDate();
        if (end != null && end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fin du forfait (" + end + ") est avant son début (" + start + ").");
        }

        SoldPlanStatus wanted = parseStatus(request.status(), "fermer");
        if (wanted != null && wanted != sold.getStatus()) {
            if (sold.getStatus() != SoldPlanStatus.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce forfait est déjà " + fr(sold.getStatus()) + " : on ne le rouvre pas. "
                                + "La période est libre, vends un nouveau forfait pour corriger.");
            }
            sold.setStatus(wanted);
            // Le forfait sort d'ACTIF : ses rappels ouverts se ferment, un engagement
            // clos ne se rappelle plus.
            rappels.forfaitFerme(id);
        }

        sold.setStartDate(start);
        sold.setEndDate(end);
        if (request.priceHt() != null) {
            sold.setPriceHt(MaintenancePlanService.money(request.priceHt(), "prix du forfait"));
        }

        soldPlans.save(sold);
        audit.record("UPDATE", "SOLD_PLAN", id,
                "Correction du forfait « " + sold.getPlan().getName() + " » (du " + sold.getStartDate()
                        + (sold.getEndDate() == null ? "" : " au " + sold.getEndDate()) + ", statut "
                        + fr(sold.getStatus()) + ")", jwt);
        return response(sold, pricesVisible);
    }

    /**
     * Annulation : un statut, pas une suppression. Les paliers gardent leur état
     * et les documents liés restent intacts ; la période se libère pour une
     * nouvelle vente du même plan sur le même bus.
     */
    @Transactional
    public void cancel(String id, Jwt jwt) {
        SoldPlan sold = require(id);
        if (sold.getStatus() != SoldPlanStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce forfait est déjà " + fr(sold.getStatus()) + " : il n'y a rien à annuler.");
        }
        sold.setStatus(SoldPlanStatus.CANCELLED);
        soldPlans.save(sold);
        rappels.forfaitFerme(id);
        audit.record("CANCEL", "SOLD_PLAN", id,
                "Annulation du forfait « " + sold.getPlan().getName() + " » sur le bus "
                        + sold.getVehicleId() + " (" + doneCount(sold) + " paliers faits sur "
                        + sold.getSteps().size() + ")", jwt);
    }

    // ---------------------------------------------------- lignes à charger (phase C)

    /**
     * Ce que le palier demande de faire, présenté en lignes de document : référence,
     * désignation, quantité, prix. L'écran de l'atelier pose ces lignes dans le
     * devis ou le BL, les relit, les corrige si besoin, puis enregistre.
     *
     * Les lignes sont recopiées, pas pointées. Une fois dans le document, elles n'ont
     * plus aucun lien avec le catalogue : changer un prix au catalogue ne réécrit pas
     * un BL déjà livré au client. C'est la raison pour laquelle aucune identité
     * d'opération n'est renvoyée ici.
     *
     * Un prix vide est signalé ({@code priceMissing}) et ne bloque rien : les prix
     * arrivent après le plan, et un palier non chiffré se charge et se fait quand même.
     */
    @Transactional(readOnly = true)
    public List<ChargedLineDto> lignesACharger(String soldPlanId, String planStepId, boolean pricesVisible) {
        SoldPlan sold = require(soldPlanId);
        MaintenancePlanStep palier = palier(sold, planStepId).getStep();
        return dansLordreDuPlan(palier).stream()
                .map(operation -> ChargedLineDto.of(operation.getReference(),
                        designation(operation), quantite(operation.getQuantityText()),
                        operation.getUnitPriceHt(), operation.getQuantityText(), pricesVisible))
                .toList();
    }

    /**
     * L'ordre de la grille Higer, pas l'ordre des lignes en base : la croix d'une
     * opération est rattachée à un palier sans numéro d'ordre, et l'atelier suit le
     * document de référence ligne par ligne, dans l'ordre où il l'a écrit.
     */
    private static List<MaintenancePlanOperation> dansLordreDuPlan(MaintenancePlanStep palier) {
        List<MaintenancePlanOperation> ordre = palier.getPlan().getOperations();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < ordre.size(); i++) {
            positions.put(ordre.get(i).getId(), i);
        }
        return palier.getOperations().stream()
                .sorted(Comparator.comparingInt(operation -> positions.getOrDefault(operation.getId(), ordre.size())))
                .toList();
    }

    /**
     * La désignation telle qu'elle ira sur le bon de travail : l'action du plan
     * devant l'article (« contrôler — Usure des plaquettes »). Le verbe se perd si
     * on ne le recopie pas, et « 10 000 km » ne dit rien à l'atelier sans savoir
     * s'il faut remplacer, contrôler ou graisser.
     */
    private static String designation(MaintenancePlanOperation operation) {
        String article = notBlank(operation.getArticle()) ? operation.getArticle()
                : notBlank(operation.getOrgan()) ? operation.getOrgan() : null;
        String action = operation.getOperationType() == null ? null : operation.getOperationType().label();
        if (article == null) {
            return action;
        }
        return action == null ? article : action + " — " + article;
    }

    /**
     * Le plan Higer écrit la quantité et son unité dans la même case ; le document
     * de maintenance calcule un total, il lui faut le nombre. La règle est unique et
     * annoncée : un seul nombre dans la case, on le prend (« 23 L » → 23, « 0,5 L »
     * → 0.5) ; deux nombres (« 5-6 L »), on ne devine pas — {@code quantity} reste
     * vide, le total de la ligne est nul, et l'atelier complète à la main avec le
     * texte sous les yeux.
     */
    static Double quantite(String quantityText) {
        if (quantityText == null || quantityText.isBlank()) {
            return null;
        }
        Matcher matcher = NOMBRE.matcher(quantityText);
        Double valeur = null;
        while (matcher.find()) {
            if (valeur != null) {
                return null;
            }
            valeur = Double.parseDouble(matcher.group().replace(',', '.'));
        }
        return valeur;
    }

    // -------------------------------------------- le document fait avancer le palier

    /**
     * Vérification AVANT écriture du document : elle doit pouvoir refuser un
     * rattachement sans laisser un devis à moitié enregistré derrière lui.
     *
     * Quatre règles, dans l'ordre où l'erreur se présente à l'écran :
     * 1. un palier se choisit dans un forfait — le seul {@code planStepId} ne veut
     *    rien dire, plusieurs plans ont un palier au même libellé ;
     * 2. le bus du document est le bus du forfait : un BL rendu sur un autre bus ne
     *    doit pas décompter les paliers de celui-ci ;
     * 3. <b>un devis peut nommer un palier</b> (correction du 02/11) : le forfait ne
     *    vend que la <b>main-d'œuvre</b>. Ce que l'article 5 du contrat exclut — la
     *    pièce fournie par Saphir, le consommable, le déplacement, le travail
     *    correctif, la prestation en plus — se chiffre au client, et le devis est le
     *    seul papier qui demande son accord. Le devis reste une <b>référence</b> au
     *    palier : il ne le décompte pas (voir {@link #appliquerSuivi}), sinon la
     *    facture d'une pièce effacerait la visite ;
     * 4. le palier doit appartenir à ce forfait, et n'être déjà fait par aucun autre
     *    document non annulé ({@code 409}) — deux BL pour la même visite des
     *    45 000 km, c'est un palier compté deux fois chez le client. Les devis en
     *    sont dispensés : ils chiffrent, ils ne travaillent pas.
     */
    @Transactional(readOnly = true)
    public void verifierRattachement(MaintenanceItem document) {
        String soldPlanId = document.getSoldPlanId();
        String planStepId = document.getPlanStepId();
        if (planStepId != null && soldPlanId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le palier se choisit dans un forfait vendu : indique d'abord le forfait, "
                            + "puis le palier que ce document exécute.");
        }
        if (soldPlanId == null) {
            return;
        }
        SoldPlan sold = require(soldPlanId);
        if (sold.getStatus() == SoldPlanStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le forfait « " + sold.getPlan().getName() + " » est annulé : on ne peut plus y rattacher "
                            + "un document. Vends le forfait qui couvre aujourd'hui ce bus.");
        }
        if (!sold.getVehicleId().equals(document.getVehicleId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le document « " + document.getNumber() + " » n'est pas sur le bus "
                            + busDuForfait(sold.getVehicleId()) + " du forfait « "
                            + sold.getPlan().getName() + " ». Un forfait se suit sur un seul bus : change le bus "
                            + "du document ou rattache-le à un autre forfait.");
        }
        if (planStepId != null) {
            SoldPlanStep suivi = palier(sold, planStepId);
            if (document.getType() != MaintenanceType.DEVIS) {
                refuserSiDejaFait(suivi, document.getId());
            }
        }
    }

    /**
     * Le document est enregistré : le suivi du palier le suit.
     *
     * Le palier passe à {@code DONE} quand le document est LIVRÉ ou TERMINÉ — c'est
     * le moment où l'atelier a réellement fait la visite et où le client la paie.
     * Il repasse à {@code TODO} quand le document est annulé ou qu'il quitte le
     * palier. Un document passé de « livré » à « brouillon » ne défait pas le
     * palier : la visite a eu lieu, une correction de papier ne l'efface pas, et
     * l'annulation du document reste le geste qui dit « en fait non ».
     *
     * Le compteur relevé ({@code mileageAtService}) n'est pas effacé au retour à
     * TODO : il décrit le bus à ce moment-là, pas le document.
     *
     * <b>Le devis ne décompte jamais un palier</b> (correction du 02/11) : il nomme le
     * palier pour dire ce qu'il facture <i>en plus</i> de la main-d'œuvre vendue — la
     * pièce, le consommable, le déplacement, le correctif. La visite se décompte par le
     * papier qui la travaille : l'ordre de travail, ou la sortie d'entretien.
     */
    @Transactional
    public void appliquerSuivi(MaintenanceItem document, String ancienSoldPlanId, String ancienPlanStepId,
                               Jwt jwt) {
        boolean aChangeDePalier = !Objects.equals(document.getPlanStepId(), ancienPlanStepId)
                || !Objects.equals(document.getSoldPlanId(), ancienSoldPlanId);
        if (aChangeDePalier) {
            remettreAFaire(ancienSoldPlanId, ancienPlanStepId, document.getId(), document.getNumber(), jwt);
        }
        if (document.getSoldPlanId() == null || document.getPlanStepId() == null
                || document.getType() == MaintenanceType.DEVIS) {
            return;
        }
        SoldPlanStep suivi = palier(require(document.getSoldPlanId()), document.getPlanStepId());
        if (estLivraison(document.getStatus())) {
            marquerFait(suivi, document, jwt);
        } else if (document.getStatus() == MaintenanceStatus.CANCELLED) {
            remettreAFaire(suivi, document.getId(), document.getNumber(), jwt);
        }
    }

    /**
     * Le document va être supprimé du portail : le palier qu'il avait fait se
     * libère, sinon le forfait resterait amputé d'une visite que plus aucun papier
     * ne justifie.
     */
    @Transactional
    public void relacherLiens(MaintenanceItem document, Jwt jwt) {
        remettreAFaire(document.getSoldPlanId(), document.getPlanStepId(), document.getId(),
                document.getNumber(), jwt);
    }

    // ------------------------------------------- la sortie d'entretien fait le palier

    /**
     * Ce qu'une sortie sait, et que le papier d'entretien doit porter. Passing des
     * valeurs plutôt que l'entité {@code Intervention} : le suivi des forfaits ne doit
     * pas dépendre de la machine à états du dépannage, sinon écrire une sortie
     * d'entretien exigerait de faire tourner les deux modules ensemble.
     */
    public record SortiePourPalier(String soldPlanId, String planStepId, String vehicleId,
                                   String clientId, String sortieNumber, String technicien,
                                   Long compteurBus) {}

    /**
     * Résultat de la clôture côté forfait : le papier créé — ou rien, avec la phrase
     * que l'atelier lira au journal de la sortie pour comprendre pourquoi rien n'a été
     * coché.
     */
    public record PapierDePalier(String documentId, String documentNumber, String note) {}

    /**
     * La sortie d'entretien est rentrée et clôturée : le palier qu'elle est allée
     * faire se coche, et le papier qui le prouve est créé ici.
     *
     * Le document est un ordre de travail à statut « livré », parce que c'est ce
     * statut qui, déjà, fait avancer un palier — la sortie n'invente pas une deuxième
     * règle d'avancement. Ses lignes viennent du catalogue, avec un prix unitaire à
     * zéro : la visite est incluse au forfait, le client l'a payée d'avance, et un
     * papier qui porterait un prix serait une double facture déguisée.
     *
     * Cette méthode ne bloque jamais une clôture. Un palier déjà fait par un autre
     * document, un forfait devenu inactif, une ligne de suivi qui a quitté le forfait :
     * dans chacun de ces cas le papier n'est pas créé et la raison est écrite, parce
     * qu'un technicien qui est rentré ne va pas rester avec une sortie impossible à
     * clôturer. Le papier saisi à la main depuis l'écran Maintenance reste, lui, la
     * porte normale pour un bus déjà rentré.
     */
    @Transactional
    public PapierDePalier papierPourPalier(SortiePourPalier sortie, Jwt jwt) {
        SoldPlan sold = soldPlans.findById(sortie.soldPlanId()).orElse(null);
        if (sold == null) {
            return new PapierDePalier(null, null,
                    "Aucun papier créé : le forfait de cette sortie n'existe plus dans le portail.");
        }
        if (sold.getStatus() != SoldPlanStatus.ACTIVE) {
            return new PapierDePalier(null, null,
                    "Aucun papier créé : le forfait « " + sold.getPlan().getName() + " » est "
                            + fr(sold.getStatus()) + ". Le palier reste sans suivi ; traites-le sur un document "
                            + "saisi à la main.");
        }
        if (!sold.getVehicleId().equals(sortie.vehicleId())) {
            return new PapierDePalier(null, null,
                    "Aucun papier créé : la sortie " + sortie.sortieNumber() + " n'a pas été sur le bus "
                            + busDuForfait(sold.getVehicleId()) + " de ce forfait. Un palier ne se décompte "
                            + "que sur le bus engagé.");
        }
        SoldPlanStep suivi = suivi(sold, sortie.planStepId()).orElse(null);
        if (suivi == null) {
            return new PapierDePalier(null, null,
                    "Aucun papier créé : ce palier n'appartient plus au forfait « "
                            + sold.getPlan().getName() + " ».");
        }
        MaintenanceItem dejaFait = auteurDuPalier(suivi);
        if (dejaFait != null) {
            return new PapierDePalier(null, null,
                    "Palier « " + suivi.getStep().getLabel() + " » déjà fait par le document « "
                            + dejaFait.getNumber() + " » : aucune opération créée, une même visite ne se paie "
                            + "pas deux fois.");
        }

        String palier = suivi.getStep().getLabel();
        MaintenanceItem m = new MaintenanceItem();
        m.setId("maint-" + UUID.randomUUID());
        m.setType(MaintenanceType.OPERATION);
        m.setNumber(numeroOperation());
        m.setVehicleId(sold.getVehicleId());
        m.setClientId(sold.getClientId());
        m.setDate(LocalDate.now());
        m.setStatus(MaintenanceStatus.DELIVERED);
        m.setTechnician(sortie.technicien());
        m.setSoldPlanId(sold.getId());
        m.setPlanStepId(sortie.planStepId());
        List<ChargedLineDto> lignes = lignesACharger(sold.getId(), sortie.planStepId(), false);
        m.setDescription("Visite du palier « " + palier + " » du forfait « " + sold.getPlan().getName()
                + " », faite par la sortie " + sortie.sortieNumber()
                + ". Visite incluse au forfait, non facturée au client."
                + (lignes.isEmpty() ? " Aucune opération inscrite à ce palier dans le catalogue." : ""));
        for (ChargedLineDto ligne : lignes) {
            MaintenanceDetail d = new MaintenanceDetail();
            d.setReference(ligne.reference());
            d.setLabel(ligne.label());
            d.setQuantity(ligne.quantity());
            // Inclus au forfait : le prix du catalogue ne repasse pas sur le papier.
            d.setUnitPrice(0.0);
            d.setQuantityText(ligne.quantityText());
            m.getDetails().add(d);
        }
        // La même vérification que depuis l'écran, avant l'écriture : un refus ne doit
        // pas laisser un ordre de travail à moitié enregistré avec un palier coché.
        verifierRattachement(m);
        MaintenanceTotals.appliquer(m, tvaRate);
        documents.save(m);
        appliquerSuivi(m, null, null, jwt);
        if (sortie.compteurBus() != null) {
            // Le compteur relu dehors est la seule preuve de la borne franchie : il part
            // avec la date du passage sur la ligne de suivi.
            suivi.setMileageAtService(sortie.compteurBus());
            soldPlans.save(sold);
        }
        audit.record("CREATE", "MAINTENANCE", m.getId(),
                "Création de l'ordre de travail « " + m.getNumber() + " » à la clôture de la sortie "
                        + sortie.sortieNumber() + " (palier « " + palier + " », inclus au forfait)", jwt);
        return new PapierDePalier(m.getId(), m.getNumber(),
                "Palier « " + palier + " » coché par l'opération " + m.getNumber()
                        + " — incluse au forfait, non facturée.");
    }

    /**
     * Le numéro OR-AAAA-NNN de l'ordre de travail créé par le serveur. La suite est
     * celle de l'année, comme pour les sorties : le compteur reprend le plus haut
     * numéro posé, jamais le plus long ({@link NumberSequence}).
     */
    private String numeroOperation() {
        String prefix = "OR-" + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(prefix, documents.findNumbersByPrefix(prefix), 3,
                documents::existsByNumberIgnoreCase);
    }

    private void marquerFait(SoldPlanStep suivi, MaintenanceItem document, Jwt jwt) {
        if (suivi.getStatus() == SoldPlanStepStatus.DONE) {
            if (document.getId().equals(suivi.getMaintenanceItemId())) {
                return;
            }
            refuserSiDejaFait(suivi, document.getId());
        }
        suivi.setStatus(SoldPlanStepStatus.DONE);
        suivi.setDoneAt(LocalDateTime.now());
        suivi.setMaintenanceItemId(document.getId());
        soldPlans.save(suivi.getSoldPlan());
        // Le palier est fait : son rappel se ferme dans le même geste. Sans cela, le
        // suivi dirait « fait » pendant que le bandeau dirait encore « à faire ».
        rappels.palierFait(suivi.getId());
        audit.record("PALIER_FAIT", "SOLD_PLAN_STEP", suivi.getId(),
                "Palier « " + suivi.getStep().getLabel() + " » du forfait « "
                        + suivi.getSoldPlan().getPlan().getName() + " » fait par le document "
                        + document.getNumber(), jwt);
    }

    /** Remise à TODO par identifiants : le document a quitté ce lien, il n'est plus là pour le tenir. */
    private void remettreAFaire(String soldPlanId, String planStepId, String documentId,
                                String documentNumber, Jwt jwt) {
        if (soldPlanId == null || planStepId == null) {
            return;
        }
        SoldPlan sold = soldPlans.findById(soldPlanId).orElse(null);
        if (sold == null) {
            return;
        }
        suivi(sold, planStepId).ifPresent(ligne -> remettreAFaire(ligne, documentId, documentNumber, jwt));
    }

    private void remettreAFaire(SoldPlanStep suivi, String documentId, String documentNumber, Jwt jwt) {
        if (suivi.getStatus() == SoldPlanStepStatus.TODO) {
            return;
        }
        String auteur = suivi.getMaintenanceItemId();
        if (auteur != null && !auteur.equals(documentId)) {
            // Ce n'est pas ce document qui a fait le palier : il ne peut pas le défaire.
            return;
        }
        suivi.setStatus(SoldPlanStepStatus.TODO);
        suivi.setDoneAt(null);
        suivi.setMaintenanceItemId(null);
        soldPlans.save(suivi.getSoldPlan());
        audit.record("PALIER_LIBRE", "SOLD_PLAN_STEP", suivi.getId(),
                "Palier « " + suivi.getStep().getLabel() + " » du forfait « "
                        + suivi.getSoldPlan().getPlan().getName() + " » remis à faire (document "
                        + documentNumber + " annulé ou délié)", jwt);
    }

    /**
     * Le palier est déjà marqué fait : est-ce que quelqu'un d'autre le revendique
     * encore ? Un document annulé ou supprimé laisse une trace périmée, et le
     * palier doit pouvoir être repris — sinon une suppression maladroite fermerait
     * le forfait pour de bon.
     */
    private void refuserSiDejaFait(SoldPlanStep suivi, String documentId) {
        MaintenanceItem autre = auteurDuPalier(suivi);
        if (autre == null || autre.getId().equals(documentId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Le palier « " + suivi.getStep().getLabel() + " » de ce forfait est déjà fait par le document « "
                        + autre.getNumber() + " ». Une même visite ne se paie pas deux fois : annule ce document "
                        + "si c'est lui qui s'est trompé de palier.");
    }

    /**
     * Le document qui tient ce palier, s'il en est un. Vide dans trois cas, et les
     * trois laissent le palier reprenable : un palier encore à faire, une ligne marquée
     * faite sans papier (visite honorée hors portail), ou un papier annulé ou supprimé
     * — sinon une suppression maladroite fermerait le forfait pour de bon.
     */
    private MaintenanceItem auteurDuPalier(SoldPlanStep suivi) {
        if (suivi.getStatus() != SoldPlanStepStatus.DONE) {
            return null;
        }
        String auteur = suivi.getMaintenanceItemId();
        if (auteur == null || auteur.isBlank()) {
            return null;
        }
        MaintenanceItem document = documents.findById(auteur).orElse(null);
        return document == null || document.getStatus() == MaintenanceStatus.CANCELLED ? null : document;
    }

    /**
     * La ligne de suivi d'un palier de CE forfait. Le palier est identifié par son
     * identifiant de catalogue ({@code MaintenancePlanStep}), pas par l'identifiant
     * de la ligne de suivi : c'est celui que l'écran connaît, celui que le document
     * stocke, et le seul que l'import Excel peut écrire.
     */
    private SoldPlanStep palier(SoldPlan sold, String planStepId) {
        return suivi(sold, planStepId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Ce palier n'appartient pas au forfait « " + sold.getPlan().getName() + " » : choisis-le dans "
                        + "la liste des paliers de ce forfait."));
    }

    private static Optional<SoldPlanStep> suivi(SoldPlan sold, String planStepId) {
        return sold.getSteps().stream().filter(s -> s.getStep().getId().equals(planStepId)).findFirst();
    }

    /** Livré ou terminé : les deux statuts disent à l'atelier « la visite est faite ». */
    private static boolean estLivraison(MaintenanceStatus status) {
        return status == MaintenanceStatus.DELIVERED || status == MaintenanceStatus.COMPLETED;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Un palier du catalogue = une ligne de suivi à TODO. L'ordre du plan est celui des lignes. */
    private static List<SoldPlanStep> paliersAFAire(MaintenancePlan plan, SoldPlan sold) {
        return plan.getSteps().stream().map(palier -> {
            SoldPlanStep line = new SoldPlanStep();
            line.setId("sps-" + UUID.randomUUID());
            line.setSoldPlan(sold);
            line.setStep(palier);
            line.setStatus(SoldPlanStepStatus.TODO);
            return line;
        }).collect(Collectors.toList());
    }

    /**
     * Le forfait ne se vend que sur un bus de la gamme qu'il entretient.
     *
     * Les deux gammes d'autobus Higer — Confort et Standard — n'ont pas les mêmes
     * paliers ni les mêmes opérations. Vendre la grille d'une gamme sur l'autre ne
     * se voit pas le jour de la vente : ça se voit six mois plus tard, quand le
     * rappel réclame un palier que ce bus n'a jamais dû faire, ou en doit un que
     * personne ne lui a jamais programmé. Le contrôle se fait donc à l'écriture.
     */
    private static void verifieLaGamme(Vehicle vehicle, MaintenancePlan plan) {
        String gammeBus = BusType.labelOf(vehicle.getModel());
        if (gammeBus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bus " + label(vehicle) + " ne dit pas s'il est Confort ou Standard : complète sa fiche "
                            + "avant de lui vendre un forfait. C'est la gamme qui décide de la grille d'entretien, "
                            + "pas le modèle commercial.");
        }
        String gammePlan = BusType.labelOf(plan.getBusModel());
        if (gammePlan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le plan « " + plan.getName() + " » ne dit pas quelle gamme il entretient : sa case Modèle "
                            + "doit porter Confort ou Standard. Reprends la fiche d'import du catalogue.");
        }
        if (!gammeBus.equals(gammePlan)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le forfait « " + plan.getName() + " » est une grille d'entretien " + gammePlan
                            + ", le bus " + label(vehicle) + " est " + gammeBus + ". Un palier, un rappel et un "
                            + "papier se lisent sur la gamme du bus : choisis le forfait de la gamme "
                            + gammeBus + ".");
        }
    }

    /**
     * Deux forfaits ACTIVE du même plan sur le même bus ne peuvent pas couvrir une
     * période commune. Une date de fin vide = l'engagement court, donc il chevauche
     * toute période qui commence après son début.
     */
    private void refuseLaMemePeriode(Vehicle vehicle, MaintenancePlan plan, LocalDate start, LocalDate end) {
        for (SoldPlan other : soldPlans.findByVehicleIdAndPlanIdAndStatus(vehicle.getId(), plan.getId(),
                SoldPlanStatus.ACTIVE)) {
            if (overlaps(start, end, other.getStartDate(), other.getEndDate())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ce bus a déjà un forfait actif du plan « " + plan.getName() + " » du "
                                + other.getStartDate() + " au "
                                + (other.getEndDate() == null ? "aujourd'hui" : other.getEndDate())
                                + ". La même période ne peut pas être vendue deux fois : annule l'ancien forfait "
                                + "ou change les dates.");
            }
        }
    }

    static boolean overlaps(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        if (aEnd != null && aEnd.isBefore(bStart)) {
            return false;
        }
        return bEnd == null || !bEnd.isBefore(aStart);
    }

    private SoldPlan require(String id) {
        return soldPlans.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ce forfait vendu est introuvable."));
    }

    private SoldPlanResponse response(SoldPlan sold, boolean pricesVisible) {
        String clientName = clients.findById(sold.getClientId()).map(Client::getName).orElse(null);
        return vehicles.findById(sold.getVehicleId())
                .map(v -> response(sold, clientName, v.getVin(), v.getRegistration(), pricesVisible))
                .orElseGet(() -> response(sold, clientName, null, null, pricesVisible));
    }

    private static SoldPlanResponse response(SoldPlan sold, String clientName, String vin,
                                            String registration, boolean pricesVisible) {
        return SoldPlanResponse.of(sold, clientName, vin, registration, pricesVisible);
    }

    private static int doneCount(SoldPlan sold) {
        return (int) sold.getSteps().stream().filter(s -> s.getStatus() == SoldPlanStepStatus.DONE).count();
    }

    private static void freeze(String what, String requested, String current, SoldPlan sold) {
        if (requested == null || requested.isBlank() || requested.trim().equals(current)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "On ne change pas " + what + " d'un forfait vendu (« " + sold.getPlan().getName()
                        + " »). Les paliers suivis et les documents déjà liés viennent de cet engagement : "
                        + "annule le forfait et vends celui que tu veux à la place.");
    }

    private static String requireId(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il manque " + what + " : un forfait se vend sur un plan, pour un client, sur un bus précis.");
        }
        return value.trim();
    }

    private static LocalDate requireDate(LocalDate value, String what) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il manque " + what + " : c'est à partir de cette date que les paliers se déclenchent.");
        }
        return value;
    }

    /**
     * Le statut arrive en texte (l'API JSON le fait circuler en minuscules). Une
     * valeur inconnue est refusée ici plutôt que par Jackson, pour que l'écran
     * affiche une phrase et pas un « HttpMessageNotReadableException ».
     */
    private static SoldPlanStatus parseStatus(String raw, String gesture) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SoldPlanStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + raw + " » n'est pas un statut de forfait. Pour " + gesture
                            + ", écris actif, terminé ou annulé.");
        }
    }

    private static String fr(SoldPlanStatus status) {
        return switch (status) {
            case ACTIVE -> "actif";
            case COMPLETED -> "terminé";
            case CANCELLED -> "annulé";
        };
    }

    private static String label(Vehicle vehicle) {
        String registration = vehicle.getRegistration();
        return registration == null || registration.isBlank() ? "VIN " + vehicle.getVin() : registration;
    }

    /**
     * Le bus d'un forfait, désigné par le matricule que l'atelier lit sur la carrosserie.
     *
     * Un refus qui assomme un identifiant technique (« veh-1 ») ne veut rien dire à
     * l'atelier : il doit pouvoir dire quel bus est concerné sans ouvrir la fiche.
     */
    private String busDuForfait(String vehicleId) {
        return vehicles.findById(vehicleId).map(SoldPlanService::label).orElse(vehicleId);
    }

    /** Les noms des clients et des bus d'une page de forfaits, en deux requêtes et pas trente. */
    private Names namesOf(Collection<SoldPlan> rows) {
        Set<String> clientIds = new HashSet<>();
        Set<String> vehicleIds = new HashSet<>();
        for (SoldPlan row : rows) {
            clientIds.add(row.getClientId());
            vehicleIds.add(row.getVehicleId());
        }
        Map<String, Client> clientsById = clients.findAllById(clientIds).stream()
                .collect(Collectors.toMap(Client::getId, Function.identity()));
        Map<String, Vehicle> vehiclesById = vehicleIds.isEmpty() ? Map.of()
                : vehicles.findAllById(vehicleIds).stream()
                        .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        return new Names(clientsById, vehiclesById);
    }

    private record Names(Map<String, Client> clients, Map<String, Vehicle> vehicles) {

        String clientName(String id) {
            Client client = clients.get(id);
            return client == null ? null : client.getName();
        }

        String vehicleVin(String id) {
            Vehicle vehicle = vehicles.get(id);
            return vehicle == null ? null : vehicle.getVin();
        }

        String vehicleRegistration(String id) {
            Vehicle vehicle = vehicles.get(id);
            return vehicle == null ? null : vehicle.getRegistration();
        }
    }
}
