package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.model.PushSent;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.repository.PushSentRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * « Ton départ prévu est passé » — la seule annonce régulière de la poussée.
 *
 * Les deux autres notifications sont des événements (un dossier arrive, une
 * alerte se déclenche) : elles partent au moment de l'écriture. Celle-ci naît
 * du temps qui passe, il faut donc un tour périodique pour aller la chercher.
 *
 * Trois précautions :
 *  - un quart d'heure de grâce : un départ prévu à 8 h ne fait pas sonner le
 *    téléphone à 8 h 01 pour un homme qui est déjà devant la voiture ;
 *  - une seule annonce par (dossier, heure de départ prévue) : sans cela, le
 *    même fait sonnerait à chaque tour jusqu'au pointage ;
 *  - une annonce ne se note que si un téléphone l'a reçue : un technicien qui
 *    s'abonne dans l'après-midi doit encore entendre la question du matin.
 *
 * Aucune alerte ne s'écrit ici : la poussée réveille, le dossier reste la seule
 * vérité, et le repère rouge de l'écran continue de dire la même chose.
 */
@Service
public class DepartureLatePushService {

    static final String KIND = "DEPART";

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM");

    private final MissionGroupDossierRepository dossiers;
    private final PushSentRepository sent;
    private final PushNotificationService push;
    private final long graceMinutes;
    private final boolean autoScan;

    public DepartureLatePushService(MissionGroupDossierRepository dossiers,
                                    PushSentRepository sent,
                                    PushNotificationService push,
                                    @Value("${app.push.departure-grace-minutes:15}") long graceMinutes,
                                    @Value("${app.push.departure-scan-enabled:true}") boolean autoScan) {
        this.dossiers = dossiers;
        this.sent = sent;
        this.push = push;
        this.graceMinutes = graceMinutes;
        this.autoScan = autoScan;
    }

    @Scheduled(fixedDelayString = "${app.push.departure-scan-minutes:10}", timeUnit = TimeUnit.MINUTES)
    public void scheduledScan() {
        if (!autoScan) {
            return;
        }
        scan();
    }

    /**
     * La passe elle-même, sans transaction qui tiendrait une connexion pendant
     * les appels réseau : chaque lecture et chaque écriture se fait pour soi.
     *
     * @return le nombre d'annonces réellement reçues par un téléphone
     */
    public int scan() {
        LocalDateTime seuil = LocalDateTime.now().minusMinutes(graceMinutes);
        int envoyees = 0;
        for (MissionGroupDossier dossier : dossiers
                .findByReturnedAtIsNullAndDepartedAtIsNullAndPlannedDepartureAtBefore(seuil)) {
            if (dossier.getTechnicianUserId() == null || dossier.getPlannedDepartureAt() == null) {
                continue;
            }
            String reference = dossier.getPlannedDepartureAt().withNano(0).toString();
            if (sent.existsByKindAndTargetIdAndReference(KIND, dossier.getId(), reference)) {
                continue;
            }
            boolean recue = push.deliver(dossier.getTechnicianUserId(),
                    new PushNotificationService.Message(
                            "Départ prévu dépassé",
                            "Dossier " + dossier.getNumber() + " — " + dossier.getClientName()
                                    + " : départ prévu " + quand(dossier.getPlannedDepartureAt())
                                    + ", le départ réel n'est pas pointé.",
                            "/?dossier=" + dossier.getId(),
                            "depart-" + dossier.getId()));
            if (recue) {
                PushSent trace = new PushSent();
                trace.setId("pst-" + UUID.randomUUID());
                trace.setKind(KIND);
                trace.setTargetId(dossier.getId());
                trace.setReference(reference);
                trace.setSentAt(LocalDateTime.now());
                sent.save(trace);
                envoyees++;
            }
        }
        return envoyees;
    }

    private static String quand(LocalDateTime moment) {
        if (moment.toLocalDate().equals(LocalDate.now())) {
            return "aujourd'hui à " + moment.format(HEURE);
        }
        return "le " + moment.format(DATE) + " à " + moment.format(HEURE);
    }
}
