package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPieceJointe;
import tn.higer.sav_backend.dto.SavCaseDtos.AgSortieJointe;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPartRequest;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPartRequestRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.ItemPartRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Le dossier d'appel en garantie relu comme le constructeur va le lire : avec ses
 * sorties et ses pièces posées derrière le papier, et la liste écrite de ce qui
 * manque encore pour le déposer.
 *
 * Trois choix de fond, et ils ne sont pas décoratifs :
 *
 *  - <b>le rattachement se lit, il ne se ressaisit pas.</b> Les sorties viennent de
 *    {@code interventions.sav_case_id}, les pièces posées de {@code item_parts.sav_case_id}
 *    et des lignes de pièce des sorties. Rien n'est redemandé au guichet : une case
 *    recopiée à la main est une case qui peut dire autre chose que sa source.
 *  - <b>aucun montant n'est calculé.</b> Le barème de remboursement Higer n'a jamais
 *    été fourni, et un total fabriqué par le portail engagerait quelqu'un qui ne l'a
 *    pas décidé. Le portail compte des lignes et nomme des cases vides — jamais il
 *    n'additionne.
 *  - <b>ce qui ne dépend pas de nous ne devient pas une faute.</b> Les codes que seul
 *    le constructeur détient (importateur, dealer, unit, CAM, code défaut) sont
 *    signalés, pas comptés comme manquants : les réclamer à l'atelier serait lui
 *    faire inventer un code.
 *
 * {@code manquants} répond à « qu'est-ce que j'ai encore à écrire ? », {@code signes}
 * à « qu'est-ce que j'ai à regarder avant d'envoyer ce papier ? ». Un dossier
 * sans manquant n'est pas envoyé pour autant : c'est un humain qui signe.
 */
