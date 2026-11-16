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
import tn.higer.sav_backend.dto.PrimeDtos.PrimeDto;
import tn.higer.sav_backend.dto.PrimeDtos.PrimePeriodResponse;
import tn.higer.sav_backend.dto.PrimeDtos.PrimeValidationRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionEvent;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le test du motif réel du module : une prime se calcule sur des heures que le
 * technicien n'a pas écrites, elle ne se valide qu'après clôture, et un signal
 * trouvé par le serveur impose une décision écrite de l'humain.
 *
 * Ces règles sont vérifiées ici plutôt qu'à l'écran parce qu'un écran peut être
 * contourné : la requête, elle, passe par ce service.
 */
@ExtendWith(MockitoExtension.class)
class PrimeServiceTest {

    @Mock private InterventionRepository interventions;
    @Mock private InterventionPrimeRepository primes;
    @Mock private InterventionPhotoRepository photos;
    @Mock private InterventionEventRepository events;
    @Mock private TechnicianRepository technicians;
    @Mock private InterventionService visibility;
    @Mock private AuditService audit;

    private static final String ITV = "itv-1";
    private static final String TECH_ID = "tch-1";

    /** Plafond de 12 h : la règle annoncée, testée telle quelle. */
    private PrimeService service() {
        return new PrimeService(interventions, primes, photos, events, technicians, visibility, audit, 12);
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Sortie complète, clôturée : dehors de 2 h 06 (126 minutes). */
    private static Intervention sortie(InterventionStatus status) {
        Intervention i = new Intervention();
        i.setId(ITV);
        i.setNumber("DP-2026-007");
        i.setStatus(status);
        i.setTechnicianId(TECH_ID);
        i.setVehicleId("veh-1");
        i.setClientId("cli-1");
        i.setReportedAt(LocalDateTime.now().minusHours(6));
        i.setDepartedAt(LocalDateTime.now().minusHours(5));
        i.setReturnedAt(i.getDepartedAt().plusMinutes(126));
        i.setCreatedAt(LocalDateTime.now().minusHours(6));
        return i;
    }

    private static Technician team(BigDecimal rate, BigDecimal allowance) {
        Technician t = new Technician();
        t.setId(TECH_ID);
        t.setName("Ali Trabelsi");
        t.setActive(true);
        t.setHourlyRate(rate);
        t.setDispatchAllowance(allowance);
        return t;
    }

    /** Les deux conditions d'une sortie propre : clôturée et illustrée. */
    private void clean(Intervention i) {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);
        when(events.findByInterventionIdOrderByRecordedAtAsc(ITV)).thenReturn(List.of());
    }

    // -------------------------------------------------------------------- calcul

    @Test
    void leMontantPartDesHeuresDuServeurEtPasDuneSaisie() {
        clean(sortie(InterventionStatus.CLOSED));
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        PrimeDto prime = service().forIntervention(ITV, auth("CHEF_ATELIER"), jwt());

        assertEquals(Long.valueOf(126L), prime.realMinutes());
        assertEquals(Long.valueOf(126L), prime.paidMinutes());
        // 126 minutes = 8,4 quarts d'heure, arrondi au plus proche : 2,00 h.
        assertEquals(0, prime.billableHours().compareTo(new BigDecimal("2.00")));
        // 2,00 h × 20 + forfait 15.
        assertEquals(0, prime.amount().compareTo(new BigDecimal("55.000")));
        assertTrue(prime.anomalies().isEmpty(), String.join(" / ", prime.anomalies()));
        assertFalse(prime.validated());
        // La règle est écrite à l'écran avec le résultat, pas dans une note de service.
        assertTrue(prime.rule().contains("quart d'heure"), prime.rule());
    }

