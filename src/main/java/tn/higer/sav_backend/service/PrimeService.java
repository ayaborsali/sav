package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeDto;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeLine;
import tn.higer.sav_backend.dto.PrimeDtos.PrimePeriodResponse;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeTotals;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeValidationRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionEventSource;
import tn.higer.sav_backend.model.InterventionEventType;
import tn.higer.sav_backend.model.InterventionPrime;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.InterventionEventRepository;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionPrimeRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.security.Access;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La prime d'intervention — le motif du module.
 *
 * Le calcul ne part d'aucune valeur saisie : il part des horodatages posés par
 * les boutons d'étape, que le technicien ne peut pas écrire lui-même. C'est ce
 * qui répond à « un technicien qui ne fait rien et qui touche une prime » :
 * sans départ ni retour horodatés, il n'y a pas de montant.
 *
 * Règle annoncée à l'avance et affichée avec le résultat :
 *  - durée retenue = départ → retour ;
 *  - arrondie au quart d'heure le plus proche ;
 *  - plafonnée à {@code app.depannage.max-paid-hours} heures par sortie ;
 *  - montant = heures × taux horaire du technicien + forfait de sortie.
 *
 * Trois garde-fous, volontaires :
 *  1. une prime se valide après clôture, jamais avant ;
 *  2. toute anomalie trouvée (photos manquantes, heure rectifiée, durée
 *     plafonnée, taux absent) impose une justification écrite pour valider —
 *     l'application signale, un humain décide, et sa décision reste lisible ;
 *  3. le taux et le forfait sont recopiés dans la ligne validée : une
 *     augmentation ultérieure ne réécrit pas le passé, et un montant payé ne se
 *     modifie plus.
 *
 * Le montant n'est jamais envoyé à un compte qui n'a pas à le voir : la paie
 * regarde l'administrateur et le chef d'atelier ({@link Access#seesPayroll}),
 * pas le gestionnaire SAV, pas le technicien, pas le client.
 */
@Service
public class PrimeService {

    /** Angles sans lesquels la sortie n'est pas tenue pour prouvée. */
    private static final List<PhotoAngle> PROOF_ANGLES = List.of(PhotoAngle.BEFORE, PhotoAngle.AFTER);

    /** Le quart d'heure est l'unité de temps admise : ni la minute (bruit), ni l'heure (perte sèche). */
    private static final BigDecimal QUARTER = new BigDecimal("0.25");

    private final InterventionRepository interventions;
    private final InterventionPrimeRepository primes;
    private final InterventionPhotoRepository photos;
    private final InterventionEventRepository events;
    private final TechnicianRepository technicians;
    private final InterventionService visibility;
    private final AuditService audit;

    private final long maxPaidHours;

    public PrimeService(InterventionRepository interventions,
                        InterventionPrimeRepository primes,
                        InterventionPhotoRepository photos,
                        InterventionEventRepository events,
                        TechnicianRepository technicians,
                        InterventionService visibility,
                        AuditService audit,
                        @Value("${app.depannage.max-paid-hours:12}") long maxPaidHours) {
        this.interventions = interventions;
        this.primes = primes;
        this.photos = photos;
        this.events = events;
        this.technicians = technicians;
        this.visibility = visibility;
        this.audit = audit;
        this.maxPaidHours = maxPaidHours;
    }

    // ------------------------------------------------------------------ calcul

    /**
     * Prime d'une sortie. Si une ligne validée existe, c'est ELLE qui est renvoyée,
     * intacte : le chef doit voir ce qui a été payé, pas ce que le calcul donnerait
     * aujourd'hui avec un taux changé entre-temps.
     */
    @Transactional(readOnly = true)
    public PrimeDto forIntervention(String interventionId, Authentication auth, Jwt jwt) {
        requirePayroll(auth);
        Intervention i = visibility.requireVisible(interventionId, auth, jwt);
        Technician tech = technicianOf(i);
        Long real = realMinutes(i);

        return primes.findByInterventionId(i.getId())
                .map(p -> PrimeDto.saved(p, i.getNumber(), label(i.getStatus()), real,
                        tech == null ? i.getTechnicianId() : tech.getId(),
                        tech == null ? null : tech.getName(), rule()))
                .orElseGet(() -> draft(i, tech, real));
    }

    /** Une sortie sans homme n'a pas de fiche à chercher : null, pas une exception Hibernate. */
    private Technician technicianOf(Intervention i) {
        return i.getTechnicianId() == null ? null
                : technicians.findById(i.getTechnicianId()).orElse(null);
    }

    /** Le brouillon : calculé à la demande, jamais stocké (un montant non décidé n'engage personne). */
    private PrimeDto draft(Intervention i, Technician tech, Long real) {
        List<String> anomalies = new ArrayList<>();
        Long paid = null;
        BigDecimal hours = null;
        BigDecimal amount = null;

        if (real == null) {
            anomalies.add("départ ou retour non horodaté : la durée ne peut pas être calculée");
        } else {
            long ceiling = maxPaidHours * 60L;
            paid = Math.min(real, ceiling);
            hours = billableHours(paid);
            if (real > ceiling) {
                anomalies.add("durée réelle de " + hoursLabel(real) + " plafonnée à "
                        + maxPaidHours + " h payées");
            }
        }

        if (i.getStatus() != InterventionStatus.CLOSED) {
            anomalies.add("sortie non clôturée (statut « " + label(i.getStatus()) + " ») : "
                    + "la prime se valide après la clôture");
        }
        if (i.getTechnicianId() == null) {
            anomalies.add("personne n'est encore affecté à la sortie : une prime ne se calcule "
                    + "pas pour un homme inconnu");
        } else if (tech == null) {
            anomalies.add("le technicien de la sortie est introuvable au dossier");
        } else if (tech.getHourlyRate() == null) {
            anomalies.add("aucun taux horaire au dossier de " + tech.getName()
                    + " : le montant ne peut pas être calculé");
        }
        List<String> missing = missingProof(i.getId());
        if (!missing.isEmpty()) {
            anomalies.add("photos de preuve manquantes : " + String.join(", ", missing));
        }
        if (hasCorrectedHour(i.getId())) {
            anomalies.add("une heure de la sortie a été rectifiée après coup");
        }

        BigDecimal rate = tech == null ? null : tech.getHourlyRate();
        BigDecimal allowance = tech == null ? null : tech.getDispatchAllowance();
        if (rate != null && paid != null) {
            amount = hours.multiply(rate).setScale(3, RoundingMode.HALF_UP);
            if (allowance != null) {
                amount = amount.add(allowance);
            }
        }
        if (tech != null && allowance == null) {
            anomalies.add("forfait de sortie non défini au dossier de " + tech.getName()
                    + " : seul le taux horaire est retenu");
        }

        return new PrimeDto(i.getId(), i.getNumber(), label(i.getStatus()),
                tech == null ? i.getTechnicianId() : tech.getId(),
                tech == null ? null : tech.getName(),
                real, paid, hours, rate, allowance, amount, anomalies, false,
                null, null, null, rule());
    }

    /**
     * Validation : la ligne est écrite, les taux sont recopiés, le journal garde
     * la signature de qui a décidé. Après ceci, plus rien ne bouge.
     */
    @Transactional
    public PrimeDto validate(String interventionId, PrimeValidationRequest r, Jwt jwt, Authentication auth) {
        requirePayroll(auth);
        Intervention i = visibility.requireVisible(interventionId, auth, jwt);

        if (primes.findByInterventionId(i.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La prime de la sortie " + i.getNumber() + " est déjà validée : une prime payée "
                            + "ne se valide pas deux fois. Une erreur se corrige au journal de la sortie.");
        }
        if (i.getStatus() != InterventionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Clôturez d'abord la sortie " + i.getNumber() + " : une prime se valide sur un "
                            + "travail terminé et rentré, pas sur une sortie encore ouverte.");
        }

        Technician tech = technicianOf(i);
        Long real = realMinutes(i);
        PrimeDto draft = draft(i, tech, real);
        List<String> anomalies = draft.anomalies();

        if (draft.amount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pas de montant calculable pour " + i.getNumber() + " : "
                            + String.join(" ; ", anomalies.isEmpty() ? List.of("durée inconnue") : anomalies)
                            + ". Complétez la sortie et la fiche du technicien, puis validez la prime.");
        }
        String justification = trim(r == null ? null : r.justification());
        if (!anomalies.isEmpty() && justification == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le calcul signale : " + String.join(" ; ", anomalies)
                            + ". Écrivez pourquoi vous validez quand même — votre raison restera"
                            + " sur la ligne de prime et se relira au contrôle.");
        }

        LocalDateTime now = LocalDateTime.now();
        InterventionPrime p = new InterventionPrime();
        p.setId("prm-" + UUID.randomUUID());
        p.setInterventionId(i.getId());
        p.setPaidMinutes(draft.paidMinutes());
        p.setBillableHours(draft.billableHours());
        p.setHourlyRate(draft.hourlyRate());
        p.setDispatchAllowance(draft.dispatchAllowance());
        p.setAmount(draft.amount());
        p.setAnomalies(truncate(String.join(" ; ", anomalies), 500));
        p.setJustification(justification == null ? null : truncate(justification, 500));
        p.setValidatedByEmail(jwt == null ? null : jwt.getClaimAsString("email"));
        p.setValidatedAt(now);
        primes.save(p);

        visibility.event(i.getId(), InterventionEventType.PRIME, InterventionEventSource.MANAGED, jwt,
                "Prime validée : " + draft.billableHours() + " h × " + draft.hourlyRate()
                        + (draft.dispatchAllowance() != null ? " + forfait " + draft.dispatchAllowance() : "")
                        + " = " + draft.amount()
                        + (anomalies.isEmpty() ? "" : " (signaux : " + String.join(" ; ", anomalies) + ")")
                        + (justification == null ? "" : " — " + justification));
        audit.record("VALIDATE", "INTERVENTION_PRIME", p.getId(),
                "Validation de la prime de la sortie " + i.getNumber(), jwt);
        return PrimeDto.saved(p, i.getNumber(), label(i.getStatus()), real,
                tech == null ? i.getTechnicianId() : tech.getId(),
                tech == null ? null : tech.getName(), rule());
    }

    // ------------------------------------------------------------------ période

    /**
     * Les primes validées d'un mois, avec le total par technicien. {@code month}
     * au format AAAA-MM ; vide = mois courant.
     */
    @Transactional(readOnly = true)
    public PrimePeriodResponse period(String month, Authentication auth) {
        requirePayroll(auth);
        YearMonth ym = parseMonth(month);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.atEndOfMonth().atTime(23, 59, 59);

        List<InterventionPrime> rows = primes.findByValidatedAtBetweenOrderByValidatedAtAsc(from, to);
        Map<String, Intervention> sorties = indexSorties(rows);
        Map<String, Technician> team = indexTechnicians(sorties.values());

        List<PrimeLine> lines = new ArrayList<>();
        Map<String, long[]> countByTech = new LinkedHashMap<>();
        Map<String, BigDecimal[]> sumByTech = new LinkedHashMap<>();
        for (InterventionPrime p : rows) {
            Intervention i = sorties.get(p.getInterventionId());
            Technician tech = i == null ? null : team.get(i.getTechnicianId());
            lines.add(new PrimeLine(p.getId(), p.getInterventionId(),
                    i == null ? "sortie supprimée" : i.getNumber(),
                    tech == null ? (i == null ? null : i.getTechnicianId()) : tech.getId(),
                    tech == null ? null : tech.getName(),
                    p.getBillableHours(), p.getAmount(), p.getValidatedAt(),
                    p.getValidatedByEmail(), PrimeDto.split(p.getAnomalies()), p.getJustification()));

            String key = tech == null ? "—" : tech.getId();
            countByTech.computeIfAbsent(key, k -> new long[1])[0]++;
            BigDecimal[] acc = sumByTech.computeIfAbsent(key,
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            acc[0] = acc[0].add(p.getBillableHours() == null ? BigDecimal.ZERO : p.getBillableHours());
            acc[1] = acc[1].add(p.getAmount() == null ? BigDecimal.ZERO : p.getAmount());
        }

        List<PrimeTotals> totals = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : sumByTech.entrySet()) {
            Technician tech = team.get(e.getKey());
            totals.add(new PrimeTotals(e.getKey(), tech == null ? "—" : tech.getName(),
                    countByTech.get(e.getKey())[0], e.getValue()[0], e.getValue()[1]));
        }
        BigDecimal grand = primes.sumAmountBetween(from, to);
        return new PrimePeriodResponse(ym.toString(), from, to, lines, totals,
                grand == null ? BigDecimal.ZERO : grand, rule());
    }

    /** Ce que l'écran affiche avant de poser la question : comment le montant est fait. */
    public String rule() {
        return "Durée retenue : départ → retour horodatés par le serveur, arrondie au quart d'heure "
                + "le plus proche, plafonnée à " + maxPaidHours + " h par sortie. "
                + "Montant : heures × taux horaire du technicien + forfait de sortie. "
                + "Une anomalie signalée exige une justification écrite pour être validée.";
    }

    // ------------------------------------------------------------------ pièces de calcul

    /** Minutes réelles dehors, ou null si le départ ou le retour manque. */
    private static Long realMinutes(Intervention i) {
        if (i.getDepartedAt() == null || i.getReturnedAt() == null
                || i.getReturnedAt().isBefore(i.getDepartedAt())) {
            return null;
        }
        return Duration.between(i.getDepartedAt(), i.getReturnedAt()).toMinutes();
    }

    /** Heures facturables : arrondi au quart d'heure le plus proche, jamais en dessous de zéro. */
    private static BigDecimal billableHours(long minutes) {
        if (minutes <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        long quarters = Math.round(minutes / 15.0);
        return BigDecimal.valueOf(quarters).multiply(QUARTER).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> missingProof(String interventionId) {
        return PROOF_ANGLES.stream()
                .filter(a -> !photos.existsByInterventionIdAndAngle(interventionId, a))
                .map(PhotoAngle::label)
                .toList();
    }

    /** Une heure rectifiée après coup se paie, mais elle se signale : c'est la règle annoncée. */
    private boolean hasCorrectedHour(String interventionId) {
        return events.findByInterventionIdOrderByRecordedAtAsc(interventionId).stream()
                .anyMatch(e -> e.getEventType() == InterventionEventType.CORRECTED);
    }

    private Map<String, Intervention> indexSorties(List<InterventionPrime> rows) {
        List<String> ids = rows.stream().map(InterventionPrime::getInterventionId).distinct().toList();
        Map<String, Intervention> map = new LinkedHashMap<>();
        interventions.findAllById(ids).forEach(i -> map.put(i.getId(), i));
        return map;
    }

    private Map<String, Technician> indexTechnicians(Iterable<Intervention> sorties) {
        List<String> ids = new ArrayList<>();
        sorties.forEach(i -> {
            if (i.getTechnicianId() != null && !ids.contains(i.getTechnicianId())) {
                ids.add(i.getTechnicianId());
            }
        });
        Map<String, Technician> map = new LinkedHashMap<>();
        technicians.findAllById(ids).forEach(t -> map.put(t.getId(), t));
        return map;
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now());
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Période incomprise : le format attendu est AAAA-MM (exemple 2026-10).");
        }
    }

    private static void requirePayroll(Authentication auth) {
        if (!Access.seesPayroll(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Les primes sont internes : seuls l'administrateur et le chef d'atelier "
                            + "les consultent et les valident.");
        }
    }

    private static String label(InterventionStatus status) {
        return status == null ? null : status.name().toLowerCase();
    }

    private static String hoursLabel(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return h + " h " + (m < 10 ? "0" : "") + m;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
