package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.BonLivraisonDtos.BonDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.CompteurDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.PieceDto;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceLineDto;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.OrdreDtos.EtatClotureDto;
import tn.higer.sav_backend.dto.OrdreDtos.FicheResponse;
import tn.higer.sav_backend.dto.OrdreDtos.ForfaitDto;
import tn.higer.sav_backend.dto.OrdreDtos.OrigineDto;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le papier de l'atelier, prêt à être mis en page : devis, ordre de travail, bon de livraison.
 *
 * <p>Ce service ne dessine rien. Il relit le papier par la seule porte qui existe déjà
 * ({@link OrdreService#fiche(String)}, celle que l'écran utilise) et il rassemble les valeurs
 * dans un plan JSON que {@code scripts/render_maintenance_pdf.py} pose sur la page. Le PDF est
 * donc toujours reconstruit depuis la base : un fichier gardé « chez soi » ne peut pas être en
 * retard sur le papier, et une copie faite à l'écran le serait.</p>
 *
 * <p><b>La question de l'argent se tranche ici, avant la page.</b> Seul le devis chiffre
 * ({@code MaintenanceTotals} ne calcule que pour lui) : l'ordre de travail décrit le travail
 * (V38) et le bon de livraison le rend (V39). La conséquence est écrite dans le JSON, pas dans
 * la mise en page — un ordre et un BL ne reçoivent <b>aucune</b> colonne de prix et <b>aucune</b>
 * clé {@code totals}, donc le script ne peut pas faire réapparaître un montant par un choix de
 * gabarit.</p>
 *
 * <p><b>Une case vide reste vide, avec son libellé.</b> Un registre de commerce non configuré
 * s'imprime « RC : — » : ce qui manque sur le papier doit se voir, sinon l'absence devient une
 * donnée. Rien n'est calculé à l'affichage hors l'arithmétique déjà posée par le serveur.</p>
 */
@Service
public class MaintenanceDocumentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale FR = Locale.FRENCH;
    private static final NumberFormat MONEY = moneyFormat();
    private static final NumberFormat HOURS = hoursFormat();
    private static final String DASH = "—";

    private final OrdreService ordre;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final String vendorName;
    private final String vendorAddress;
    private final String vendorTradeRegister;
    private final String vendorTaxId;
    private final String vendorRepresentative;
    private final double tvaRateConfig;

    public MaintenanceDocumentService(OrdreService ordre,
                                      VehicleRepository vehicles,
                                      ClientRepository clients,
                                      @Value("${app.vendor.name}") String vendorName,
                                      @Value("${app.vendor.address}") String vendorAddress,
                                      @Value("${app.vendor.trade-register:}") String vendorTradeRegister,
                                      @Value("${app.vendor.tax-id:}") String vendorTaxId,
                                      @Value("${app.vendor.representative:}") String vendorRepresentative,
                                      @Value("${app.tva.rate:19}") double tvaRateConfig) {
        this.ordre = ordre;
        this.vehicles = vehicles;
        this.clients = clients;
        this.vendorName = vendorName;
        this.vendorAddress = vendorAddress;
        this.vendorTradeRegister = vendorTradeRegister;
        this.vendorTaxId = vendorTaxId;
        this.vendorRepresentative = vendorRepresentative;
        this.tvaRateConfig = tvaRateConfig;
    }

    /**
     * Le plan du papier, relu intégralement en base. {@code fiche()} porte déjà les origines
     * nommées, le forfait, les pièces, l'état de clôture et le bloc du bon de livraison : le
     * document imprimé et l'écran lisent donc la même source, et ne peuvent pas se contredire.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> payload(String id) {
        FicheResponse fiche = ordre.fiche(id);
        MaintenanceResponse papier = fiche.papier();
        MaintenanceType type = typeOf(papier);

        Vehicle bus = papier.vehicleId() == null ? null
                : vehicles.findById(papier.vehicleId()).orElse(null);
        Client client = clientDe(papier, bus);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", type.label());
        // Le mot machine du type : la mise en page l'ignore, le contrôleur s'en sert pour
        // nommer le fichier. Déduire le nom du papier de son numéro seul serait deviner.
        payload.put("type", type.name().toLowerCase(Locale.ROOT));
        payload.put("brand", vendorName);
        payload.put("brand_lines", letterhead());
        payload.put("subtitle", subtitle(type));
        payload.put("number", papier.number());
        payload.put("status_label", "État : " + orDash(papier.statusLabel()));
        payload.put("date_label", "Édité le " + LocalDate.now().format(DATE));
        payload.put("badge", badge(type, papier, fiche.bon()));
        payload.put("boxes", boxes(type, papier, fiche, bus, client));
        payload.put("tables", tables(type, papier, fiche));
        // Clé « totals » : le devis seul. Absente ailleurs, et une ligne « Total » absente se
        // voit sur la page — ce qui vaut mieux qu'un « 0,000 » qui en tient lieu.
        if (type == MaintenanceType.DEVIS) {
            payload.put("totals", totals(papier));
        }
        payload.put("texts", texts(type, papier, fiche));
        payload.put("signatures", signatures(type, papier, client));
        payload.put("footer", footer(type));
        return payload;
    }

    // ------------------------------------------------------------------ l'en-tête

    /** L'identité de l'atelier, telle que la configuration la donne. Une clé vide reste sur la page. */
    private List<String> letterhead() {
        List<String> lines = new ArrayList<>();
        if (notBlank(vendorAddress)) {
            lines.add(vendorAddress.trim());
        }
        lines.add("RC : " + orDash(vendorTradeRegister));
        lines.add("Matricule fiscal : " + orDash(vendorTaxId));
        return lines;
    }

    private static String subtitle(MaintenanceType type) {
        return switch (type) {
            case DEVIS -> "Devis — le travail chiffré que le client accepte ou refuse";
            case OPERATION -> "Ordre de travail — ce que l'atelier exécute";
            case BL -> "Bon de livraison — restitution du bus et des pièces";
        };
    }

    /** Ce que CE papier prouve, en une ligne, et jusqu'où il va. */
    private static String badge(MaintenanceType type, MaintenanceResponse papier, BonDto bon) {
        return switch (type) {
            case DEVIS -> "Devis chiffré en dinars tunisiens (DT). Les montants viennent du serveur ;"
                    + " ce papier ne coche aucun palier de forfait.";
            case OPERATION -> "Papier interne : l'ordre décrit le travail et ne le facture pas (V38)."
                    + " Le prix est sur le devis accepté, pas ici.";
            case BL -> "Bon de livraison " + (estSigne(papier) ? "signé" : "NON signé")
                    + " — aucun montant n'y figure (V39)."
                    + (!estSigne(papier) && bon != null && bon.signature() != null && !bon.signature().pret()
                            ? " " + bon.signature().message() : "");
        };
    }

    // ------------------------------------------------------------------ les fiches

    private List<Map<String, Object>> boxes(MaintenanceType type, MaintenanceResponse papier,
                                            FicheResponse fiche, Vehicle bus, Client client) {
        List<Map<String, Object>> boxes = new ArrayList<>();
        boxes.add(box("Le papier", List.of(
                row("Nature", type.label()),
                row("Numéro", orDash(papier.number())),
                row("Date du papier", date(papier.date())),
                row("État", orDash(papier.statusLabel())),
                row("Émis par", vendorName),
                row("Technicien nommé", orDash(papier.technician())))));
        boxes.add(box("Le client et le bus", List.of(
                row("Client", client == null ? DASH : orDash(client.getName())),
                row("Code client", client == null ? DASH : orDash(client.getCode())),
                row("Châssis (VIN)", bus == null ? DASH : orDash(bus.getVin())),
                row("Immatriculation", bus == null ? DASH : orDash(bus.getRegistration())),
                row("Modèle", bus == null ? DASH : orDash(bus.getModel())),
                row("Compteur de la fiche du bus", km(bus == null ? null : bus.getMileage())))));

        OrigineDto origine = fiche.origine();
        if (origine != null && (notBlank(origine.numeroSource()) || notBlank(origine.numeroMission())
                || notBlank(origine.numeroSortie()))) {
            boxes.add(box("D'où vient ce papier", List.of(
                    row("Né de", origineLibelle(origine)),
                    row("Devis de la chaîne", orDash(origine.numeroDevis())),
                    row("Demande (mission)", orDash(origine.numeroMission())),
                    row("Sortie d'origine", orDash(origine.numeroSortie())),
                    row("Clichés de la sortie", nombreDeCliches(fiche)))));
        }

        ForfaitDto forfait = fiche.forfait();
        if (forfait != null) {
            boxes.add(box("Le forfait engagé", List.of(
                    row("Forfait vendu", orDash(forfait.labelForfait())),
                    row("Palier visé", orDash(forfait.labelPalier())),
                    row("Palier", orDash(forfait.etatDuPalier())),
                    row("Paliers faits", forfait.paliersFaits() + " / " + forfait.paliersTotal()),
                    row("Tenu par", orDash(forfait.tenuPar())))));
        }

        switch (type) {
            case DEVIS -> boxes.add(box("L'accord du client", List.of(
                    row("Envoyé le", date(papier.sentOn())),
                    row("Accepté le", date(papier.acceptedOn())),
                    row("Refusé le", date(papier.refusedOn())))));
            case OPERATION -> {
                boxes.add(box("La clôture de l'ordre", List.of(
                        row("Clôturé le", date(papier.closedOn())),
                        row("Raison écrite", orDash(papier.closureReason())),
                        row("Preuve", preuve(fiche.cloture())))));
                if (fiche.cloture() != null && !fiche.cloture().pret()) {
                    boxes.add(box("Ce qui manque encore", List.of(
                            row("Opérations à cocher", orDash(fiche.cloture().operationsNonCochees())),
                            row("Heures à écrire", orDash(fiche.cloture().heuresManquantes())))));
                }
            }
            case BL -> {
                boxes.add(box("La réception du bus", List.of(
                        row("Signataire", orDash(papier.signataireNom())),
                        row("À quel titre", orDash(papier.signataireQualite())),
                        row("Signé le", date(papier.signedOn())),
                        row("Pièces remplacées remises", ouiNon(papier.partsReturned())),
                        row("Compteur à la restitution", km(papier.mileageAtDelivery())))));
                BonDto bon = fiche.bon();
                if (bon != null && bon.travail() != null) {
                    boxes.add(box("Le travail rendu", List.of(
                            row("Ordre d'origine", orDash(bon.travail().numeroOrdre())),
                            row("Clôturé le", date(bon.travail().dateDeCloture())),
                            row("Vu sur le bus pendant le travail", orDash(bon.travail().anomalies())))));
                }
                if (bon != null && bon.intervenant() != null) {
                    boxes.add(box("L'intervention sur site", List.of(
                            row("Sortie", orDash(bon.intervenant().numeroSortie())),
                            row("Technicien", orDash(bon.intervenant().technicien())),
                            row("Départ", timestamp(bon.intervenant().depart())),
                            row("Fin", timestamp(bon.intervenant().fin())))));
                }
                if (bon != null && bon.compteur() != null) {
                    CompteurDto compteur = bon.compteur();
                    boxes.add(box("Le compteur, des deux côtés de la case", List.of(
                            row("Fiche du bus", km(compteur.valeurFiche())),
                            row("Écrit par", origineEcrite(compteur)),
                            row("Plus haut relevé", km(compteur.valeurRelevee())),
                            row("Relevé sur", orDash(compteur.sortieRelevee())),
                            row("Le", date(compteur.jourReleve())))));
                }
            }
        }
        return boxes;
    }

    /** Le papier parent, nommé par son type : « né du devis DV-… », pas un identifiant technique. */
    private static String origineLibelle(OrigineDto origine) {
        if (!notBlank(origine.numeroSource())) {
            return DASH;
        }
        return orDash(origine.typeSource()) + " " + origine.numeroSource().trim();
    }

    private static String nombreDeCliches(FicheResponse fiche) {
        int n = fiche.photosSortie() == null ? 0 : fiche.photosSortie().size();
        return n == 0 ? "aucun" : n + (n == 1 ? " cliché" : " clichés");
    }

    /** La preuve que l'atelier a montrée pour clôturer — ou ce qui manque encore. */
    private static String preuve(EtatClotureDto cloture) {
        if (cloture == null || cloture.justificatif() == null) {
            return DASH;
        }
        var justificatif = cloture.justificatif();
        List<String> retenues = new ArrayList<>();
        if (justificatif.photoApresDeLaSortie()) {
            retenues.add("photo « après » de la sortie " + orDash(justificatif.numeroSortie()));
        }
        if (justificatif.ficheSigneeJointe()) {
            retenues.add("fiche d'atelier signée jointe au papier");
        }
        if (justificatif.raisonEcrite()) {
            retenues.add("raison écrite au journal");
        }
        return retenues.isEmpty() ? DASH : String.join(" • ", retenues);
    }

    /** Qui a écrit le compteur de la fiche du bus — la porte unique le nomme déjà. */
    private static String origineEcrite(CompteurDto compteur) {
        if (!notBlank(compteur.origineFiche())) {
            return DASH;
        }
        return orDash(compteur.origineFiche())
                + (notBlank(compteur.parFiche()) ? " (" + compteur.parFiche().trim() + ")" : "");
    }

    // ------------------------------------------------------------------ les lignes

    private List<Map<String, Object>> tables(MaintenanceType type, MaintenanceResponse papier,
                                             FicheResponse fiche) {
        List<Map<String, Object>> tables = new ArrayList<>();
        if (type == MaintenanceType.DEVIS) {
            tables.add(lignesDeDevis(papier));
        } else if (type == MaintenanceType.OPERATION) {
            tables.add(lignesDOrdre(papier));
        } else {
            tables.add(piecesDuBon(fiche));
        }
        if (type != MaintenanceType.DEVIS) {
            tables.add(piecesPosees(fiche.piecesPosees(), type));
        }
        return tables;
    }

    /**
     * Les lignes du devis, seules à porter un prix. La colonne « Montant HT » est la même
     * multiplication que celle de {@code MaintenanceTotals} (quantité × prix unitaire) : le
     * total du bas de page doit se recompter ligne par ligne, sinon le client compte et ne
     * retombe pas sur le papier.
     */
    private Map<String, Object> lignesDeDevis(MaintenanceResponse papier) {
        List<List<String>> rows = new ArrayList<>();
        for (MaintenanceLineDto ligne : lignes(papier)) {
            rows.add(List.of(
                    orDash(ligne.reference()),
                    orDash(ligne.label()),
                    orDash(ligne.natureLabel()),
                    orDash(ligne.suppliedByLabel()),
                    quantity(ligne.quantity()),
                    money(ligne.unitPrice()),
                    money(ligne.quantity() == null || ligne.unitPrice() == null
                            ? null : ligne.quantity() * ligne.unitPrice())));
        }
        return table("Les lignes du devis, chiffrées en dinars tunisiens (DT)",
                List.of("Référence", "Désignation", "Nature", "Fourni par",
                        "Quantité", "P.U. HT (DT)", "Montant HT (DT)"),
                List.of(0.9, 2.2, 0.9, 0.8, 0.6, 0.65, 0.65),
                rows, "Aucune ligne sur ce devis.");
    }

    /**
     * Les lignes de l'ordre : le travail et le temps, jamais le prix. « Prévu » est le temps
     * standard du catalogue ({@code quantityText}), « Heures réelles » ce que l'atelier a écrit —
     * les deux colonnes côte à côte, c'est là que l'écart se lit.
     */
    private static Map<String, Object> lignesDOrdre(MaintenanceResponse papier) {
        List<List<String>> rows = new ArrayList<>();
        for (MaintenanceLineDto ligne : lignes(papier)) {
            rows.add(List.of(
                    orDash(ligne.reference()),
                    orDash(ligne.label()),
                    orDash(ligne.natureLabel()),
                    orDash(ligne.quantityText()),
                    ligne.done() == null ? DASH : (ligne.done() ? "Fait" : "Pas fait"),
                    hours(ligne.realHours())));
        }
        return table("Le travail de l'atelier, ligne par ligne — aucune colonne de prix (V38)",
                List.of("Référence", "Opération", "Nature", "Prévu", "Fait", "Heures réelles"),
                List.of(0.9, 2.6, 0.9, 0.7, 0.6, 0.8),
                rows, "Aucune opération sur cet ordre.");
    }

    /** Les pièces et fluides que Saphir a fournis, repris des lignes du travail (bloc du BL). */
    private static Map<String, Object> piecesDuBon(FicheResponse fiche) {
        List<List<String>> rows = new ArrayList<>();
        BonDto bon = fiche.bon();
        if (bon != null && bon.piecesSaphir() != null) {
            for (PieceDto piece : bon.piecesSaphir()) {
                rows.add(List.of(orDash(piece.reference()), orDash(piece.designation()),
                        quantity(piece.quantite())));
            }
        }
        return table("Pièces et fluides fournis par Saphir, rendus avec le bus — sans prix (V39)",
                List.of("Référence", "Désignation", "Quantité"),
                List.of(1.0, 2.6, 0.6),
                rows, "Rien n'a été fourni par Saphir sur ce papier.");
    }

    /** Les pièces posées sur ce papier, et ce qu'elles sont devenues. */
    private static Map<String, Object> piecesPosees(List<ItemPartResponse> pieces, MaintenanceType type) {
        List<List<String>> rows = new ArrayList<>();
        if (pieces != null) {
            for (ItemPartResponse piece : pieces) {
                rows.add(List.of(
                        orDash(piece.reference()),
                        orDash(piece.designation()),
                        quantity(piece.quantity()),
                        date(piece.fittedOn()),
                        piece.returned() ? "retournée" + (notBlank(piece.returnReason())
                                ? " — " + piece.returnReason().trim() : "")
                        : "pas retournée"));
            }
        }
        return table(type == MaintenanceType.BL
                ? "Pièces posées sur ce bon de livraison"
                : "Pièces posées pendant ce papier, et leur sort",
                List.of("Référence", "Désignation", "Quantité", "Posée le", "Devenue"),
                List.of(0.9, 2.4, 0.6, 0.7, 1.2),
                rows, "Aucune pièce posée sur ce papier.");
    }

    private static List<MaintenanceLineDto> lignes(MaintenanceResponse papier) {
        return papier.details() == null ? List.of() : papier.details();
    }

    // ------------------------------------------------------------------ l'argent du devis

    /** Total HT, TVA, total TTC — relus du papier, pas recalculs à la page. */
    private List<List<String>> totals(MaintenanceResponse papier) {
        List<List<String>> rows = new ArrayList<>();
        Double ht = papier.totalHT();
        Double ttc = papier.totalTTC();
        rows.add(List.of("Total HT", money(ht)));
        if (ht != null && ttc != null) {
            rows.add(List.of(tvaLabel(), money(ttc - ht)));
        }
        rows.add(List.of("Total TTC", money(ttc)));
        return rows;
    }

    /** Le taux de la config, dit en pourcentage : 19 % s'écrit « 19 % », 5,5 % reste 5,5 %. */
    private String tvaLabel() {
        double pourCent = MaintenanceTotals.tvaFraction(tvaRateConfig) * 100;
        String mot = String.format(FR, "%.2f", pourCent);
        if (mot.endsWith(",00")) {
            mot = mot.substring(0, mot.length() - 3);
        } else if (mot.endsWith("0")) {
            mot = mot.substring(0, mot.length() - 1);
        }
        return "TVA " + mot + " %";
    }

    // ------------------------------------------------------------------ les blocs de texte

    private List<Map<String, Object>> texts(MaintenanceType type, MaintenanceResponse papier,
                                           FicheResponse fiche) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(text("Ce qui est écrit sur le papier", papier.description()));
        switch (type) {
            case DEVIS -> {
                if (notBlank(papier.refusalReason())) {
                    blocks.add(text("Motif du refus écrit par le client", papier.refusalReason()));
                }
                blocks.add(text("Ce que ce devis ne fait pas",
                        "Il nomme éventuellement un palier dû, mais il ne le coche jamais : seul le"
                                + " papier de travail l'acquitte."));
            }
            case OPERATION -> {
                blocks.add(text("Anomalies constatées sur le bus pendant le travail", papier.anomalies()));
                blocks.add(text("Raison écrite à la clôture", papier.closureReason()));
                if (fiche.cloture() != null && notBlank(fiche.cloture().message())) {
                    blocks.add(text("État de la clôture", fiche.cloture().message()));
                }
            }
            case BL -> {
                blocks.add(text("Réserves du client en reprenant le bus", papier.reserves()));
                BonDto bon = fiche.bon();
                if (bon != null && bon.travail() != null) {
                    blocks.add(text("Ce qui avait été vu sur le bus pendant le travail",
                            bon.travail().anomalies()));
                }
                // « Ce qui manque avant de signer » ne se imprime que sur un papier qui n'est pas
                // encore signé : sur la feuille d'archive du bon de BL-2026-0094, la liste aurait
                // porté « signature refusée » sous « Bon de livraison signé ».
                if (!estSigne(papier) && bon != null && bon.signature() != null && !bon.signature().pret()) {
                    blocks.add(text("Pour que la signature passe",
                            String.join(" • ", bon.signature().ceQuiManque())));
                }
            }
        }
        return blocks;
    }

    // ------------------------------------------------------------------ les cases à signer

    /**
     * Les cases du bas de page. Un nom est écrit quand le serveur le connaît (le client d'un
     * devis, le signataire d'un BL signé) ; sinon la case reste vide et se signe à la main —
     * une signature que l'atelier aurait « préremplie » n'en est pas une.
     */
    private List<Map<String, Object>> signatures(MaintenanceType type, MaintenanceResponse papier,
                                                 Client client) {
        List<Map<String, Object>> signatures = new ArrayList<>();
        switch (type) {
            case DEVIS -> {
                signatures.add(signature(client == null ? "" : orDash(client.getName()),
                        "Le client — devis lu, chiffré et accepté"));
                signatures.add(signature(nomDeAtelier(), "Saphir — devis établi par l'atelier"));
            }
            case OPERATION -> {
                signatures.add(signature(orDash(papier.technician()),
                        "L'atelier — opération exécutée"));
                signatures.add(signature(estFini(papier) ? nomDeAtelier() : "",
                        "Le chef d'atelier — ordre clôturé"));
            }
            case BL -> {
                signatures.add(signature(estSigne(papier) ? orDash(papier.signataireNom()) : "",
                        "Le client — bus repris, lu et approuvé"));
                signatures.add(signature(estSigne(papier) ? nomDeAtelier() : "",
                        "Le chef d'atelier — restitution validée"));
            }
        }
        return signatures;
    }

    /** Le nom qui engage l'atelier sur la case : le représentant s'il est configuré, sinon la société. */
    private String nomDeAtelier() {
        return notBlank(vendorRepresentative) ? vendorRepresentative.trim() : vendorName;
    }

    private static Map<String, Object> signature(String name, String label) {
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("name", name);
        signature.put("label", label);
        return signature;
    }

    private static Map<String, Object> box(String heading, List<List<String>> rows) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("heading", heading);
        box.put("rows", rows);
        return box;
    }

    private static List<String> row(String label, String value) {
        return List.of(label, value);
    }

    private static Map<String, Object> table(String heading, List<String> columns,
                                             List<Double> weights, List<List<String>> rows,
                                             String note) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("heading", heading);
        table.put("columns", columns);
        table.put("weights", weights);
        table.put("rows", rows);
        table.put("note", note);
        return table;
    }

    private static Map<String, Object> text(String heading, String body) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("heading", heading);
        text.put("body", notBlank(body) ? body.trim() : "Rien n'a été écrit pour cette partie.");
        return text;
    }

    /** Le pied de page dit ce que le papier contient côté argent — c'est la première question posée. */
    private String footer(MaintenanceType type) {
        String argent = type == MaintenanceType.DEVIS
                ? " — les montants sont en dinars tunisiens (DT)"
                : " — aucun montant ne figure sur ce papier";
        return vendorName + " — " + vendorAddress + argent
                + " — document généré automatiquement le " + LocalDate.now().format(DATE);
    }

    // ------------------------------------------------------------------ les mots

    /**
     * Le type du papier, lu sur le mot que {@code MaintenanceResponse} rend en minuscules. Un
     * mot inconnu se refuse : retomber sur « devis » imprimait des prix sur un papier dont
     * personne ne sait ce qu'il rend.
     */
    private static MaintenanceType typeOf(MaintenanceResponse papier) {
        if (papier.type() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le papier « " + papier.number()
                    + " » n'a pas de nature en base : rien à imprimer tant que le document n'est pas un"
                    + " devis, un ordre de travail ou un bon de livraison.");
        }
        try {
            return MaintenanceType.valueOf(papier.type().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nature de papier inconnue (« "
                    + papier.type() + " » sur " + papier.number() + ") : la mise en page refuse de deviner"
                    + " si ce papier chiffre ou non.");
        }
    }

    /** Le client du papier, et à défaut celui du bus : un BL peut porter une fiche client vide. */
    private Client clientDe(MaintenanceResponse papier, Vehicle bus) {
        if (notBlank(papier.clientId())) {
            Client client = clients.findById(papier.clientId().trim()).orElse(null);
            if (client != null) {
                return client;
            }
        }
        return bus == null || !notBlank(bus.getClientId()) ? null
                : clients.findById(bus.getClientId().trim()).orElse(null);
    }

    private static boolean estSigne(MaintenanceResponse papier) {
        return "signed".equalsIgnoreCase(orEmpty(papier.status()));
    }

    private static boolean estFini(MaintenanceResponse papier) {
        String etat = orEmpty(papier.status());
        return "completed".equalsIgnoreCase(etat) || "delivered".equalsIgnoreCase(etat);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String ouiNon(Boolean value) {
        return value == null ? DASH : (value ? "oui" : "non");
    }

    private static String date(LocalDate value) {
        return value == null ? DASH : value.format(DATE);
    }

    private static String timestamp(LocalDateTime value) {
        return value == null ? DASH : value.format(DATE) + " à "
                + String.format(FR, "%02dh%02d", value.getHour(), value.getMinute());
    }

    private static String km(Long value) {
        return value == null ? "non relevé" : value + " km";
    }

    /** La quantité s'écrit comme elle se pense : « 2 » pièces, pas « 2.0 » ; un 0,25 reste français. */
    private static String quantity(Double value) {
        if (value == null) {
            return DASH;
        }
        if (value == Math.rint(value) && !value.isInfinite()) {
            return String.valueOf((long) value.doubleValue());
        }
        return String.valueOf(value).replace('.', ',');
    }

    /** Le prix en dinars, trois décimales — la même pose que partout ailleurs dans le portail. */
    private static synchronized String money(Double value) {
        return value == null ? DASH : MONEY.format(value);
    }

    /** Les heures réelles à deux décimales : le quart d'heure s'écrit 0,25. */
    private static synchronized String hours(Double value) {
        return value == null ? DASH : HOURS.format(value);
    }

    private static String orDash(List<String> valeurs) {
        if (valeurs == null || valeurs.isEmpty()) {
            return DASH;
        }
        return String.join(" • ", valeurs);
    }

    private static String orDash(String value) {
        return notBlank(value) ? value.trim() : DASH;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static NumberFormat moneyFormat() {
        NumberFormat nf = NumberFormat.getNumberInstance(FR);
        nf.setMinimumFractionDigits(3);
        nf.setMaximumFractionDigits(3);
        return nf;
    }

    private static NumberFormat hoursFormat() {
        NumberFormat nf = NumberFormat.getNumberInstance(FR);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf;
    }
}