    @Test
    void sansDepartNiRetourIlN_YaPasDePrime() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        i.setReturnedAt(null);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);
        when(events.findByInterventionIdOrderByRecordedAtAsc(ITV)).thenReturn(List.of());
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        PrimeDto prime = service().forIntervention(ITV, auth("CHEF_ATELIER"), jwt());

        assertNull(prime.amount());
        assertNull(prime.billableHours());
        assertTrue(prime.anomalies().stream().anyMatch(a -> a.contains("départ ou retour non horodaté")),
                String.join(" / ", prime.anomalies()));
    }

    @Test
    void uneDureeHorsNormeEstPlafonneeEtSignalee() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        i.setReturnedAt(i.getDepartedAt().plusHours(20));
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);
        when(events.findByInterventionIdOrderByRecordedAtAsc(ITV)).thenReturn(List.of());
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        PrimeDto prime = service().forIntervention(ITV, auth("CHEF_ATELIER"), jwt());

        assertEquals(Long.valueOf(1200L), prime.realMinutes(), "la durée réelle reste lisible");
        assertEquals(Long.valueOf(720L), prime.paidMinutes(), "ce qui se paie est plafonné");
        assertEquals(0, prime.billableHours().compareTo(new BigDecimal("12.00")));
        assertTrue(prime.anomalies().stream().anyMatch(a -> a.contains("plafonnée")),
                String.join(" / ", prime.anomalies()));
    }

    @Test
    void lesPreuvesManquantesSontSignaleesSurLaPrime() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(false);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(false);
        when(events.findByInterventionIdOrderByRecordedAtAsc(ITV)).thenReturn(List.of());
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        PrimeDto prime = service().forIntervention(ITV, auth("CHEF_ATELIER"), jwt());

        assertTrue(prime.anomalies().stream().anyMatch(a -> a.contains("photos de preuve manquantes")
                && a.contains("avant intervention")), String.join(" / ", prime.anomalies()));
    }

    @Test
    void uneHeureRectifieeApresCoupSeSignale() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);
        InterventionEvent corrected = new InterventionEvent();
        corrected.setEventType(InterventionEventType.CORRECTED);
        when(events.findByInterventionIdOrderByRecordedAtAsc(ITV)).thenReturn(List.of(corrected));
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        PrimeDto prime = service().forIntervention(ITV, auth("CHEF_ATELIER"), jwt());

        assertTrue(prime.anomalies().stream().anyMatch(a -> a.contains("rectifiée")),
                String.join(" / ", prime.anomalies()));
    }

    @Test
    void unGestionnaireSAVNeVoitPasLesPrimes() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().forIntervention(ITV, auth("GESTIONNAIRE_SAV"), jwt()));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("internes"), e.getReason());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void validerTantQueLaSortieNestPasClotureeEstRefuse() {
        Intervention i = sortie(InterventionStatus.RETURNED);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().validate(ITV, new PrimeValidationRequest(null), jwt(), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("Clôturez d'abord"), e.getReason());
        verify(primes, never()).save(any());
    }

    @Test
    void unSignalementSansJustificationEmpecheLaValidation() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.BEFORE)).thenReturn(false);
        when(photos.existsByInterventionIdAndAngle(ITV, PhotoAngle.AFTER)).thenReturn(true);
        when(events.findByInterventionIdOrderByRecordedAtAsc(ITV)).thenReturn(List.of());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().validate(ITV, new PrimeValidationRequest("  "), jwt(), auth("CHEF_ATELIER")));
        // Le refus répète ce qui est signalé : le chef décide avec la liste sous les yeux.
        assertTrue(e.getReason().contains("Écrivez pourquoi"), e.getReason());
        assertTrue(e.getReason().contains("avant intervention"), e.getReason());
        verify(primes, never()).save(any());
    }

    @Test
    void validerRecopieLesTauxEtSigneLaDecision() {
        clean(sortie(InterventionStatus.CLOSED));
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        PrimeDto prime = service().validate(ITV, new PrimeValidationRequest(null), jwt(), auth("CHEF_ATELIER"));

        ArgumentCaptor<InterventionPrime> saved = ArgumentCaptor.forClass(InterventionPrime.class);
        verify(primes).save(saved.capture());
        InterventionPrime row = saved.getValue();
        assertEquals(ITV, row.getInterventionId());
        assertEquals(new BigDecimal("20.000"), row.getHourlyRate());
        assertEquals(new BigDecimal("15.000"), row.getDispatchAllowance());
        assertEquals(0, row.getAmount().compareTo(new BigDecimal("55.000")));
        assertNotNull(row.getValidatedAt());
        assertEquals("chef@saphir.tn", row.getValidatedByEmail());
        assertTrue(prime.validated());

        ArgumentCaptor<String> journal = ArgumentCaptor.forClass(String.class);
        verify(visibility).event(eq(ITV), eq(InterventionEventType.PRIME), any(), any(), journal.capture());
        assertTrue(journal.getValue().contains("55.000"), journal.getValue());
    }

    @Test
    void unePrimeNeSeValidePasDeuxFois() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.of(validatedRow()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().validate(ITV, new PrimeValidationRequest(null), jwt(), auth("CHEF_ATELIER")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("déjà validée"), e.getReason());
    }

    @Test
    void uneLigneValideeSeRelIntacteMemeSiLeTauxAChange() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        // Le technicien a été augmenté depuis : la prime payée ne doit pas bouger.
        when(technicians.findById(TECH_ID))
                .thenReturn(Optional.of(team(new BigDecimal("30.000"), new BigDecimal("25.000"))));
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.of(validatedRow()));

        PrimeDto prime = service().forIntervention(ITV, auth("ADMIN"), jwt());

        assertTrue(prime.validated());
        assertEquals(0, prime.amount().compareTo(new BigDecimal("55.000")), prime.amount().toString());
        assertEquals(new BigDecimal("20.000"), prime.hourlyRate());
    }

    // -------------------------------------------------------------------- période

    @Test
    void leTableauDuMoisRegroupeParTechnicien() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        when(primes.findByValidatedAtBetweenOrderByValidatedAtAsc(any(), any()))
                .thenReturn(List.of(validatedRow(), validatedRow()));
        when(interventions.findAllById(any())).thenReturn(List.of(i, otherSortie()));
        when(technicians.findAllById(any())).thenReturn(List.of(
                team(new BigDecimal("20.000"), new BigDecimal("15.000"))));
        when(primes.sumAmountBetween(any(), any())).thenReturn(new BigDecimal("110.000"));

        PrimePeriodResponse period = service().period("2026-10", auth("CHEF_ATELIER"));

        assertEquals("2026-10", period.period());
        assertEquals(2, period.lines().size());
        assertEquals(1, period.byTechnician().size());
        assertEquals(2L, period.byTechnician().get(0).sorties());
        assertEquals(0, period.byTechnician().get(0).hours().compareTo(new BigDecimal("4.00")));
        assertEquals(0, period.grandTotal().compareTo(new BigDecimal("110.000")));
    }

    @Test
    void unePeriodeMalEcriteEstRefuseeAvecLeFormatAttendu() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().period("octobre 2026", auth("ADMIN")));
        assertTrue(e.getReason().contains("AAAA-MM"), e.getReason());
    }

    private static Intervention otherSortie() {
        Intervention i = sortie(InterventionStatus.CLOSED);
        i.setId("itv-2");
        i.setNumber("DP-2026-008");
        return i;
    }

    private static InterventionPrime validatedRow() {
        InterventionPrime p = new InterventionPrime();
        p.setId("prm-1");
        p.setInterventionId(ITV);
        p.setPaidMinutes(126L);
        p.setBillableHours(new BigDecimal("2.00"));
        p.setHourlyRate(new BigDecimal("20.000"));
        p.setDispatchAllowance(new BigDecimal("15.000"));
        p.setAmount(new BigDecimal("55.000"));
        p.setValidatedAt(LocalDateTime.now().minusDays(1));
        p.setValidatedByEmail("chef@saphir.tn");
        return p;
    }
}
