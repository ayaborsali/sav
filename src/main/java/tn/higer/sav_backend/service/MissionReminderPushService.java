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
 * Les deux rappels du dossier qui naissent du temps qui passe : « ton départ
 * prévu est passé » et « ton retour prévu est passé ».
 *
 * Les autres notifications sont des événements (un dossier arrive, une alerte
 * se déclenche) : elles partent au moment de l'écriture. Celles-ci naissent de
 * l'heure, il faut donc un tour périodique pour aller les chercher. Un dossier
 * annulé n'a plus d'heure à tenir : il ne sonne pas.
 *
 * Trois précautions, les mêmes pour les deux rappels :
 *  - un quart d'heure de grâce : un départ prévu à 8 h ne fait pas sonner le
 *    téléphone à 8 h 01 pour un homme qui est déjà devant la voiture ;
 *  - une seule annonce par (dossier, heure prévue) : sans cela, le même fait
 *    sonnerait à chaque tour jusqu'au pointage ;
 *  - une annonce ne se note que si un téléphone l'a reçue : un technicien qui
 *    s'abonne dans l'après-midi doit encore entendre la question du matin.
 *
 * Aucune alerte ne s'écrit ici : la poussée réveille, le dossier reste la seule
 * vérité, et les repères rouges de l'écran continuent de dire la même chose.
 */
@Service
public class MissionReminderPushService {

    static final String KIND_DEPART = "DEPART";
    static final String KIND_RETOUR = "RETOUR";

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM");

    private final MissionGroupDossierRepository dossiers;
    private final PushSentRepository sent;
    private final PushNotificationService push;
    private final long graceMinutes;
    private final boolean autoScan;

    public MissionReminderPushService(MissionGroupDossierRepository dossiers,
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
        return rappelerDeparts(seuil) + rappelerRetours(seuil);
    }

    /** Départ prévu passé, départ réel non pointé : l'homme est-il parti ? */
    private int rappelerDeparts(LocalDateTime seuil) {
        int envoyees = 0;
        for (MissionGroupDossier dossier : dossiers
                .findByReturnedAtIsNullAndDepartedAtIsNullAndCancelledAtIsNullAndPlannedDepartureAtBefore(seuil)) {
            if (dossier.getTechnicianUserId() == null || dossier.getPlannedDepartureAt() == null) {
                continue;
            }
            if (annoncer(dossier, KIND_DEPART, dossier.getPlannedDepartureAt(), "Départ prévu dépassé",
                    "depart-", "départ")) {
                envoyees++;
            }
        }
        return envoyees;
    }

    /** Retour prévu passé, retour réel non pointé : l'homme est-il rentré ? */
    private int rappelerRetours(LocalDateTime seuil) {
        int envoyees = 0;
        for (MissionGroupDossier dossier : dossiers
                .findByReturnedAtIsNullAndDepartedAtIsNotNullAndCancelledAtIsNullAndPlannedReturnAtBefore(seuil)) {
            if (dossier.getTechnicianUserId() == null || dossier.getPlannedReturnAt() == null) {
                continue;
            }
            if (annoncer(dossier, KIND_RETOUR, dossier.getPlannedReturnAt(), "Retour prévu dépassé",
                    "retour-", "retour")) {
                envoyees++;
            }
        }
        return envoyees;
    }

    /**
     * Une annonce : la même pour le départ et le retour. La référence est
     * l'heure prévue, à la seconde près, pour qu'un horaire déplacé par
     * l'atelier soit une annonce neuve et le même horaire un silence.
     */
    private boolean annoncer(MissionGroupDossier dossier, String kind, LocalDateTime heurePrevue,
                             String titre, String tagPrefixe, String quoi) {
        String reference = heurePrevue.withNano(0).toString();
        if (sent.existsByKindAndTargetIdAndReference(kind, dossier.getId(), reference)) {
            return false;
        }
        boolean recue = push.deliver(dossier.getTechnicianUserId(),
                new PushNotificationService.Message(
                        titre,
                        "Dossier " + dossier.getNumber() + " — " + dossier.getClientName()
                                + " : " + quoi + " prévu " + quand(heurePrevue)
                                + ", le " + quoi + " réel n'est pas pointé.",
                        "/?dossier=" + dossier.getId(),
                        tagPrefixe + dossier.getId()));
        if (!recue) {
            return false;
        }
        PushSent trace = new PushSent();
        trace.setId("pst-" + UUID.randomUUID());
        trace.setKind(kind);
        trace.setTargetId(dossier.getId());
        trace.setReference(reference);
        trace.setSentAt(LocalDateTime.now());
        sent.save(trace);
        return true;
    }

    private static String quand(LocalDateTime moment) {
        if (moment.toLocalDate().equals(LocalDate.now())) {
            return "aujourd'hui à " + moment.format(HEURE);
        }
        return "le " + moment.format(DATE) + " à " + moment.format(HEURE);
    }
}
