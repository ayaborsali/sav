package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPieceJointe;
import tn.higer.sav_backend.dto.SavCaseDtos.AgSortieJointe;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le papier d'appel en garantie, prêt à être mis en page pour Higer.
 *
 * <p>Troisième document du portail, après le contrat et le bon d'intervention, et
 * il ne se découpe pas comme eux : le bon se signe à deux devant le bus, la feuille
 * de garantie se <b>dépose</b> chez le constructeur. Ce service ne dessine donc rien
 * — il assemble les trente cases du papier AG, les justificatifs déjà relus par
 * {@link AgPaperAssemblyService} (sorties, pièces posées, ce qui manque, ce qu'il
 * faut regarder) et les mots de la fiche du bus et du client, dans un plan JSON que
 * {@code scripts/render_claim_pdf.py} pose sur la page.</p>
 *
 * <p><b>Les libellés sont ceux de l'écran, mot pour mot.</b> Une case qui ne
 * s'appelle pas pareil sur la feuille et sur l'écran oblige le guichet à traduire
 * de tête, et c'est à la traduction de tête qu'un numéro se recopie dans la mauvaise
 * case. Les six rubriques et les noms de cases viennent donc du même vocabulaire
 * que le bloc AG du formulaire des dossiers.</p>
 *
 * <p><b>Aucun montant n'est calculé, et ça s'imprime.</b> Le barème de remboursement
 * de Higer n'a jamais été fourni : le portail recopie ce que le guichet a écrit,
 * case par case, et écrit « non écrit » à la place d'un chiffre qu'il n'a pas. La
 * monnaie elle-même n'est pas affirmée. Un total fabriqué ici engagerait quelqu'un
 * qui ne l'a pas décidé.</p>
 *
 * <p><b>Les cases vides restent vides, mais elles sont dites.</b> Un code que seul
 * le constructeur détient n'est pas inventé pour remplir la page ; il figure à la
 * liste « ce qui reste à écrire », imprimée en rouge en tête du document. C'est le
 * seul papier du portail qui s'imprime avec ses propres manques visibles : une
 * feuille incomplète qui part quand même ne doit sa chance à personne.</p>
 */
