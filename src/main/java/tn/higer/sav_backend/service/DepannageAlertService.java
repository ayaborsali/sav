package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.AlertDtos.AlertDto;
import tn.higer.sav_backend.dto.AlertDtos.AlertListResponse;
import tn.higer.sav_backend.dto.AlertDtos.WaiveRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionAlert;
import tn.higer.sav_backend.model.InterventionAlertKind;
import tn.higer.sav_backend.model.InterventionEventSource;
import tn.higer.sav_backend.model.InterventionEventType;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.InterventionAlertRepository;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.security.Access;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Les alertes de dépannage — niveau 3 du contrôle : ce que le temps signale.
 *
 * Les deux premiers niveaux répondent à « qu'est-ce qui s'est passé » (la sortie,
 * les horodatages) et « qu'est-ce que ça coûte » (la prime). Celui-ci répond à
 * « qu'est-ce qui attend encore » : une sortie dehors depuis neuf heures, un
 * cliché jamais versé, un bus réparé qui n'est pas rentré. C'est là que se voit
 * un technicien qui encaisse une prime pour une sortie à moitié faite.
 *
 * Trois règles, prises pour le même but que le reste du module :
 *  1. les seuils sont annoncés à l'avance dans la configuration et récités à
 *     l'écran avec chaque liste — une règle cachée se discute, une règle écrite
 *     se contrôle ;
 *  2. une alerte n'est pas une faute : elle pose une question. C'est un humain qui
 *     y répond, et sa réponse s'écrit ({@link #waive}) ;
 *  3. une situation qui se règle ferme elle-même son alerte : le scan résout ce
 *     qui n'est plus vrai, sinon la liste devient du bruit et plus personne ne la
 *     lit.
 *
 * Le technicien ne lève rien : il ne lit que les signaux de ses propres sorties,
 * comme pour le reste du module (la propriété est vérifiée par
 * {@link InterventionService#requireVisible}).
 */
@Service
public class DepannageAlertService {

    /** Tout ce qui n'est pas clos : une sortie clôturée a déjà rendu ses comptes. */
    private static final List<InterventionStatus> OPEN_STATUSES = List.of(
            InterventionStatus.REPORTED, InterventionStatus.DISPATCHED, InterventionStatus.ON_SITE,
            InterventionStatus.IN_PROGRESS, InterventionStatus.REPAIRED, InterventionStatus.TOWED,
            InterventionStatus.RETURNED);

    private final InterventionRepository interventions;
    private final InterventionAlertRepository alerts;
    private final InterventionPhotoRepository photos;
    private final TechnicianRepository technicians;
    private final InterventionService visibility;
    private final AuditService audit;

    /** Sortie dehors au-delà de cette durée : le plafond déjà utilisé par le tableau du jour. */
    private final long longRunningHours;
    /** Minutes accordées pour verser un cliché après le départ (ou après la fin). */
    private final long photoGraceMinutes;
    /** Heures accordées pour rentrer à l'atelier après la réparation. */
    private final long returnHours;
    /** Minutes accordées pour affecter une voiture de déplacement après l'appel. */
    private final long dispatchGraceMinutes;
    /** Le tour automatique, coupé par une propriété : rien ne se déclenche tout seul s'il est fermé. */
    private final boolean autoScan;

    public DepannageAlertService(InterventionRepository interventions,
                                 InterventionAlertRepository alerts,
                                 InterventionPhotoRepository photos,
                                 TechnicianRepository technicians,
                                 InterventionService visibility,
                                 AuditService audit,
                                 @Value("${app.depannage.long-running-hours:8}") long longRunningHours,
                                 @Value("${app.depannage.alert-photo-grace-minutes:60}") long photoGraceMinutes,
                                 @Value("${app.depannage.alert-return-hours:2}") long returnHours,
                                 @Value("${app.depannage.alert-dispatch-minutes:45}") long dispatchGraceMinutes,
                                 @Value("${app.depannage.alert-scan-enabled:false}") boolean autoScan) {
        this.interventions = interventions;
        this.alerts = alerts;
        this.photos = photos;
        this.technicians = technicians;
        this.visibility = visibility;
        this.audit = audit;
        this.longRunningHours = longRunningHours;
        this.photoGraceMinutes = photoGraceMinutes;
        this.returnHours = returnHours;
        this.dispatchGraceMinutes = dispatchGraceMinutes;
        this.autoScan = autoScan;
    }

    // ------------------------------------------------------------------ le scan

    /**
     * Passe périodique : ouvre les alertes dont la règle se vérifie, ferme celles
     * qui ne se vérifient plus. Idempotent — deux passes de suite ne créent
     * qu'une ligne par (sortie, règle).
     *
     * Le journal de la sortie ne gonfle pas ici : une alerte est un signal de
     * lecture, pas un acte de gestion. Seule la levée s'y écrit.
     */
    @Scheduled(fixedDelayString = "${app.depannage.alert-scan-minutes:15}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void scheduledScan() {
        // La coupure se décide ici et non sur @EnableScheduling : le scheduler est
        // partagé avec d'autres règles (le rappel des paliers), et couper l'une ne
        // doit pas réveiller l'autre.
        if (!autoScan) {
            return;
        }
        scanOnce();
    }

    /**
     * La même passe, déclenchée depuis l'écran (« Vérifier maintenant ») : personne
     * n'a à attendre le tour du scheduler pour savoir où en est la journée.
     *
     * @return le nombre d'alertes ouvertes par cette passe
     */
    @Transactional
    public int scan() {
        return scanOnce();
    }

    private int scanOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<Intervention> open = interventions.findByStatusInOrderByReportedAtDesc(OPEN_STATUSES);
        int created = 0;

        for (Intervention i : open) {
            Map<InterventionAlertKind, String> fired = firedRules(i, now);

            for (Map.Entry<InterventionAlertKind, String> e : fired.entrySet()) {
                if (!alerts.existsByInterventionIdAndKindAndResolvedFalse(i.getId(), e.getKey())) {
                    alerts.save(newAlert(i.getId(), e.getKey(), e.getValue(), now));
                    created++;
                }
            }
            // Ce qui n'est plus vrai se ferme tout seul : la preuve est arrivée,
            // la voiture est rentrée. Personne n'a eu à décider, donc rien à écrire.
            for (InterventionAlert a : alerts.findByInterventionIdAndResolvedFalse(i.getId())) {
                if (!fired.containsKey(a.getKind())) {
                    resolve(a, now);
                }
            }
        }

        // Une sortie clôturée a déjà répondu de ses preuves manquantes (raison
        // exigée à la clôture) et de sa prime : ses alertes ouvertes se ferment.
        Set<String> stillOpen = new LinkedHashSet<>();
        open.forEach(i -> stillOpen.add(i.getId()));
        for (InterventionAlert a : alerts.findByResolvedFalseOrderByDetectedAtAsc()) {
            if (!a.isResolved() && !stillOpen.contains(a.getInterventionId())) {
                resolve(a, now);
            }
        }
        return created;
    }

    /**
     * Les règles qui se vérifient SUR CETTE SORTIE, à l'heure `now`, avec le
     * message qui sera stocké. Une seule source de vérité pour le scan : ouvrir
     * et résoudre utilisent la même question, sinon une alerte pourrait ne jamais
     * se fermer.
     */
    private Map<InterventionAlertKind, String> firedRules(Intervention i, LocalDateTime now) {
        Map<InterventionAlertKind, String> fired = new LinkedHashMap<>();

        if (i.getDispatchedAt() == null && elapsed(i.getReportedAt(), now) > dispatchGraceMinutes) {
            fired.put(InterventionAlertKind.NOT_DISPATCHED,
                    "Appel reçu à " + at(i.getReportedAt()) + ", " + hoursLabel(elapsed(i.getReportedAt(), now))
                            + " plus tard : aucune voiture de déplacement affectée (seuil annoncé : "
                            + dispatchGraceMinutes + " min).");
        }

        if (i.getDepartedAt() != null
                && elapsed(i.getDepartedAt(), now) > photoGraceMinutes
                && !photos.existsByInterventionIdAndAngle(i.getId(), PhotoAngle.BEFORE)) {
            fired.put(InterventionAlertKind.MISSING_BEFORE_PHOTO,
                    "Départ horodaté à " + at(i.getDepartedAt()) + ", " + hoursLabel(elapsed(i.getDepartedAt(), now))
                            + " plus tard : toujours aucun cliché « avant » (seuil annoncé : "
                            + photoGraceMinutes + " min).");
        }

        LocalDateTime sinceEnd = i.getFinishedAt() != null ? i.getFinishedAt() : i.getReturnedAt();
        if (sinceEnd != null
                && elapsed(sinceEnd, now) > photoGraceMinutes
                && !photos.existsByInterventionIdAndAngle(i.getId(), PhotoAngle.AFTER)) {
            fired.put(InterventionAlertKind.MISSING_AFTER_PHOTO,
                    "Travail terminé à " + at(sinceEnd) + ", " + hoursLabel(elapsed(sinceEnd, now))
                            + " plus tard : toujours aucun cliché « après » (seuil annoncé : "
                            + photoGraceMinutes + " min).");
        }

        if (i.getDepartedAt() != null && i.getReturnedAt() == null
                && elapsed(i.getDepartedAt(), now) > longRunningHours * 60) {
            fired.put(InterventionAlertKind.STILL_OUT,
                    "Partie à " + at(i.getDepartedAt()) + ", dehors depuis "
                            + hoursLabel(elapsed(i.getDepartedAt(), now)) + " et toujours pas rentrée"
                            + " (seuil annoncé : " + longRunningHours + " h).");
        }

        boolean finishedOnSite = i.getStatus() == InterventionStatus.REPAIRED || i.getStatus() == InterventionStatus.TOWED;
        if (finishedOnSite && i.getReturnedAt() == null && i.getFinishedAt() != null
                && elapsed(i.getFinishedAt(), now) > returnHours * 60) {
            fired.put(InterventionAlertKind.NOT_RETURNED,
                    "Terminé à " + at(i.getFinishedAt()) + ", " + hoursLabel(elapsed(i.getFinishedAt(), now))
                            + " plus tard : le bus n'est pas rentré à l'atelier (seuil annoncé : "
                            + returnHours + " h).");
        }
        return fired;
    }

    private static InterventionAlert newAlert(String interventionId, InterventionAlertKind kind,
                                             String message, LocalDateTime now) {
        InterventionAlert a = new InterventionAlert();
        a.setId("alr-" + UUID.randomUUID());
        a.setInterventionId(interventionId);
        a.setKind(kind);
        a.setMessage(truncate(message, 500));
        a.setDetectedAt(now);
        a.setResolved(false);
        a.setWaived(false);
        return a;
    }

    private void resolve(InterventionAlert a, LocalDateTime now) {
        a.setResolved(true);
        a.setResolvedAt(now);
        alerts.save(a);
    }

    // ------------------------------------------------------------------ lecture

    /**
     * Les alertes encore ouvertes, telles que le poste de l'atelier les lit.
     * Un technicien ne voit que celles de ses sorties — et n'a aucun bouton pour
     * les lever : ce n'est pas lui qui décide si sa journée est tenue pour correcte.
     */
    @Transactional(readOnly = true)
    public AlertListResponse open(Authentication auth, Jwt jwt) {
        requireMember(auth);
        String own = visibility.ownTechnicianId(auth, jwt);
        List<InterventionAlert> rows = alerts.findByResolvedFalseOrderByDetectedAtAsc();

        Map<String, Intervention> sorties = indexSorties(rows);
        Map<String, Technician> team = indexTechnicians(sorties.values());

        List<AlertDto> dtos = new ArrayList<>();
        for (InterventionAlert a : rows) {
            Intervention i = sorties.get(a.getInterventionId());
            if (i == null) {
                // Sortie supprimée : la question ne se pose plus.
                continue;
            }
            if (own != null && !own.equals(i.getTechnicianId())) {
                continue;
            }
            Technician tech = team.get(i.getTechnicianId());
            dtos.add(new AlertDto(a.getId(), i.getId(), i.getNumber(),
                    tech == null ? null : tech.getName(), a.getKind().name(), a.getKind().label(),
                    a.getMessage(), a.getDetectedAt(), false, null, null, null));
        }
        return new AlertListResponse(dtos, dtos.size(), rules(), LocalDateTime.now());
    }

    /** Les seuils appliqués, en français, affichés au-dessus de la liste. */
    public String rules() {
        return "Seuils appliqués : voiture de déplacement affectée au-delà de " + dispatchGraceMinutes
                + " min après l'appel ; cliché « avant » attendu " + photoGraceMinutes
                + " min après le départ ; cliché « après » attendu " + photoGraceMinutes
                + " min après la fin du travail ; sortie signalée dehors plus de " + longRunningHours
                + " h ; bus non rentré signalé " + returnHours + " h après la réparation. "
                + "Une alerte ne se lève que par une raison écrite ; ce qui se règle tout seul se ferme.";
    }

    // ------------------------------------------------------------------ la décision

    /**
     * Lever une alerte : la seule écriture d'un humain sur une alerte. Rien n'est
     * effacé — la ligne garde le signal, la raison, son auteur et l'heure, parce
     * que c'est précisément ce croisement qui se relit au contrôle.
     */
    @Transactional
    public AlertDto waive(String alertId, WaiveRequest r, Jwt jwt, Authentication auth) {
        InterventionAlert a = alerts.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable."));
        Intervention i = visibility.requireVisible(a.getInterventionId(), auth, jwt);

        if (!Access.managesInterventions(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seul le chef d'atelier (ou l'administrateur) lève une alerte. Le technicien "
                            + "signale de son côté, il ne se donne pas raison à lui-même.");
        }
        if (a.isResolved()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette alerte est déjà fermée" + (a.getWaivedAt() != null
                            ? " : levée par " + a.getWaivedByEmail() + " le " + at(a.getWaivedAt())
                            : a.getResolvedAt() != null ? " le " + at(a.getResolvedAt()) : "")
                            + ". Une décision se prend une fois ; une seconde s'écrirait au journal de la sortie.");
        }
        String reason = trim(r == null ? null : r.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une alerte ne se lève pas sans écrit : dites pourquoi la sortie est tenue pour "
                            + "correcte quand même. Votre raison restera sur la ligne et se relira au contrôle.");
        }

        LocalDateTime now = LocalDateTime.now();
        a.setResolved(true);
        a.setResolvedAt(now);
        a.setWaived(true);
        a.setWaiverReason(truncate(reason, 500));
        a.setWaivedByEmail(jwt == null ? null : jwt.getClaimAsString("email"));
        a.setWaivedAt(now);
        alerts.save(a);

        visibility.event(i.getId(), InterventionEventType.ALERT, InterventionEventSource.MANAGED, jwt,
                "Alerte levée (« " + a.getKind().label() + " », signalée le " + at(a.getDetectedAt())
                        + ") : " + reason);
        audit.record("WAIVE", "INTERVENTION_ALERT", a.getId(),
                "Lever de l'alerte " + a.getKind().name() + " sur la sortie " + i.getNumber(), jwt);

        Technician tech = i.getTechnicianId() == null ? null
                : technicians.findById(i.getTechnicianId()).orElse(null);
        return new AlertDto(a.getId(), i.getId(), i.getNumber(), tech == null ? null : tech.getName(),
                a.getKind().name(), a.getKind().label(), a.getMessage(), a.getDetectedAt(),
                true, a.getWaiverReason(), a.getWaivedByEmail(), a.getWaivedAt());
    }

    // ------------------------------------------------------------------ pièces communes

    private Map<String, Intervention> indexSorties(List<InterventionAlert> rows) {
        List<String> ids = rows.stream().map(InterventionAlert::getInterventionId).distinct().toList();
        Map<String, Intervention> map = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            interventions.findAllById(ids).forEach(i -> map.put(i.getId(), i));
        }
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

    /** Minutes écoulées depuis `from` (0 si l'heure manque ou si l'heure du serveur est avant). */
    private static long elapsed(LocalDateTime from, LocalDateTime now) {
        if (from == null || from.isAfter(now)) {
            return 0;
        }
        return Duration.between(from, now).toMinutes();
    }

    private static String hoursLabel(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? h + " h " + (m < 10 ? "0" : "") + m : m + " min";
    }

    private static String at(LocalDateTime v) {
        return v == null ? "—" : v.toLocalDate() + " " + String.format("%02d:%02d", v.getHour(), v.getMinute());
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** Le module Dépannage est interne, y compris en lecture d'alertes. */
    private static void requireMember(Authentication auth) {
        if (!Access.usesDepannageModule(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Le module dépannage est interne : votre compte n'y a pas accès.");
        }
    }
}