@Service
public class AgPaperAssemblyService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Une pièce qui est sur le bus. Deux maisons le disent, un seul mot à l'écran. */
    private static final String MONTEE_SUR_PAPIER = "montee";
    private static final String MONTEE_PENDANT_LA_SORTIE = "montee_en_sortie";
    private static final Set<String> MONTEES = Set.of(MONTEE_SUR_PAPIER, MONTEE_PENDANT_LA_SORTIE);

    private final SavCaseRepository savCases;
    private final InterventionRepository interventions;
    private final InterventionPartRequestRepository demandesDePiece;
    private final ItemPartRepository piecesMontees;
    private final MaintenanceRepository papiers;
    private final VehicleRepository vehicles;

    public AgPaperAssemblyService(SavCaseRepository savCases, InterventionRepository interventions,
                                  InterventionPartRequestRepository demandesDePiece,
                                  ItemPartRepository piecesMontees, MaintenanceRepository papiers,
                                  VehicleRepository vehicles) {
        this.savCases = savCases;
        this.interventions = interventions;
        this.demandesDePiece = demandesDePiece;
        this.piecesMontees = piecesMontees;
        this.papiers = papiers;
        this.vehicles = vehicles;
    }

    /**
     * Le papier AG et ses justificatifs. Un dossier qui n'est pas un appel en
     * garantie n'a pas de justificatifs à montrer : c'est un refus écrit, pas une
     * page vide — une page vide ferait croire que le dossier est prêt à déposer.
     */
    @Transactional(readOnly = true)
    public AgPapierJustifie relire(String savCaseId, Authentication auth) {
        if (!Access.readsClaimPapers(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Le dossier d'appel en garantie et ses sorties ne se relisent qu'au guichet et à"
                            + " l'atelier : votre compte n'a pas affaire au remboursement du constructeur.");
        }
        SavCase dossier = savCases.findById(savCaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dossier SAV introuvable."));
        if (dossier.getType() != SavCaseType.GARANTIES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + dossier.getCaseNumber() + " n'est pas un appel en garantie :"
                            + " il n'a ni papier AG à justifier ni marchandise à réclamer au constructeur.");
        }

        List<Intervention> sorties = sortiesDuDossier(dossier);
        Map<String, String> numerosDePapiers = numerosDePapiers(sorties);
        Map<String, String> sortieDeChaquePapier = sortieDeChaquePapier(sorties);

        List<AgSortieJointe> lignesDeSortie = sorties.stream()
                .map(i -> new AgSortieJointe(i.getNumber(),
                        i.getStatus() == null ? null : i.getStatus().name().toLowerCase(),
                        i.getStatus() == InterventionStatus.CLOSED,
                        i.getReportedAt(), i.getClosedAt(), i.getBusMileage(), i.getBusMileageEnd(),
                        i.getDefectCode(), i.getCausingPart(),
                        lire(numerosDePapiers, i.getMaintenanceItemId())))
                .toList();

        List<AgPieceJointe> pieces = new ArrayList<>(
                monteesSurPapier(dossier.getId(), numerosDePapiers, sortieDeChaquePapier));
        pieces.addAll(monteesPendantLesSorties(sorties));

        List<String> manquants = new ArrayList<>();
        List<String> signes = new ArrayList<>();
        relireLeDossier(dossier, busDe(dossier), sorties, pieces, manquants, signes);
        return new AgPapierJustifie(dossier.getCaseNumber(), lignesDeSortie, pieces,
                manquants, signes, manquants.isEmpty());
    }

    // ------------------------------------------------------------------- les lignes

    /**
     * Les sorties que le constructeur doit trouver derrière ce papier : celles
     * rattachées au dossier, et celles de la réclamation qui l'a fait naître.
     *
     * Un appel en garantie ne réclame jamais son propre travail : il rembourse la sortie
     * de dépannage envoyée sur l'appel du client. Cette sortie-là porte le dossier de la
     * réclamation dans {@code interventions.sav_case_id} et n'est jamais re-pointée sur
     * le claim — le lien qui la désigne est la filiation ({@code parent_case_id}, V20),
     * et la relire par ce lien évite de la ressaisir à l'écran. Sans elle, Higer ne
     * reçoit qu'une déclaration : aucune trace d'homme envoyé, aucun compteur relevé.
     *
     * Deux gardes : un dossier parent supprimé ne casse pas la lecture (le pointeur peut
     * survivre à la ligne), et une sortie vue des deux côtés ne se cite pas deux fois.
     */
    private List<Intervention> sortiesDuDossier(SavCase dossier) {
        List<Intervention> sorties = new ArrayList<>(
                interventions.findBySavCaseIdOrderByReportedAtAsc(dossier.getId()));
        if (dossier.getParentCaseId() == null) {
            return sorties;
        }
        savCases.findById(dossier.getParentCaseId()).ifPresent(parent ->
                interventions.findBySavCaseIdOrderByReportedAtAsc(parent.getId()).forEach(i -> {
                    if (sorties.stream().noneMatch(s -> s.getId().equals(i.getId()))) {
                        sorties.add(i);
                    }
                }));
        sorties.sort(Comparator.comparing(Intervention::getReportedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorties;
    }

    /**
     * Les pièces montées sur un papier d'atelier et réclamées par ce dossier. C'est
     * la seule marchandise du portail qui porte un sort chez le constructeur : la
     * pièce morte est repartie (datée) ou pas (motif écrit) — et sans elle, Higer
     * annule le remboursement.
     */
    private List<AgPieceJointe> monteesSurPapier(String savCaseId, Map<String, String> numerosDePapiers,
                                                  Map<String, String> sortieDeChaquePapier) {
        return piecesMontees.findBySavCaseId(savCaseId).stream()
                .map(p -> {
                    String numeroDePapier = lire(numerosDePapiers, p.getMaintenanceItemId());
                    return new AgPieceJointe(p.getReference(), p.getDesignation(), p.getQuantity(),
                            p.getFittedOn(),
                            numeroDePapier == null ? null
                                    : lire(sortieDeChaquePapier, p.getMaintenanceItemId()),
                            numeroDePapier, MONTEE_SUR_PAPIER, "Montée", p.isReturned(),
                            p.getReturnedAt(), p.getReturnReason());
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Les pièces vues devant le bus par une sortie de ce dossier, du premier coup
     * d'œil du technicien à la pose. Leur état ne se saisit pas ({@link
     * PartRequestState}), donc c'est lui qui dit au guichet si la marchandise est
     * déjà sur le bus ou encore au magasin.
     */
    private List<AgPieceJointe> monteesPendantLesSorties(List<Intervention> sorties) {
        List<String> ids = sorties.stream().map(Intervention::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<InterventionPartRequest> lignes = demandesDePiece
                .findByInterventionIdInOrderByCreatedAtAsc(ids);
        Map<String, Intervention> parId = nouvellesSorties(lignes, sorties);
        return lignes.stream()
                .map(p -> {
                    Intervention ouOnLaVue = lire(parId, p.getInterventionId());
                    Intervention ouOnLaMontee = p.getFittedInterventionId() == null ? null
                            : parId.get(p.getFittedInterventionId());
                    String designation = nomDeLaPiece(p.getReference(), p.getDesignation(),
                            ouOnLaVue == null ? null : ouOnLaVue.getCausingPart());
                    return new AgPieceJointe(p.getReference(), designation,
                            p.getQuantityOrdered() == null ? null : p.getQuantityOrdered().doubleValue(),
                            p.getFittedAt() == null ? null : p.getFittedAt().toLocalDate(),
                            ouOnLaMontee == null ? null : ouOnLaMontee.getNumber(), null,
                            etatDe(p.getState()), labelDe(p.getState()), null, null, null);
                })
                .toList();
    }

    /**
     * Les secondes sorties — celles qui sont allées monter la pièce — ne sont pas
     * toutes rattachées au dossier : elles le portent souvent par la mission, pas par
     * le dossier. Relues d'un bloc, pour qu'une liste de pièces ne devienne pas une
     * lecture de sortie par ligne.
     */
    private Map<String, Intervention> nouvellesSorties(List<InterventionPartRequest> lignes,
                                                       List<Intervention> dejaLues) {
        Map<String, Intervention> parId = new HashMap<>();
        dejaLues.forEach(i -> parId.put(i.getId(), i));
        List<String> absentes = lignes.stream()
                .flatMap(p -> java.util.stream.Stream.of(p.getInterventionId(), p.getFittedInterventionId()))
                .filter(Objects::nonNull).distinct().filter(id -> !parId.containsKey(id)).toList();
        if (!absentes.isEmpty()) {
            interventions.findAllById(absentes).forEach(i -> parId.put(i.getId(), i));
        }
        return parId;
    }

    // ------------------------------------------------------------------- le verdict

    /**
     * Ce qui manque encore, et ce qu'il faut regarder. La liste est écrite en français
     * et nomme la case plutôt que le champ : c'est la case du papier que le guichet a
     * sous les yeux, pas une colonne de base.
     */
    private void relireLeDossier(SavCase dossier, Vehicle bus, List<Intervention> sorties,
                                 List<AgPieceJointe> pieces, List<String> manquants, List<String> signes) {
        if (sorties.isEmpty()) {
            manquants.add("Aucune sortie ne répond à ce dossier : le constructeur n'a rien à vérifier"
                    + " sur place, et rien ne prouve que le bus a été réparé.");
        }
        sorties.stream().filter(i -> i.getStatus() != InterventionStatus.CLOSED).forEach(i ->
                manquants.add("La sortie " + i.getNumber() + " n'est pas clôturée : ce qui a été fait"
                        + " chez le client reste à écrire à l'atelier."));

        LocalDate incident = jourDeLIncident(dossier);
        if (incident == null) {
            manquants.add("La date de l'incident reste vide sur le papier AG : c'est elle que le"
                    + " constructeur confronte à la fin de garantie du bus.");
        }
        if (!rempli(dossier.getAgRepairOrderNumber())) {
            manquants.add("Le numéro de l'ordre de travail reste vide" + papierLePlusProche(sorties) + ".");
        }
        if (dossier.getAgRepairOrderDate() == null) {
            manquants.add("L'ordre de travail n'est pas daté : sans sa date, le constructeur ne peut"
                    + " pas vérifier que l'appel suit la panne.");
        }
        if (dossier.getAgKm() == null) {
            manquants.add("Le compteur du bus à l'incident reste vide" + compteurReleve(sorties) + ".");
        }
        if (!rempli(dossier.getResolution())) {
            manquants.add("La correction faite ne s'écrit nulle part : un dossier qui ne dit pas ce qui"
                    + " a été réparé n'est pas une demande de remboursement.");
        }
        if (!rempli(dossier.getAgCause())) {
            manquants.add("La cause de la panne reste vide : le constructeur décide de payer un défaut"
                            + " de fabrication, pas une usure, et il lui faut le mot écrit.");
        }
        if (!rempli(dossier.getAgCausalPartReference()) && !rempli(dossier.getAgCausalPartDescription())
                && !rempli(dossier.getAgConcernedPart())) {
            manquants.add("La pièce en cause reste sans nom : c'est pourtant elle que le constructeur"
                    + " veut récupérer avant de rembourser.");
        }
        if (dossier.getClaimAmountClaimed() == null) {
            manquants.add("Le montant demandé au constructeur reste vide. Il s'écrit au guichet : le"
                    + " portail ne le calcule pas, le barème de remboursement de Higer n'ayant jamais"
                    + " été fourni.");
        }

        pieces.stream().filter(p -> !MONTEES.contains(p.etat())).forEach(p ->
                manquants.add("La pièce " + nom(p) + " est « " + p.etatLabel()
                        + " » : elle n'est pas encore montée sur le bus. Un papier déposé maintenant"
                        + " réclamerait un travail pas fini."));
        pieces.stream().filter(p -> Boolean.FALSE.equals(p.retourEnvoye())).forEach(p ->
                manquants.add("La pièce " + nom(p) + " n'est pas repartie chez le constructeur ("
                        + (rempli(p.motifDeNonRetour()) ? p.motifDeNonRetour().trim() : "aucun motif écrit")
                        + "). Une pièce que Higer ne récupère pas annule le remboursement."));

        if (incident != null && bus != null && bus.getWarrantyEndDate() != null
                && bus.getWarrantyEndDate().isBefore(incident)) {
            signes.add("La fiche du bus dit la garantie finie le " + bus.getWarrantyEndDate().format(JOUR)
                    + ", l'incident est daté du " + incident.format(JOUR) + " : le constructeur écartera"
                    + " la demande. Vérifiez laquelle des deux dates est fausse avant de déposer.");
        }
        pieces.stream().filter(p -> MONTEE_PENDANT_LA_SORTIE.equals(p.etat())).forEach(p ->
                signes.add("La pièce " + nom(p) + " a été montée pendant "
                        + (p.sortieNumber() == null ? "une sortie de ce dossier" : "la sortie " + p.sortieNumber())
                        + " : le portail n'écrit le sort du retour que sur un papier d'atelier, donc la"
                        + " preuve que la pièce morte est repartie reste à produire ailleurs."));
        if (dossier.getType() == SavCaseType.GARANTIES && caseConstructeurVide(dossier)) {
            signes.add("Les codes que seul le constructeur détient (importateur, numéro de dealer, d'unité,"
                    + " de CAM, code défaut) sont encore vides : rien n'est inventé pour les remplir, ils"
                    + " viennent de la réponse de Higer.");
        }
        if (dossier.getClaimState() == ClaimState.ACCORDE) {
            signes.add("Le constructeur a déjà accordé cet appel : ce papier ne demande plus rien,"
                    + " il reste à encaisser l'avoir.");
        }
    }

    /**
     * La date de l'incident telle que le papier la lit : la case AG d'abord, car
     * c'est elle que Higer confronte à sa garantie ; à défaut l'appel du client, qui
     * est la même panne arrivée par le téléphone. Jamais « aujourd'hui » : une date
     * devinée ferait entrer dans la garantie un bus qui en est sorti.
     *
     * Publique depuis le 29/10 : la garde du devis ({@link MissionService}) juge la
     * garantie à la même date. Deux lectures différentes de la panne finiraient par
     * dire d'un bus qu'il est couvert pour l'une et pas pour l'autre.
     */
    public static LocalDate jourDeLIncident(SavCase dossier) {
        if (dossier.getAgIncidentDate() != null) {
            return dossier.getAgIncidentDate();
        }
        return dossier.getCalledAt() == null ? null : dossier.getCalledAt().toLocalDate();
    }

    /** Le papier d'atelier né d'une sortie, cité dans le refus : « recopiez OR-2026-004 ». */
    private static String papierLePlusProche(List<Intervention> sorties) {
        return sorties.stream().filter(i -> i.getMaintenanceItemId() != null).findFirst()
                .map(i -> " : la sortie " + i.getNumber() + " a déjà donné un papier d'atelier,"
                        + " recopiez-en le numéro")
                .orElse(" : c'est le numéro du papier d'atelier qui a réparé ce bus");
    }

    /** Le compteur déjà relevé devant le bus, recopié dans le refus plutôt que réinventé. */
    private static String compteurReleve(List<Intervention> sorties) {
        return sorties.stream().filter(i -> i.getBusMileage() != null).findFirst()
                .map(i -> " : la sortie " + i.getNumber() + " a relevé " + i.getBusMileage()
                        + " km au tableau de bord, recopiez-les")
                .orElse(" : relevez-le sur la fiche du bus");
    }

    private static boolean caseConstructeurVide(SavCase c) {
        return !rempli(c.getAgImporter()) || !rempli(c.getAgDealerNumber())
                || !rempli(c.getAgUnitNumber()) || !rempli(c.getAgCamNumber())
                || !rempli(c.getAgDefectCode());
    }

    /** Le nom d'une pièce tel que le constructeur l'écrit : sa référence, sinon sa désignation. */
    private static String nom(AgPieceJointe p) {
        return nomDeLaPiece(p.reference(), p.designation(), null);
    }

    private static String nomDeLaPiece(String reference, String designation, String defaultName) {
        if (rempli(reference)) {
            return reference.trim() + (rempli(designation) ? " (" + designation.trim() + ")" : "");
        }
        if (rempli(designation)) {
            return designation.trim();
        }
        return rempli(defaultName) ? defaultName.trim() : "sans nom";
    }

    private Vehicle busDe(SavCase dossier) {
        return dossier.getVehicleId() == null ? null
                : vehicles.findById(dossier.getVehicleId()).orElse(null);
    }

    /** Les papiers d'atelier nés de ces sorties, relus d'un bloc. */
    private Map<String, String> numerosDePapiers(List<Intervention> sorties) {
        List<String> ids = sorties.stream().map(Intervention::getMaintenanceItemId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return papiers.findAllById(ids).stream()
                .collect(Collectors.toMap(MaintenanceItem::getId, MaintenanceItem::getNumber));
    }

    /** Le papier d'atelier porte la sortie qui l'a fait naître : la pièce se retrouve ainsi. */
    private Map<String, String> sortieDeChaquePapier(List<Intervention> sorties) {
        Map<String, String> parPapier = new HashMap<>();
        sorties.stream().filter(i -> i.getMaintenanceItemId() != null)
                .forEach(i -> parPapier.put(i.getMaintenanceItemId(), i.getNumber()));
        return parPapier;
    }

    /** L'état d'une ligne de pièce, en minuscules pour l'écran, avec son mot français. */
    private static String etatDe(PartRequestState state) {
        if (state == PartRequestState.POSEE) {
            return MONTEE_PENDANT_LA_SORTIE;
        }
        return state == null ? null : state.name().toLowerCase();
    }

    private static String labelDe(PartRequestState state) {
        return state == null ? "sans état" : state.label();
    }

    private static boolean rempli(String v) {
        return v != null && !v.isBlank();
    }

    private static <T> T lire(Map<String, T> carte, String cle) {
        return cle == null ? null : carte.get(cle);
    }
}