@Service
public class AgPaperDocumentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String DASH = "—";
    /** Une case d'argent non saisie ne se remplit pas d'un zéro : le zéro est une décision. */
    private static final String NON_ECRIT = "non écrit";
    /**
     * Le nombre saisi s'imprime avec une virgule française et sans décimales fantômes :
     * 1 200 et pas 1200.0. Trois décimales au plus, parce qu'au-delà le guichet n'écrit
     * rien — arrondir une case d'argent serait changer le montant qui a été demandé.
     */
    private static final DecimalFormat NOMBRE = nombreFrancais();

    private final AgPaperAssemblyService papier;
    private final SavCaseRepository savCases;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final String vendorName;
    private final String vendorAddress;

    public AgPaperDocumentService(AgPaperAssemblyService papier, SavCaseRepository savCases,
                                  VehicleRepository vehicles, ClientRepository clients,
                                  @Value("${app.vendor.name}") String vendorName,
                                  @Value("${app.vendor.address}") String vendorAddress) {
        this.papier = papier;
        this.savCases = savCases;
        this.vehicles = vehicles;
        this.clients = clients;
        this.vendorName = vendorName;
        this.vendorAddress = vendorAddress;
    }

    /**
     * Le plan du document. La lecture des justificatifs est déléguée à
     * {@link AgPaperAssemblyService#relire} : c'est elle qui détient le droit de
     * regard (guichet et atelier), le refus hors famille garanties et la liste des
     * manques. Le document n'ajoute aucune règle et n'en adoucit aucune — ce que
     * l'écran refuse de montrer ne s'imprime pas non plus.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> payload(String savCaseId, Authentication auth) {
        AgPapierJustifie lu = papier.relire(savCaseId, auth);
        SavCase dossier = savCases.findById(savCaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dossier SAV introuvable."));
        return build(dossier, lu);
    }

    // ------------------------------------------------------------------ le plan

    private Map<String, Object> build(SavCase dossier, AgPapierJustifie lu) {
        Vehicle bus = dossier.getVehicleId() == null ? null
                : vehicles.findById(dossier.getVehicleId()).orElse(null);
        Client client = dossier.getClientId() == null ? null
                : clients.findById(dossier.getClientId()).orElse(null);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("brand", vendorName);
        plan.put("subtitle", "Appel en garantie — demande de remboursement au constructeur");
        plan.put("number", lu.caseNumber());
        plan.put("date_label", "Édité le " + LocalDate.now().format(DATE));
        plan.put("badge", badge(dossier));
        plan.put("boxes", boxes(dossier, bus, client, lu));
        plan.put("tables", tables(lu));
        plan.put("texts", texts(dossier));
        plan.put("manquants", lu.manquants());
        plan.put("manquants_heading", "CE QUI RESTE À ÉCRIRE AVANT DE DÉPOSER CE PAPIER");
        plan.put("signes", lu.signes());
        plan.put("signes_heading", "À REGARDER AVANT D'ENVOYER CE PAPIER");
        plan.put("signatures", signatures(dossier));
        plan.put("footer", footer());
        return plan;
    }

    /** Le mot du constructeur d'abord : un papier déjà accordé ne demande plus rien. */
    private String badge(SavCase dossier) {
        String etat = dossier.getClaimState() == null ? "état de la demande non écrit"
                : "Demande « " + dossier.getClaimState().label() + " »";
        return etat + " — aucun montant n'est calculé par le portail : la feuille est un dossier "
                + "de preuves, pas une facture.";
    }

    private List<Map<String, Object>> boxes(SavCase d, Vehicle bus, Client client,
                                            AgPapierJustifie lu) {
        List<Map<String, Object>> boxes = new ArrayList<>();
        boxes.add(box("La demande", List.of(
                row("N° de dossier", orDash(lu.caseNumber())),
                row("Intitulé", orDash(d.getTitle())),
                row("Client", orDash(FicheLabel.client(client))),
                row("N° d'accord constructeur", orDash(d.getWarrantyApprovalNumber())),
                row("Montant demandé", montant(d.getClaimAmountClaimed())),
                row("Montant accordé", montant(d.getClaimAmountGranted())))));
        boxes.add(box("Le bus", List.of(
                row("Châssis (VIN)", orDash(FicheLabel.vin(bus))),
                row("Immatriculation", orDash(FicheLabel.immatriculation(bus))),
                row("Modèle", orDash(FicheLabel.modele(bus))),
                row("Fin de garantie de la fiche", date(bus == null ? null : bus.getWarrantyEndDate())))));

        boxes.add(box("Qui écrit le papier, et sur quel ordre", List.of(
                row("Nom du rédacteur", orDash(d.getAgWriterName())),
                row("N° OR", orDash(d.getAgRepairOrderNumber())),
                row("Date d'OR", date(d.getAgRepairOrderDate())))));
        boxes.add(box("Le bus chez Higer", List.of(
                row("Importateur", orDash(d.getAgImporter())),
                row("N° distributeur", orDash(d.getAgDealerNumber())),
                row("Unit No.", orDash(d.getAgUnitNumber())),
                row("CAM N°", orDash(d.getAgCamNumber()))),
                // Le rappel de la règle, tel que l'écran le montre : ces quatre codes ne
                // s'inventent pas, ils viennent de la réponse de Higer.
                "Les quatre codes ci-dessus sont ceux que le constructeur attribue : tant qu'il ne "
                        + "les a pas donnés, la case reste vide. Un numéro inventé sur un papier "
                        + "officiel se retourne contre son auteur."));

        boxes.add(box("L'incident", List.of(
                row("Date incident", date(d.getAgIncidentDate())),
                row("Km au jour de l'incident", nombre(d.getAgKm())),
                row("Sécurité en cause", ouiNon(d.getAgSecurityImpact())),
                row("Nature", d.getAgIncidentType() == null ? DASH : d.getAgIncidentType().label()),
                row("Pièce ou fonction concernée", orDash(d.getAgConcernedPart())),
                row("Nouvelle ou reconditionnée", orDash(d.getAgPartNewOrReconditioned())),
                row("Km de l'organe", nombre(d.getAgOrganKm())),
                row("N° AG précédent", orDash(d.getAgPreviousClaimNumber()))),
                "Le kilométrage demandé ici est celui du bus au jour de la panne, pas le dernier "
                        + "compteur lu sur sa fiche : les deux chiffres ne racontent pas la même chose."));
        boxes.add(box("La pièce en cause et le défaut", List.of(
                row("Référence pièce causale", orDash(d.getAgCausalPartReference())),
                row("Désignation pièce causale", orDash(d.getAgCausalPartDescription())),
                row("Type de défaut", orDash(d.getAgDefectType())),
                row("Code défaut", orDash(d.getAgDefectCode())),
                row("Fonction / groupe", orDash(d.getAgFunctionGroup())),
                row("N° de série", orDash(d.getAgSerialNumber())))));

        boxes.add(box("La main-d'œuvre, recopiée du papier", List.of(
                row("Code opération", orDash(d.getAgLaborOperationCode())),
                row("Temps alloué (h)", nombre(d.getAgAllocatedTime())),
                row("Coût à l'heure (DT)", montant(d.getAgHourlyCost())),
                row("Net main-d'œuvre (DT)", montant(d.getAgLaborNet())),
                row("Autres coûts (DT)", montant(d.getAgOtherCosts()))),
                "Les montants s'écrivent, ils ne se calculent pas : le barème de remboursement de "
                        + "Higer n'a jamais été fourni, et un total fabriqué par le portail engagerait "
                        + "quelqu'un qui ne l'a pas décidé. La monnaie n'est donc pas affirmée sur "
                        + "cette feuille."));

        boxes.add(box("La réponse du constructeur", List.of(
                row("État de la demande", d.getClaimState() == null ? DASH : d.getClaimState().label()),
                row("Décrite le", date(d.getClaimDecisionAt())),
                row("Décision", orDash(d.getClaimDecision())))));
        return boxes;
    }

    private static Map<String, Object> box(String heading, List<List<String>> rows) {
        return box(heading, rows, null);
    }

    private static Map<String, Object> box(String heading, List<List<String>> rows, String note) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("heading", heading);
        box.put("rows", rows);
        if (note != null) {
            box.put("note", note);
        }
        return box;
    }

    private static List<String> row(String label, String value) {
        return List.of(label, value);
    }

    // ------------------------------------------------------------------- les justificatifs

    /**
     * Les deux tables que le constructeur réclame : qui est allé voir le bus, et ce
     * qui a été posé dessus. Compteurs et dates se recopient des sorties ; aucune
     * ligne ne chiffre quoi que ce soit.
     */
    private List<Map<String, Object>> tables(AgPapierJustifie lu) {
        List<Map<String, Object>> tables = new ArrayList<>();
        tables.add(sortiesTable(lu.sorties()));
        tables.add(piecesTable(lu.pieces()));
        return tables;
    }

    private Map<String, Object> sortiesTable(List<AgSortieJointe> sorties) {
        List<List<String>> rows = new ArrayList<>();
        for (AgSortieJointe s : sorties) {
            rows.add(List.of(orDash(s.number()), sortieLabel(s),
                    horodatage(s.reportedAt()) + " -> " + horodatage(s.closedAt()),
                    compteurs(s),
                    diagnostic(s),
                    orDash(s.atelierPaperNumber())));
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("heading", "Les sorties qui répondent à cet appel (" + sorties.size()
                + " ligne(s), aucune n'est saisie ici)");
        table.put("columns", List.of("Sortie", "Où en est la sortie", "Signalée -> clôturée",
                "Compteurs relevés sur le bus", "Diagnostic lu devant le bus", "Papier d'atelier"));
        table.put("weights", List.of(0.9, 1.1, 1.5, 1.3, 1.7, 1.0));
        table.put("rows", rows);
        table.put("note", "Aucune sortie ne répond à ce dossier : le constructeur n'a rien à "
                + "vérifier sur place.");
        return table;
    }

    private static String sortieLabel(AgSortieJointe s) {
        if (s.terminee()) {
            return "clôturée";
        }
        return s.status() == null ? "état inconnu" : statutFr(s.status());
    }

    /** Les compteurs tels qu'ils ont été lus : le portail n'intercale rien entre deux relevés. */
    private static String compteurs(AgSortieJointe s) {
        if (s.kmReleve() == null && s.kmReleveApres() == null) {
            return "aucun compteur relevé";
        }
        return orDash(longKm(s.kmReleve())) + " -> " + orDash(longKm(s.kmReleveApres()));
    }

    private static String diagnostic(AgSortieJointe s) {
        List<String> lignes = new ArrayList<>();
        if (notBlank(s.defectCode())) {
            lignes.add("code : " + s.defectCode().trim());
        }
        if (notBlank(s.causingPart())) {
            lignes.add("pièce : " + s.causingPart().trim());
        }
        return lignes.isEmpty() ? "rien n'a été lu" : String.join(" • ", lignes);
    }

    private Map<String, Object> piecesTable(List<AgPieceJointe> pieces) {
        List<List<String>> rows = new ArrayList<>();
        for (AgPieceJointe p : pieces) {
            rows.add(List.of(nomDeLaPiece(p), nombre(p.quantite()), date(p.monteeLe()),
                    ouOnLaVue(p), sortDeLaPiece(p)));
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("heading", "La marchandise réclamée au constructeur (" + pieces.size()
                + " ligne(s))");
        table.put("columns", List.of("Pièce", "Qté", "Montée le", "Où on l'a vue ou montée",
                "Sort de la pièce morte"));
        table.put("weights", List.of(1.6, 0.5, 0.9, 1.4, 1.6));
        table.put("rows", rows);
        table.put("note", "Aucune pièce n'est rattachée à cet appel : Higer rembourse une pièce "
                + "morte récupérée, pas une réparation sans marchandise.");
        return table;
    }

    private static String nomDeLaPiece(AgPieceJointe p) {
        if (notBlank(p.reference())) {
            return p.reference().trim()
                    + (notBlank(p.designation()) ? " (" + p.designation().trim() + ")" : "");
        }
        return notBlank(p.designation()) ? p.designation().trim() : "pièce sans nom";
    }

    private static String ouOnLaVue(AgPieceJointe p) {
        List<String> lignes = new ArrayList<>();
        if (notBlank(p.sortieNumber())) {
            lignes.add("sortie " + p.sortieNumber().trim());
        }
        if (notBlank(p.atelierPaperNumber())) {
            lignes.add("papier " + p.atelierPaperNumber().trim());
        }
        return lignes.isEmpty() ? DASH : String.join(" • ", lignes);
    }

    /**
     * Le sort de la pièce morte, en un mot : c'est la case que Higer regarde avant de
     * rembourser. Rien n'est déduit — la date et le motif viennent de la ligne de
     * pièce, et l'absence de trace s'écrit comme une absence de trace.
     */
    private static String sortDeLaPiece(AgPieceJointe p) {
        if (Boolean.TRUE.equals(p.retourEnvoye())) {
            return p.retourLe() == null ? "repartie, date non écrite"
                    : "repartie le " + p.retourLe().format(DATE);
        }
        if (Boolean.FALSE.equals(p.retourEnvoye())) {
            return "non repartie : " + (notBlank(p.motifDeNonRetour())
                    ? p.motifDeNonRetour().trim() : "aucun motif écrit");
        }
        return "montée".equals(p.etat()) || "montee_en_sortie".equals(p.etat())
                ? "retour non tracé" : orDash(p.etatLabel());
    }

    // ------------------------------------------------------------------- les écritures longues

    /**
     * Les quatre cases qui se tapent en plusieurs lignes, reprises sous leur libellé
     * d'écran. La plainte du client et la correction faite restent dans « Description
     * » et « Résolution » du dossier : le papier AG ne les redemande pas, il les
     * recopie.
     */
    private List<Map<String, Object>> texts(SavCase d) {
        List<Map<String, Object>> texts = new ArrayList<>();
        texts.add(text("Description du travail", d.getAgLaborDescription()));
        texts.add(text("Contexte client", d.getAgCustomerContext()));
        texts.add(text("Cause", d.getAgCause()));
        texts.add(text("Commentaires", d.getAgComments()));
        return texts;
    }

    private static Map<String, Object> text(String heading, String body) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("heading", heading);
        text.put("body", notBlank(body) ? body.trim() : "Rien n'a été écrit pour cette partie.");
        return text;
    }

    /** Le papier se dépose, il ne se signe pas devant le bus : deux cases, le stylo et le cachet. */
    private List<Map<String, String>> signatures(SavCase d) {
        List<Map<String, String>> signatures = new ArrayList<>();
        signatures.add(signature(orDash(d.getAgWriterName()),
                "Le rédacteur du papier — cases relues avant envoi"));
        signatures.add(signature(vendorName,
                "Le chef d'atelier — justificatifs joints, demande déposée"));
        return signatures;
    }

    private static Map<String, String> signature(String name, String label) {
        Map<String, String> signature = new LinkedHashMap<>();
        signature.put("name", name);
        signature.put("label", label);
        return signature;
    }

    private String footer() {
        return vendorName + " — " + vendorAddress + " — papier d'appel en garantie généré le "
                + LocalDate.now().format(DATE)
                + " — le portail compte des lignes et nomme des cases vides, il ne calcule aucun montant.";
    }

    // ------------------------------------------------------------------ mise en forme

    private static String statutFr(String code) {
        if (code == null) {
            return DASH;
        }
        return switch (code) {
            case "reported" -> "signalée, pas encore partie";
            case "dispatched" -> "affectée, pas encore partie";
            case "on_site" -> "sur place";
            case "in_progress" -> "en cours de réparation";
            case "repaired" -> "réparée sur place";
            case "towed" -> "remorquée";
            case "returned" -> "rentrée à l'atelier";
            case "closed" -> "clôturée";
            default -> code;
        };
    }

    private static String date(LocalDate value) {
        return value == null ? DASH : value.format(DATE);
    }

    private static String horodatage(LocalDateTime value) {
        return value == null ? "non horodaté" : value.format(DATE);
    }

    private static String montant(Double value) {
        return value == null ? NON_ECRIT : nombre(value);
    }

    private static String nombre(Integer value) {
        return value == null ? DASH : format(value);
    }

    /** Une quantité de pièces s'écrit 1 ou 2,5 : pas 1.0, qui ferait douter de la ligne. */
    private static String nombre(Double value) {
        return value == null ? DASH : format(value);
    }

    private static synchronized String format(double value) {
        return NOMBRE.format(value);
    }

    private static DecimalFormat nombreFrancais() {
        DecimalFormatSymbols symboles = DecimalFormatSymbols.getInstance(Locale.FRENCH);
        // L'espace fine (U+202F) que Java met par défaut en séparateur de milliers n'existe
        // pas dans l'encodage des polices Helvetica : il ressortirait en carré noir sur la
        // feuille. Un espace ordinaire se lit pareil et s'imprime.
        symboles.setGroupingSeparator(' ');
        DecimalFormat df = new DecimalFormat("#,##0.###", symboles);
        df.setGroupingUsed(true);
        return df;
    }

    private static String longKm(Long value) {
        return value == null ? null : format(value) + " km";
    }

    private static String ouiNon(Boolean value) {
        if (value == null) {
            return "non répondu";
        }
        return value ? "oui" : "non";
    }

    private static String orDash(String value) {
        return notBlank(value) ? value.trim() : DASH;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
