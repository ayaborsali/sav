package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.InterventionResponse;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeDto;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPrime;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.SavPriority;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionPrimeRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Le bon d'intervention, prêt à être mis en page.
 *
 * <p>Ce service ne dessine rien : il rassemble les valeurs du document (bus, client, équipe,
 * horaires serveur, durées, compteurs, diagnostic, dossier SAV rattaché) dans un plan JSON que
 * {@code scripts/render_intervention_pdf.py} pose sur la page. Comme pour le contrat, aucun
 * chiffre n'est inventé à l'affichage : les heures viennent des horodatages posés par les
 * boutons d'étape, les durées sont celles déjà calculées par {@link InterventionService}, et le
 * montant vient de la ligne de prime validée.</p>
 *
 * <p>DEUX VERSIONS, et le choix se fait ici, avant la mise en page :</p>
 * <ul>
 *   <li>{@code interne} — tout, y compris la prime telle qu'elle a été validée. Réservée à la
 *       paie ({@link Access#seesPayroll}) ;</li>
 *   <li>{@code client} — le travail constaté, les heures et les compteurs, AUCUN montant, aucune
 *       ligne de paie, aucun signal d'anomalie. C'est la feuille que le client signe.</li>
 * </ul>
 * <p>Une prime non validée n'est jamais imprimée : un chiffrage estimé posé sur un document
 * signable ressemblerait à une somme due. L'estimation se lit à l'écran, pas sur le bon.</p>
 */
@Service
public class InterventionDocumentService {

    /** Les deux versions acceptées dans la requête ; le serveur refuse tout le reste. */
    public static final String VERSION_INTERNE = "interne";
    public static final String VERSION_CLIENT = "client";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'MM");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale FR = Locale.FRENCH;
    private static final NumberFormat MONEY = moneyFormat();
    /** Le quart d'heure s'imprime 0,25 : une durée facturée sans décimales ne se contrôle pas. */
    private static final NumberFormat HOURS = hoursFormat();
    private static final String DASH = "—";

    private final InterventionService visibility;
    private final VehicleRepository vehicles;
    private final InterventionPhotoRepository photos;
    private final InterventionPrimeRepository primes;
    private final String vendorName;
    private final String vendorAddress;

    public InterventionDocumentService(InterventionService visibility,
                                       VehicleRepository vehicles,
                                       InterventionPhotoRepository photos,
                                       InterventionPrimeRepository primes,
                                       @Value("${app.vendor.name}") String vendorName,
                                       @Value("${app.vendor.address}") String vendorAddress) {
        this.visibility = visibility;
        this.vehicles = vehicles;
        this.photos = photos;
        this.primes = primes;
        this.vendorName = vendorName;
        this.vendorAddress = vendorAddress;
    }

    /**
     * Plan du document pour la version demandée. La visibilité de la sortie est vérifiée comme
     * partout ailleurs dans le module (le rôle CONSULTATION et le technicien non concerné sont
     * refusés), et la version interne exige en plus le droit de voir la paie.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> payload(String interventionId, String version,
                                       Authentication auth, Jwt jwt) {
        Intervention i = visibility.requireVisible(interventionId, auth, jwt);
        boolean internal = isInternalVersion(version);
        if (internal && !Access.seesPayroll(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La version interne du bon d'intervention comporte la prime : elle est "
                            + "réservée à l'administrateur et au chef d'atelier. Imprimez la version "
                            + "client pour le client.");
        }
        return build(i, internal);
    }

    /** Une faute de frappe sur ?version= doit être dite, pas tombée silencieusement sur le client. */
    private static boolean isInternalVersion(String version) {
        if (version == null || version.isBlank() || VERSION_CLIENT.equalsIgnoreCase(version.trim())) {
            return false;
        }
        if (VERSION_INTERNE.equalsIgnoreCase(version.trim())) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Version de bon inconnue : « " + version + " ». Les deux seules sont "
                        + VERSION_CLIENT + " (sans montant) et " + VERSION_INTERNE + " (avec la prime).");
    }

    // ------------------------------------------------------------------ le plan

    private Map<String, Object> build(Intervention i, boolean internal) {
        InterventionResponse r = visibility.full(i);
        Vehicle bus = i.getVehicleId() == null ? null : vehicles.findById(i.getVehicleId()).orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brand", vendorName);
        payload.put("subtitle", "Bon d'intervention — dépannage sur site");
        payload.put("number", r.number());
        payload.put("date_label", "Édité le " + LocalDateTime.now().format(DATE));
        payload.put("badge", internal
                ? "VERSION INTERNE — ce document comporte la prime validée. Il ne sort pas de l'atelier."
                : "Version client — constat de l'intervention, aucun montant n'y figure.");
        payload.put("boxes", boxes(r, bus));
        payload.put("tables", tables(r, i, internal));
        payload.put("texts", texts(r));
        payload.put("signatures", signatures(r, internal));
        payload.put("footer", footer(internal));
        return payload;
    }

    private List<Map<String, Object>> boxes(InterventionResponse r, Vehicle bus) {
        List<Map<String, Object>> boxes = new ArrayList<>();
        boxes.add(box("La sortie", List.of(
                row("Statut", statusLabel(r.status())),
                row("Priorité", priorityLabel(r.priority())),
                row("Enregistrée le", date(r.times().reportedAt())),
                row("Dossier SAV rattaché", orDash(r.savCaseNumber())))));
        boxes.add(box("Bus en panne", List.of(
                row("Client", orDash(r.clientName())),
                row("Châssis (VIN)", bus == null ? DASH : orDash(bus.getVin())),
                row("Immatriculation", bus == null ? DASH : orDash(bus.getRegistration())),
                row("Modèle", bus == null ? DASH : orDash(bus.getModel())),
                row("Compteur déclaré sur le bus", km(r.mileages().busMileage())),
                row("Compteur du bus à la fin de l'intervention", km(r.mileages().busMileageEnd())))));
        boxes.add(box("Lieu de l'intervention", List.of(
                row("Adresse", orDash(r.location() == null ? null : r.location().address())),
                row("Ville", orDash(r.location() == null ? null : r.location().city())),
                row("Appel déclaré par", orDash(r.requestedBy())))));
        boxes.add(box("Équipe et voiture de déplacement", List.of(
                row("Technicien intervenu", orDash(r.technicianName())),
                row("Voiture de déplacement", orDash(r.serviceVehicleRegistration())),
                row("Km au départ", km(r.mileages().startMileage())),
                row("Km au retour", km(r.mileages().endMileage())),
                row("Distance parcourue", distance(r.mileages().distanceKm())))));
        return boxes;
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

    /** Les huit heures d'étape et les trois durées déduites — la colonne qui compte au contrôle. */
    private List<Map<String, Object>> tables(InterventionResponse r, Intervention i, boolean internal) {
        List<Map<String, Object>> tables = new ArrayList<>();
        tables.add(timings(r));
        if (internal) {
            tables.add(proofTable(i.getId()));
            tables.add(primeTable(i.getId(), r.number()));
        }
        return tables;
    }

    private Map<String, Object> timings(InterventionResponse r) {
        var t = r.times();
        var d = r.durations();
        List<List<String>> rows = new ArrayList<>();
        rows.add(timingRow("Appel déclaré (heure annoncée par l'appelant)", t.reportedAt(), null));
        rows.add(timingRow("Affectation de la voiture de déplacement", t.dispatchedAt(), null));
        rows.add(timingRow("Départ de l'atelier", t.departedAt(), null));
        rows.add(timingRow("Arrivée sur place", t.arrivedAt(), "Trajet : " + hoursLabel(d.travelMinutes())));
        rows.add(timingRow("Fin de l'intervention", t.finishedAt(), "Sur place : " + hoursLabel(d.onSiteMinutes())));
        rows.add(timingRow("Départ des lieux", t.leftSiteAt(), "Total sur site : " + hoursLabel(d.siteMinutes())));
        rows.add(timingRow("Retour à l'atelier", t.returnedAt(), "Sortie : " + hoursLabel(d.totalMinutes())));
        rows.add(timingRow("Clôture par le chef d'atelier", t.closedAt(), null));

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("heading", "Horodatages enregistrés par le serveur (aucune heure n'est saisie à la main)");
        table.put("columns", List.of("Étape", "Date et heure", "Durée déduite"));
        table.put("weights", List.of(1.5, 1.15, 1.0));
        table.put("rows", rows);
        table.put("note", "Aucune étape horodatée.");
        return table;
    }

    private static List<String> timingRow(String label, LocalDateTime at, String duration) {
        return List.of(label, at == null ? "non horodaté" : at.format(TIMESTAMP),
                duration == null ? DASH : duration);
    }

    /** Les clichés comptés par angle : c'est la preuve que le travail a été fait, pas déclaré. */
    private Map<String, Object> proofTable(String interventionId) {
        Map<PhotoAngle, Integer> counts = new EnumMap<>(PhotoAngle.class);
        for (PhotoAngle angle : PhotoAngle.values()) {
            counts.put(angle, 0);
        }
        photos.findByInterventionIdOrderByUploadedAtAsc(interventionId)
                .forEach(p -> {
                    if (p.getAngle() != null) {
                        counts.merge(p.getAngle(), 1, Integer::sum);
                    }
                });

        List<List<String>> rows = new ArrayList<>();
        counts.forEach((angle, count) -> rows.add(List.of(
                angle.label(),
                count == 0 ? "aucun cliché" : count + (count > 1 ? " clichés" : " cliché"))));

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("heading", "Preuves photographiques au dossier");
        table.put("columns", List.of("Ce que la photo doit montrer", "Clichés versés"));
        table.put("weights", List.of(1.4, 1.0));
        table.put("rows", rows);
        table.put("note", "Aucun cliché au dossier.");
        return table;
    }

    /**
     * La prime, telle qu'elle a été validée (taux et forfait recopiés dans la ligne). Sans ligne
     * validée, la table dit qu'aucun montant n'est engagé — elle ne remplace pas l'écran Primes.
     */
    private Map<String, Object> primeTable(String interventionId, String number) {
        InterventionPrime p = primes.findByInterventionId(interventionId).orElse(null);
        List<List<String>> rows = new ArrayList<>();
        if (p == null) {
            rows.add(row("Prime de la sortie " + number, "non validée à l'édition — aucun montant engagé"));
        } else {
            rows.add(row("Durée retenue", minutesLabel(p.getPaidMinutes())));
            rows.add(row("Heures facturables", hours2(p.getBillableHours()) + " h"));
            rows.add(row("Taux horaire", money(p.getHourlyRate()) + " DT"));
            rows.add(row("Forfait de sortie", p.getDispatchAllowance() == null
                    ? "aucun" : money(p.getDispatchAllowance()) + " DT"));
            rows.add(row("Montant de la prime", money(p.getAmount()) + " DT"));
            rows.add(row("Validée par", orDash(p.getValidatedByEmail()) + ", le "
                    + (p.getValidatedAt() == null ? DASH : p.getValidatedAt().format(TIMESTAMP))));
            rows.add(row("Signaux au moment du calcul", orDash(PrimeDto.split(p.getAnomalies()))));
            rows.add(row("Justification écrite", orDash(p.getJustification())));
        }

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("heading", "Prime d'intervention — interne, ne pas remettre au client");
        table.put("columns", List.of("Élément", "Valeur"));
        table.put("weights", List.of(1.0, 1.6));
        table.put("rows", rows);
        table.put("note", "Aucune prime validée pour cette sortie.");
        return table;
    }

    private List<Map<String, Object>> texts(InterventionResponse r) {
        List<Map<String, Object>> texts = new ArrayList<>();
        texts.add(text("Problème déclaré à l'appel", r.description()));
        texts.add(text("Diagnostic constaté sur place", diagnosis(r)));
        texts.add(text("Résultat de l'intervention", r.resultNote()));
        texts.add(text("Reprise du bus à l'atelier", r.receivedBy()));
        texts.add(text("Note de clôture du chef d'atelier", r.closureNote()));
        return texts;
    }

    private static String diagnosis(InterventionResponse r) {
        List<String> lines = new ArrayList<>();
        if (notBlank(r.defectCode())) {
            lines.add("Code défaut : " + r.defectCode().trim());
        }
        if (notBlank(r.causingPart())) {
            lines.add("Pièce en cause : " + r.causingPart().trim());
        }
        if (notBlank(r.diagnosis())) {
            lines.add(r.diagnosis().trim());
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static Map<String, Object> text(String heading, String body) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("heading", heading);
        text.put("body", body == null || body.isBlank() ? "Rien n'a été écrit pour cette partie." : body.trim());
        return text;
    }

    private List<Map<String, String>> signatures(InterventionResponse r, boolean internal) {
        List<Map<String, String>> signatures = new ArrayList<>();
        signatures.add(signature(orDash(r.technicianName()), "Le technicien — intervention réalisée"));
        signatures.add(signature(orDash(r.clientName()), "Le client — bus repris, lu et approuvé"));
        if (internal) {
            signatures.add(signature(vendorName, "Le chef d'atelier — sortie et prime validées"));
        }
        return signatures;
    }

    private static Map<String, String> signature(String name, String label) {
        Map<String, String> signature = new LinkedHashMap<>();
        signature.put("name", name);
        signature.put("label", label);
        return signature;
    }

    private String footer(boolean internal) {
        return vendorName + " — " + vendorAddress + " — document généré automatiquement le "
                + LocalDate.now().format(DATE)
                + (internal ? " — version interne, les montants sont en dinars tunisiens (DT)."
                        : " — aucun montant ne figure sur cette copie.");
    }

    // ------------------------------------------------------------------ mise en forme

    private static String statusLabel(String code) {
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

    private static String priorityLabel(String code) {
        if (code == null) {
            return DASH;
        }
        try {
            return switch (SavPriority.valueOf(code.toUpperCase())) {
                case LOW -> "basse";
                case MEDIUM -> "normale";
                case HIGH -> "haute";
                case URGENT -> "urgente";
            };
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    private static String hoursLabel(Long minutes) {
        return minutes == null ? "non calculable" : minutesLabel(minutes);
    }

    private static String minutesLabel(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return h + " h " + (m < 10 ? "0" : "") + m;
    }

    private static String km(Long value) {
        return value == null ? "non relevé" : String.valueOf(value) + " km";
    }

    private static String distance(Long value) {
        return value == null ? "non calculable (départ ou retour manquant)" : value + " km";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? DASH : value.trim();
    }

    /** Les signaux recopiés dans la ligne de prime, un par puce plutôt qu'un bloc illisible. */
    private static String orDash(List<String> anomalies) {
        if (anomalies.isEmpty()) {
            return "aucun";
        }
        return String.join(" • ", anomalies);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String date(LocalDateTime at) {
        return at == null ? DASH : at.format(TIMESTAMP);
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

    private static synchronized String money(BigDecimal value) {
        return value == null ? DASH : MONEY.format(value.setScale(3, RoundingMode.HALF_UP));
    }

    /** Heures facturables : deux décimales, le quart d'heure s'écrit 0,25. */
    private static synchronized String hours2(BigDecimal value) {
        return value == null ? DASH : HOURS.format(value.setScale(2, RoundingMode.HALF_UP));
    }
}
