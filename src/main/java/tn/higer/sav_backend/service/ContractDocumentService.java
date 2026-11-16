package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tn.higer.sav_backend.dto.ContractDtos;
import tn.higer.sav_backend.dto.ContractDtos.ContractLine;
import tn.higer.sav_backend.dto.ContractDtos.ContractResponse;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contenu du contrat de maintenance, prêt à être mis en page.
 *
 * <p>Ce service ne dessine rien : il rassemble et forme toutes les valeurs du document
 * (numéro, date, identité des deux parties, clauses effectives, annexe des véhicules,
 * totaux et somme en toutes lettres) dans un plan JSON. La mise en page A4 est faite par
 * {@code scripts/render_contract_pdf.py}, qui ne calcule rien. Cette séparation garantit
 * qu'aucun chiffre n'est inventé à l'affichage : tout vient de la base et des calculs serveur.</p>
 *
 * <p>Les clauses reprennent la saisie quand elle existe, sinon la rédaction standard
 * ({@link ContractClauses}). figures = false (rôle consultation) retire les montants du plan,
 * y compris la colonne de prix de l'annexe et la somme en toutes lettres — la confidentialité
 * ne dépend jamais de l'interface ni du rendu.</p>
 */
@Service
public class ContractDocumentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale FR = Locale.FRENCH;
    private static final NumberFormat MONEY = moneyFormat();
    private static final String DASH = "—";

    private final ContractClauses clauses;
    private final String vendorAddress;
    private final String vendorTradeRegister;
    private final String vendorTaxId;
    private final String vendorRepresentative;
    private final BigDecimal tvaFraction;

    public ContractDocumentService(ContractClauses clauses,
                                   @Value("${app.vendor.address}") String vendorAddress,
                                   @Value("${app.vendor.trade-register}") String vendorTradeRegister,
                                   @Value("${app.vendor.tax-id}") String vendorTaxId,
                                   @Value("${app.vendor.representative}") String vendorRepresentative,
                                   @Value("${app.tva.rate:19}") double tvaRateConfig) {
        this.clauses = clauses;
        this.vendorAddress = vendorAddress;
        this.vendorTradeRegister = vendorTradeRegister;
        this.vendorTaxId = vendorTaxId;
        this.vendorRepresentative = vendorRepresentative;
        // Même normalisation que ContractController : 19 -> 0.19, 0.19 -> 0.19.
        double v = tvaRateConfig < 0 ? 19 : tvaRateConfig;
        if (v > 1) {
            v = v / 100.0;
        }
        this.tvaFraction = BigDecimal.valueOf(v);
    }

    /**
     * Plan du document : les clés sont celles que lit le script Python. Les totaux sont
     * recalculés ici à partir des lignes (mêmes règles que ContractDtos), jamais lus d'un champ.
     */
    public Map<String, Object> payload(Contract c, Client client, List<Vehicle> lines, boolean figures) {
        ContractResponse view = ContractDtos.toResponse(c, lines, figures, tvaFraction);
        String clientName = client != null ? client.getName() : DASH;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brand", clauses.vendorName());
        payload.put("subtitle", "Contrat de maintenance de véhicules");
        payload.put("number", view.number());
        payload.put("date_label", "Date : " + date(view.contractDate()));
        payload.put("parties", parties(clientName, client));
        payload.put("clauses", articleList(view, clientName));
        payload.put("annex", annex(view, figures));
        payload.put("totals", totals(view, figures));
        payload.put("signatures", signatures(clientName));
        payload.put("footer", footer(figures));
        return payload;
    }

    private List<Map<String, Object>> parties(String clientName, Client client) {
        // Les deux mots du métier : celui qui entretient et celui qui confie le bus. Le contrat
        // que ce portail rédige n'est pas un acte de vente, il ne doit pas en prendre l'habit.
        return List.of(
                party("Le prestataire", clauses.vendorName(), vendorAddress, vendorTradeRegister, vendorTaxId),
                party("Le client", clientName, client != null ? joinAddress(client) : null,
                        client != null ? client.getTradeRegister() : null,
                        client != null ? client.getTaxId() : null));
    }

    private static Map<String, Object> party(String heading, String name, String address,
                                             String tradeRegister, String taxId) {
        List<String> lines = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            lines.add(name.trim());
        }
        if (address != null && !address.isBlank()) {
            lines.add(address.trim());
        }
        // Le registre de commerce et le matricule fiscal s'affichent toujours : une absence
        // doit se lire sur le document, pas disparaître silencieusement.
        lines.add("RC : " + orDash(tradeRegister));
        lines.add("Matricule fiscal : " + orDash(taxId));
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("heading", heading);
        party.put("lines", lines);
        return party;
    }

    /** Les six articles du contrat, dans l'ordre de signature. Article 6 seulement s'il y a du texte. */
    private List<Map<String, String>> articleList(ContractResponse v, String clientName) {
        List<Map<String, String>> articles = new ArrayList<>();
        articles.add(article("Article 1 — Objet",
                ContractClauses.effective(v.objectClause(), clauses.objectStandard(clientName, v.vehicleCount()))));
        articles.add(article("Article 2 — Prestations et délais d'intervention",
                ContractClauses.effective(v.deliveryClause(), clauses.deliveryStandard())));
        articles.add(article("Article 3 — Garantie des travaux",
                ContractClauses.effective(v.warrantyClause(), clauses.warrantyStandard())));
        articles.add(article("Article 4 — Pièces déposées et propriété",
                ContractClauses.effective(v.ownershipClause(), clauses.ownershipStandard())));
        articles.add(article("Article 5 — Litiges",
                ContractClauses.effective(v.disputeClause(), clauses.disputeStandard())));
        String special = ContractClauses.effective(v.specialTerms(), "");
        if (!special.isBlank()) {
            articles.add(article("Article 6 — Clauses particulières", special));
        }
        return articles;
    }

    private static Map<String, String> article(String title, String body) {
        Map<String, String> clause = new LinkedHashMap<>();
        clause.put("title", title);
        clause.put("body", body);
        return clause;
    }

    private Map<String, Object> annex(ContractResponse v, boolean figures) {
        List<String> columns = new ArrayList<>(List.of(
                "Réf.", "Châssis (VIN)", "Immat.", "Modèle", "Année", "Mise en circ.", "Fin garantie"));
        if (figures) {
            columns.add("Forfait HT");
        }
        List<List<String>> rows = new ArrayList<>();
        int index = 1;
        for (ContractLine line : v.lines()) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(index++));
            row.add(orDash(line.vin()));
            row.add(orDash(line.registration()));
            row.add(orDash(line.model()));
            row.add(line.year() == null ? DASH : String.valueOf(line.year()));
            row.add(date(line.commissioningDate()));
            row.add(date(line.warrantyEndDate()));
            if (figures) {
                row.add(money(line.unitPriceHt()) + " DT");
            }
            rows.add(row);
        }
        Map<String, Object> annex = new LinkedHashMap<>();
        annex.put("heading", "Annexe — Véhicules entretenus");
        annex.put("columns", columns);
        annex.put("rows", rows);
        annex.put("note", "Aucun véhicule rattaché à ce contrat.");
        return annex;
    }

    private Map<String, Object> totals(ContractResponse v, boolean figures) {
        Map<String, Object> totals = new LinkedHashMap<>();
        List<List<String>> rows = new ArrayList<>();
        if (figures) {
            rows.add(List.of("Total HT", money(v.totalHT()) + " DT"));
            rows.add(List.of("TVA " + tvaLabel() + " %", money(v.tva()) + " DT"));
            rows.add(List.of("Total TTC", money(v.totalTTC()) + " DT"));
        }
        totals.put("rows", rows);
        // La somme en toutes lettres ne quitte jamais le serveur : elle ne peut pas
        // contredire le total qu'elle épelle, et elle n'existe pas sans les montants.
        totals.put("words", figures
                ? "Arrêté le présent contrat de maintenance à la somme de " + amountInWords(v.totalTTC()) + "."
                : "");
        return totals;
    }

    private List<Map<String, String>> signatures(String clientName) {
        String vendorLabel = "Le prestataire"
                + (vendorRepresentative.isBlank() ? "" : " — " + vendorRepresentative.trim());
        return List.of(signature(clauses.vendorName(), vendorLabel),
                signature(clientName, "Le client (lu et approuvé)"));
    }

    private static Map<String, String> signature(String name, String label) {
        Map<String, String> signature = new LinkedHashMap<>();
        signature.put("name", name);
        signature.put("label", label);
        return signature;
    }

    private String footer(boolean figures) {
        return "Document généré automatiquement par le portail SAV Higer le "
                + LocalDate.now().format(DATE)
                + " — établi en deux exemplaires originaux."
                + (figures ? " Montants en dinars tunisiens (DT)." : "");
    }

    // --- helpers de mise en forme -------------------------------------------------------

    private static String joinAddress(Client client) {
        StringBuilder sb = new StringBuilder();
        if (client.getAddress() != null && !client.getAddress().isBlank()) {
            sb.append(client.getAddress().trim());
        }
        if (client.getCity() != null && !client.getCity().isBlank()) {
            sb.append(sb.length() > 0 ? ", " : "").append(client.getCity().trim());
        }
        if (client.getCountry() != null && !client.getCountry().isBlank()) {
            sb.append(sb.length() > 0 ? ", " : "").append(client.getCountry().trim());
        }
        return sb.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? DASH : value.trim();
    }

    private static String date(LocalDate d) {
        return d == null ? DASH : d.format(DATE);
    }

    private static NumberFormat moneyFormat() {
        NumberFormat nf = NumberFormat.getNumberInstance(FR);
        nf.setMinimumFractionDigits(3);
        nf.setMaximumFractionDigits(3);
        return nf;
    }

    private static synchronized String money(BigDecimal value) {
        if (value == null) {
            return DASH;
        }
        return MONEY.format(value.setScale(3, RoundingMode.HALF_UP));
    }

    /** 19 -> « 19 », 0.19 -> « 19 » : le libellé de la ligne TVA suit la configuration. */
    private String tvaLabel() {
        BigDecimal percent = tvaFraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        return percent.stripTrailingZeros().toPlainString();
    }

    /**
     * Somme en toutes lettres (dinars + millimes), en français. Le texte est calculé,
     * jamais saisi : il ne peut donc pas contredire le total affiché. Borné aux montants
     * d'un contrat de bus ; au-delà de 999 999 999,999 on retombe sur le chiffre formaté.
     */
    static String amountInWords(BigDecimal amount) {
        if (amount == null) {
            return DASH;
        }
        BigDecimal value = amount.setScale(3, RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            return "montant négatif";
        }
        long dinars = value.longValue();
        int millimes = value.remainder(BigDecimal.ONE)
                .movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValue();
        if (dinars > 999_999_999L) {
            return value.stripTrailingZeros().toPlainString() + " dinars tunisiens";
        }
        StringBuilder sb = new StringBuilder();
        if (dinars == 0) {
            sb.append("zéro dinar");
        } else {
            // « de » devant l'unité à partir du million : deux millions DE dinars.
            sb.append(integerToFrench(dinars)).append(dinars >= 1_000_000L ? " de dinar" : " dinar");
            if (dinars > 1) {
                sb.append('s');
            }
        }
        if (millimes > 0) {
            sb.append(" et ").append(integerToFrench(millimes)).append(" millime");
            if (millimes > 1) {
                sb.append('s');
            }
        }
        return sb.toString();
    }

    // --- entier -> lettres françaises (0 .. 999 999 999) -------------------------------

    private static final String[] UNITS = {
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept",
            "dix-huit", "dix-neuf"};
    private static final String[] TENS = {
            "", "", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante",
            "quatre-vingt", "quatre-vingt"};

    private static String integerToFrench(long n) {
        if (n < 20) {
            return UNITS[(int) n];
        }
        if (n < 100) {
            return tens((int) n);
        }
        if (n < 1_000) {
            return hundreds((int) n);
        }
        if (n < 1_000_000) {
            int thousands = (int) (n / 1_000);
            int rest = (int) (n % 1_000);
            // « cent » reste invariable devant « mille » : deux cent mille, pas deux cents mille.
            String prefix = thousands == 1
                    ? "mille"
                    : invariableBeforeMille(integerToFrench(thousands)) + " mille";
            return rest == 0 ? prefix : prefix + " " + integerToFrench(rest);
        }
        long millions = n / 1_000_000;
        int rest = (int) (n % 1_000_000);
        String prefix = millions == 1 ? "un million" : integerToFrench(millions) + " millions";
        return rest == 0 ? prefix : prefix + " " + integerToFrench(rest);
    }

    private static String tens(int n) {
        int ten = n / 10;
        int unit = n % 10;
        if (ten == 7 || ten == 9) {
            // 70-79 et 90-99 se construisent sur 60 et 80 : soixante-dix, quatre-vingt-dix…
            String base = ten == 7 ? "soixante" : "quatre-vingt";
            if (unit == 0) {
                return base + "-dix";
            }
            if (unit == 1 && ten == 7) {
                return "soixante et onze";
            }
            return base + "-" + UNITS[unit + 10];
        }
        if (unit == 0) {
            return TENS[ten] + (ten == 8 ? "s" : "");
        }
        if (unit == 1 && ten != 8) {
            return TENS[ten] + " et un";
        }
        return TENS[ten] + "-" + UNITS[unit];
    }

    private static String invariableBeforeMille(String words) {
        return words.endsWith("cents") ? words.substring(0, words.length() - 1) : words;
    }

    private static String hundreds(int n) {
        int hundred = n / 100;
        int rest = n % 100;
        String prefix = hundred == 1 ? "cent" : UNITS[hundred] + " cent";
        if (rest == 0) {
            return hundred == 1 ? "cent" : prefix + "s";
        }
        return prefix + " " + integerToFrench(rest);
    }
}
