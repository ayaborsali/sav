package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le troisième niveau du contrôle : ce que LE TEMPS signale.
 *
 * Ce fichier vérifie les quatre comportements qui font qu'une liste d'alertes est
 * utilisable plutôt que bruyante :
 *  - une règle annoncée se déclenche sur les heures du serveur, pas sur un récit ;
 *  - la même question n'est pas reposée vingt fois par heure ;
 *  - ce qui se règle tout seul ferme son alerte (sinon la liste devient du bruit) ;
 *  - une alerte ne se lève QUE par une raison écrite, et cette raison reste.
 *
 * Les seuils sont fixés dans chaque test comme ils le seraient en production
 * (8 h dehors, 60 min de délai de preuve, 2 h de retour, 45 min d'affectation) :
 * changer un seuil doit faire rougir un test, pas passer inaperçu.
 */
@ExtendWith(MockitoExtension.class)
class DepannageAlertServiceTest {

    @Mock private InterventionRepository interventions;
    @Mock private InterventionAlertRepository alerts;
    @Mock private InterventionPhotoRepository photos;
    @Mock private TechnicianRepository technicians;
    @Mock private InterventionService visibility;
    @Mock private AuditService audit;

    private static final String ITV = "itv-1";
    private static final String TECH_ID = "tch-1";
    private static final String ALERT_ID = "alr-1";

    /** 8 h dehors, 60 min de délai de cliché, 2 h de retour, 45 min d'affectation, tour automatique coupé. */
    private DepannageAlertService service() {
        return new DepannageAlertService(interventions, alerts, photos, technicians, visibility, audit,
                8, 60, 2, 45, false);
    }

    /** Idem, tour automatique ouvert : ce que laisse la propriété en production. */
    private DepannageAlertService serviceAuto() {
        return new DepannageAlertService(interventions, alerts, photos, technicians, visibility, audit,
                8, 60, 2, 45, true);
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication auth(String role) {
        // Les autorités côté serveur sont ROLE_ADMIN / ROLE_CHEF_ATELIER… en majuscules.
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(java.util.Locale.ROOT))));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "chef@saphir.tn")
                .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(3600)).build();
    }

    /**
     * Sortie dehors depuis 10 heures, arrivée sur place, rien de versé comme preuve
     * et le travail pas terminé. Deux règles se vérifient sur ce dossier : le cliché
     * « avant » en retard et la durée dehors dépassée.
     */
    private static Intervention dehors() {
        Intervention i = new Intervention();
        i.setId(ITV);
        i.setNumber("DP-2026-007");
        i.setStatus(InterventionStatus.ON_SITE);
        i.setTechnicianId(TECH_ID);
        i.setVehicleId("veh-1");
        i.setClientId("cli-1");
        LocalDateTime now = LocalDateTime.now();
        i.setReportedAt(now.minusHours(11));
        i.setDispatchedAt(now.minusHours(10).minusMinutes(30));
        i.setDepartedAt(now.minusHours(10));
        i.setArrivedAt(now.minusHours(9).minusMinutes(30));
        i.setCreatedAt(now.minusHours(11));
        return i;
    }

    private static InterventionAlert openAlert(String id, InterventionAlertKind kind) {
        InterventionAlert a = new InterventionAlert();
        a.setId(id);
        a.setInterventionId(ITV);
        a.setKind(kind);
        a.setMessage("signalé");
        a.setDetectedAt(LocalDateTime.now().minusHours(3));
        a.setResolved(false);
        a.setWaived(false);
        return a;
    }

    private static Technician team() {
        Technician t = new Technician();
        t.setId(TECH_ID);
        t.setName("Ali Trabelsi");
        t.setActive(true);
        t.setHourlyRate(new BigDecimal("20.000"));
        t.setDispatchAllowance(new BigDecimal("15.000"));
        return t;
    }

    // ------------------------------------------------------------------ le scan

    @Test
    void uneHeureQuiDepasseLeSeuilOuvreUneAlerteParRegle() {
        when(interventions.findByStatusInOrderByReportedAtDesc(anyList())).thenReturn(List.of(dehors()));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(false);
        when(alerts.existsByInterventionIdAndKindAndResolvedFalse(ITV, InterventionAlertKind.MISSING_BEFORE_PHOTO))
                .thenReturn(false);
        when(alerts.existsByInterventionIdAndKindAndResolvedFalse(ITV, InterventionAlertKind.STILL_OUT))
                .thenReturn(false);
        when(alerts.findByInterventionIdAndResolvedFalse(ITV)).thenReturn(List.of());
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of());

        int opened = service().scan();

        assertEquals(2, opened);
        ArgumentCaptor<InterventionAlert> saved = ArgumentCaptor.forClass(InterventionAlert.class);
        verify(alerts, times(2)).save(saved.capture());
        List<InterventionAlert> rows = saved.getAllValues();

        InterventionAlert stillOut = rows.stream()
                .filter(a -> a.getKind() == InterventionAlertKind.STILL_OUT).findFirst().orElseThrow();
        // Le message porte les chiffres évalués : l'atelier lit ce qui a déclenché,
        // pas une étiquette générale.
        assertTrue(stillOut.getMessage().contains("10 h 00"), stillOut.getMessage());
        assertTrue(stillOut.getMessage().contains("8 h"), stillOut.getMessage());
        assertFalse(stillOut.isResolved());
        assertFalse(stillOut.isWaived());
        assertNotNull(stillOut.getDetectedAt());
        assertTrue(stillOut.getId().startsWith("alr-"));
    }

    @Test
    void laMemeQuestionNEstPasReposeeDeuxFois() {
        when(interventions.findByStatusInOrderByReportedAtDesc(anyList())).thenReturn(List.of(dehors()));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(false);
        when(alerts.existsByInterventionIdAndKindAndResolvedFalse(eq(ITV), any())).thenReturn(true);
        when(alerts.findByInterventionIdAndResolvedFalse(ITV)).thenReturn(List.of());
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of());

        assertEquals(0, service().scan());
        verify(alerts, never()).save(any(InterventionAlert.class));
    }

    @Test
    void laPreuveArriveeFermeLAlerteSansDecision() {
        Intervention sortie = dehors();
        when(interventions.findByStatusInOrderByReportedAtDesc(anyList())).thenReturn(List.of(sortie));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(true);
        when(alerts.existsByInterventionIdAndKindAndResolvedFalse(ITV, InterventionAlertKind.STILL_OUT))
                .thenReturn(true);
        InterventionAlert before = openAlert("alr-before", InterventionAlertKind.MISSING_BEFORE_PHOTO);
        InterventionAlert out = openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT);
        when(alerts.findByInterventionIdAndResolvedFalse(ITV)).thenReturn(List.of(before, out));
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of(before, out));

        service().scan();

        assertTrue(before.isResolved(), "le cliché est arrivé : le signal n'a plus de raison d'être");
        assertNotNull(before.getResolvedAt());
        // Résolue toute seule ne veut pas dire levée par quelqu'un : personne n'a décidé.
        assertFalse(before.isWaived());
        assertFalse(out.isResolved(), "la sortie est toujours dehors : la durée reste signalée");
    }

    @Test
    void uneSortieClotureeNaccumulePasSesSignaux() {
        // La clôture a déjà exigé la raison écrite des preuves manquantes : les
        // alertes ouvertes de cette sortie se ferment, elles ne restent pas là.
        when(interventions.findByStatusInOrderByReportedAtDesc(anyList())).thenReturn(List.of());
        InterventionAlert stale = openAlert(ALERT_ID, InterventionAlertKind.MISSING_AFTER_PHOTO);
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of(stale));

        service().scan();

        assertTrue(stale.isResolved());
        assertFalse(stale.isWaived());
        verify(alerts).save(stale);
    }

    @Test
    void leSeuilDAffectationSeDeclencheAussi() {
        Intervention attente = dehors();
        attente.setDispatchedAt(null);
        attente.setDepartedAt(null);
        attente.setArrivedAt(null);
        attente.setStatus(InterventionStatus.REPORTED);
        when(interventions.findByStatusInOrderByReportedAtDesc(anyList())).thenReturn(List.of(attente));
        when(alerts.existsByInterventionIdAndKindAndResolvedFalse(ITV, InterventionAlertKind.NOT_DISPATCHED))
                .thenReturn(false);
        when(alerts.findByInterventionIdAndResolvedFalse(ITV)).thenReturn(List.of());
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of());

        assertEquals(1, service().scan());
        ArgumentCaptor<InterventionAlert> saved = ArgumentCaptor.forClass(InterventionAlert.class);
        verify(alerts).save(saved.capture());
        assertEquals(InterventionAlertKind.NOT_DISPATCHED, saved.getValue().getKind());
        assertTrue(saved.getValue().getMessage().contains("45 min"), saved.getValue().getMessage());
    }

    // ------------------------------------------------------------------ la décision

    @Test
    void uneAlerteNeSeLevePasSansRaisonEcrite() {
        Intervention i = dehors();
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT)));
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().waive(ALERT_ID, new WaiveRequest("   "), jwt(), auth("chef_atelier")));

        assertEquals(HttpStatus.BAD_REQUEST, err.getStatusCode());
        assertTrue(err.getReason().contains("sans écrit"), err.getReason());
        verify(alerts, never()).save(any(InterventionAlert.class));
    }

    @Test
    void leverUneAlerteECritLaDecisionAuJournal() {
        Intervention i = dehors();
        InterventionAlert a = openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT);
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(a));
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(team()));

        AlertDto dto = service().waive(ALERT_ID,
                new WaiveRequest("pneu crevé en revenant, l'équipe est rentrée à 22 h"), jwt(), auth("chef_atelier"));

        assertTrue(dto.waived());
        assertTrue(a.isResolved());
        assertTrue(a.isWaived());
        assertEquals("chef@saphir.tn", a.getWaivedByEmail());
        assertNotNull(a.getWaivedAt());
        assertEquals("pneu crevé en revenant, l'équipe est rentrée à 22 h", a.getWaiverReason());

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(visibility).event(eq(ITV), eq(InterventionEventType.ALERT), eq(InterventionEventSource.MANAGED),
                any(), line.capture());
        assertTrue(line.getValue().contains("pneu crevé"), line.getValue());
        assertTrue(line.getValue().contains("sortie dehors depuis trop longtemps"), line.getValue());

        verify(audit).record(eq("WAIVE"), eq("INTERVENTION_ALERT"), eq(ALERT_ID), any(), any());
    }

    @Test
    void leTechnicienNeSeDonnePasRaisonALuiMeme() {
        Intervention i = dehors();
        i.setTechnicianId(TECH_ID);
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT)));
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().waive(ALERT_ID, new WaiveRequest("tout est bon"), jwt(), auth("technicien")));

        assertEquals(HttpStatus.FORBIDDEN, err.getStatusCode());
        assertTrue(err.getReason().contains("chef d'atelier"), err.getReason());
        verify(alerts, never()).save(any(InterventionAlert.class));
    }

    @Test
    void uneAlerteDejaFermeeNeSeLevePasUneSecondeFois() {
        Intervention i = dehors();
        InterventionAlert a = openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT);
        a.setResolved(true);
        a.setResolvedAt(LocalDateTime.now().minusHours(1));
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(a));
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().waive(ALERT_ID, new WaiveRequest("encore"), jwt(), auth("admin")));

        assertEquals(HttpStatus.BAD_REQUEST, err.getStatusCode());
        assertTrue(err.getReason().contains("déjà fermée"), err.getReason());
    }

    // ------------------------------------------------------------------ la lecture

    @Test
    void leChefDAtelierLitTousLesSignauxAvecLaSortieEtLEquipe() {
        InterventionAlert a = openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT);
        a.setMessage("dehors depuis 10 h 00");
        when(visibility.ownTechnicianId(any(), any())).thenReturn(null);
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of(a));
        when(interventions.findAllById(List.of(ITV))).thenReturn(List.of(dehors()));
        when(technicians.findAllById(List.of(TECH_ID))).thenReturn(List.of(team()));

        AlertListResponse res = service().open(auth("chef_atelier"), jwt());

        assertEquals(1, res.openCount());
        AlertDto row = res.alerts().get(0);
        assertEquals("DP-2026-007", row.interventionNumber());
        assertEquals("Ali Trabelsi", row.technicianName());
        assertEquals("sortie dehors depuis trop longtemps", row.kindLabel());
        assertEquals("dehors depuis 10 h 00", row.message());
        assertFalse(row.waived());
    }

    @Test
    void leTechnicienNeLitQueSesPropresSignaux() {
        when(visibility.ownTechnicianId(any(), any())).thenReturn("tch-2");
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc())
                .thenReturn(List.of(openAlert(ALERT_ID, InterventionAlertKind.STILL_OUT)));
        when(interventions.findAllById(List.of(ITV))).thenReturn(List.of(dehors()));
        when(technicians.findAllById(List.of(TECH_ID))).thenReturn(List.of(team()));

        AlertListResponse res = service().open(auth("technicien"), jwt());

        assertEquals(0, res.openCount());
        assertTrue(res.alerts().isEmpty());
    }

    @Test
    void laConsultationNEntrePasMemeEnLecture() {
        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().open(auth("consultation"), jwt()));

        assertEquals(HttpStatus.FORBIDDEN, err.getStatusCode());
        assertTrue(err.getReason().contains("interne"), err.getReason());
    }

    @Test
    void lesSeuilsSontAnoncesAvantDePoserLaQuestion() {
        String rules = service().rules();

        assertTrue(rules.contains("45 min"), rules);
        assertTrue(rules.contains("60 min"), rules);
        assertTrue(rules.contains("8 h"), rules);
        assertTrue(rules.contains("2 h"), rules);
        assertTrue(rules.contains("raison écrite"), rules);
    }

    /**
     * La coupure du tour automatique se décide dans la règle, plus dans le
     * scheduler global. Depuis le rappel des paliers, le scheduler est ouvert pour
     * tout le monde : si la coupure était encore portée par {@code @EnableScheduling},
     * activer le rappel des forfaits relancerait ces scans-ci sans que l'atelier
     * l'ait redemandé.
     */
    @Test
    void leTourAutomatiqueCoupNeLitRienEtLeTourOuvertScanne() {
        service().scheduledScan();
        verifyNoInteractions(interventions, alerts, photos);

        serviceAuto().scheduledScan();
        verify(interventions).findByStatusInOrderByReportedAtDesc(anyList());
    }
}
