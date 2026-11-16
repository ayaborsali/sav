package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.DurationsDto;
import tn.higer.sav_backend.dto.InterventionDtos.InterventionResponse;
import tn.higer.sav_backend.dto.InterventionDtos.LocationDto;
import tn.higer.sav_backend.dto.InterventionDtos.MileagesDto;
import tn.higer.sav_backend.dto.InterventionDtos.TimesDto;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.InterventionPrime;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionPrimeRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Le contenu du bon d'intervention, tel que le reçoit le script de mise en page.
 *
 * Ce que ces tests protègent, dans l'ordre : la version client ne contient AUCUN montant
 * (la confidentialité se décide avant la page, pas dedans), la version interne n'est pas
 * ouverte à qui n'a pas la paie, une prime non validée ne s'imprime pas comme si elle
 * l'était, et une heure manquante reste écrite « non horodaté » au lieu de disparaître —
 * c'est le document qui sert au contrôle, il ne doit rien arranger.
 */
@ExtendWith(MockitoExtension.class)
class InterventionDocumentServiceTest {

    @Mock private InterventionService visibility;
    @Mock private VehicleRepository vehicles;
    @Mock private InterventionPhotoRepository photos;
    @Mock private InterventionPrimeRepository primes;

    private static final String ITV = "itv-1";
    private static final LocalDateTime DEPART = LocalDateTime.of(2026, 10, 19, 8, 0);

    private InterventionDocumentService service() {
        return new InterventionDocumentService(visibility, vehicles, photos, primes,
                "Saphir Véhicules et Automobiles", "NZI Cité Ettaamir 4000 Sousse");
    }

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject("usr-1").claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    // ------------------------------------------------------------------ fixtures

    private static Intervention entity() {
        Intervention i = new Intervention();
        i.setId(ITV);
        i.setNumber("DP-2026-007");
        i.setStatus(InterventionStatus.CLOSED);
        i.setTechnicianId("tch-1");
        i.setVehicleId("veh-1");
        i.setClientId("cli-1");
        return i;
    }

    /** Une sortie menée jusqu'au bout : dehors 2 h 06, trajet 42 min, sur place 1 h 10. */
    private static InterventionResponse sortieComplete() {
        return vue(DEPART, DEPART.plusMinutes(42), DEPART.plusMinutes(102),
                DEPART.plusMinutes(110), DEPART.plusMinutes(126), DEPART.plusMinutes(180));
    }

    private static InterventionResponse vue(LocalDateTime departed, LocalDateTime arrived,
                                            LocalDateTime finished, LocalDateTime leftSite,
                                            LocalDateTime returned, LocalDateTime closed) {
        TimesDto times = new TimesDto(DEPART.minusMinutes(30), DEPART.minusMinutes(20),
                departed, arrived, finished, leftSite, returned, closed);
        MileagesDto mileages = new MileagesDto(12_000L, 12_058L, 98_400L, 98_412L, 58L);
        // Les durées se déduisent des mêmes horodatages que le service : rien n'est forcé ici.
        DurationsDto durations = new DurationsDto(minutes(departed, arrived), minutes(arrived, finished),
                minutes(arrived, leftSite), minutes(departed, returned));
        return new InterventionResponse(ITV, "DP-2026-007", "closed", "high",
                "veh-1", "LTSD45H32D0000123", "cli-1", "Transports du Sahel",
                "tch-1", "Ali Trabelsi", "sv-1", "3162-419-16",
                new LocationDto("Route de Tunis, km 12", "Sousse"),
                "M. Ben Salah", "Bus immobilisé, témoin défaut moteur allumé.",
                times, mileages, durations,
                "P0217", "Surchauffe, ventilateur non alimenté.", "Relais de ventilateur",
                "Réparé sur place, bus remis en service.", "M. Ben Salah", "Sortie conforme.",
                "sav-1", "SAV-WAR-2026-004",
                "panne", null, null, null,
                "usr-1", false, LocalDateTime.now(),
                DEPART, closed, null, null, List.of(), false);
    }

    private static Long minutes(LocalDateTime from, LocalDateTime to) {
        return from == null || to == null ? null : java.time.Duration.between(from, to).toMinutes();
    }

    private void busPresent() {
        Vehicle v = new Vehicle();
        v.setId("veh-1");
        v.setVin("LTSD45H32D0000123");
        v.setRegistration("1234-419-16");
        v.setModel("Confort");
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(v));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("tables");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> rowsOf(Map<String, Object> table) {
        return (List<List<String>>) table.get("rows");
    }

    /** La table dont l'en-tête contient ce mot (le plan est une liste, pas une cle par document). */
    private static Map<String, Object> table(Map<String, Object> payload, String headingFragment) {
        return tables(payload).stream()
                .filter(t -> String.valueOf(t.get("heading")).contains(headingFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Table introuvable : " + headingFragment
                        + " — tables = " + tables(payload)));
    }

    // ------------------------------------------------------------------ versions

    @Test
    void versionClient_neContientAucuneLigneDePaie() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());
        when(visibility.full(any())).thenReturn(sortieComplete());
        busPresent();

        Map<String, Object> payload = service().payload(ITV, "client", auth("CHEF_ATELIER"), jwt());

        assertEquals(1, tables(payload).size(), "Une seule table en version client : les horaires");
        String texte = payload.toString();
        assertFalse(texte.contains("Taux horaire"), texte);
        assertFalse(texte.contains("prime"), "le mot même doit disparaître de la copie du client");
        assertFalse(texte.contains("DT"), "Aucun montant, pas même en discussion");
        assertTrue(texte.contains("LTSD45H32D0000123"), "le bus, lui, est bien identifié");
    }

    @Test
    void versionInterne_ajouteLaPrimeValideeEtLesPreuves() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());
        when(visibility.full(any())).thenReturn(sortieComplete());
        busPresent();
        when(photos.findByInterventionIdOrderByUploadedAtAsc(ITV)).thenReturn(List.of(
                cliché(PhotoAngle.BEFORE), cliché(PhotoAngle.AFTER), cliché(PhotoAngle.AFTER)));
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.of(prime()));

        Map<String, Object> payload = service().payload(ITV, "interne", auth("ADMIN"), jwt());

        String texte = payload.toString();
        assertTrue(texte.contains("55,000 DT"), texte);
        assertTrue(texte.contains("2,00 h"), "la durée facturée se relit");
        assertTrue(texte.contains("chef@saphir.tn"), "qui a validé la prime est nommé");
        assertTrue(texte.contains("15,000 DT"), "le forfait recopié dans la ligne se relit : " + texte);
        assertTrue(rowsOf(table(payload, "Preuves photographiques")).toString().contains("2 clichés"),
                "deux clichés après intervention, comptés et écrits");
        assertTrue(payload.toString().contains("Compteur du bus à la fin"),
                "le second relevé du compteur du bus se relit sur le bon");
    }

    @Test
    void versionInterne_sansPrimeValidee_nImprimeAucunEstime() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());
        when(visibility.full(any())).thenReturn(sortieComplete());
        busPresent();
        when(photos.findByInterventionIdOrderByUploadedAtAsc(ITV)).thenReturn(List.of());
        when(primes.findByInterventionId(ITV)).thenReturn(Optional.empty());

        Map<String, Object> payload = service().payload(ITV, "interne", auth("CHEF_ATELIER"), jwt());

        assertTrue(payload.toString().contains("non validée à l'édition"),
                "le document doit dire qu'aucun montant n'est engagé");
        String prime = rowsOf(table(payload, "Prime d'intervention")).toString();
        assertEquals(1, rowsOf(table(payload, "Prime d'intervention")).size(),
                "une prime non validée n'ajoute aucune ligne de calcul : " + prime);
        assertFalse(prime.contains("DT"), "aucun montant sur une prime qui n'a pas été validée : " + prime);
        assertTrue(rowsOf(table(payload, "Preuves photographiques")).toString().contains("aucun cliché"));
    }

    @Test
    void laVersionInterneEstRefuseeALQuiNaPasLaPaie() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service().payload(ITV, "interne", auth("GESTIONNAIRE_SAV"), jwt()));

        assertEquals(HttpStatus.FORBIDDEN, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("version client"),
                "le refus doit dire quoi imprimer à la place : " + erreur.getReason());
    }

    @Test
    void uneVersionInconnueEstNommeePlutotQueTombeeSurLeClient() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service().payload(ITV, "paie", auth("ADMIN"), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("client") && erreur.getReason().contains("interne"),
                erreur.getReason());
    }

    @Test
    void leTechnicienDUneAutreSortieNeRecoitRien() {
        // La règle de visibilité est celle du module : le service d'édition ne la réinvente pas.
        when(visibility.requireVisible(eq(ITV), any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Sortie introuvable."));

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service().payload(ITV, "client", auth("TECHNICIEN"), jwt()));

        assertEquals(HttpStatus.NOT_FOUND, erreur.getStatusCode());
    }

    // ------------------------------------------------------------------ contenu

    @Test
    void uneHeureManquanteResteEcrite_nonHorodateEtNonCalculable() {
        Intervention i = entity();
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(i);
        // Sortie ouverte jamais rentrée : départ et retour absents.
        when(visibility.full(any())).thenReturn(vue(null, null, null, null, null, null));
        busPresent();

        Map<String, Object> payload = service().payload(ITV, "client", auth("CHEF_ATELIER"), jwt());

        String tableau = rowsOf(table(payload, "Horodatages")).toString();
        assertTrue(tableau.contains("non horodaté"), tableau);
        assertTrue(tableau.contains("non calculable"), "une durée fausse vaut mieux qu'une durée absente");
        assertFalse(tableau.contains("0 h 00"), "rien n'est inventé pour remplir la page");
    }

    @Test
    void leDiagnosticRassembleLeCodeLaPieceEtLeTexte() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());
        when(visibility.full(any())).thenReturn(sortieComplete());
        busPresent();

        Map<String, Object> payload = service().payload(ITV, "client", auth("CHEF_ATELIER"), jwt());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> texts = (List<Map<String, Object>>) payload.get("texts");
        String diagnostic = texts.stream()
                .filter(t -> String.valueOf(t.get("heading")).contains("Diagnostic"))
                .findFirst().orElseThrow().get("body").toString();
        assertTrue(diagnostic.contains("Code défaut : P0217"), diagnostic);
        assertTrue(diagnostic.contains("Pièce en cause : Relais de ventilateur"), diagnostic);
        assertTrue(diagnostic.contains("Surchauffe"), diagnostic);
    }

    @Test
    void laCopieClientSeSigneADeuxEtSansLeChefDatelier() {
        when(visibility.requireVisible(eq(ITV), any(), any())).thenReturn(entity());
        when(visibility.full(any())).thenReturn(sortieComplete());
        busPresent();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clientSignatures =
                (List<Map<String, Object>>) service().payload(ITV, "client",
                        auth("CHEF_ATELIER"), jwt()).get("signatures");
        assertEquals(2, clientSignatures.size(), "technicien + client, personne d'autre sur la copie client");
        assertTrue(clientSignatures.toString().contains("Ali Trabelsi"));
        assertFalse(clientSignatures.toString().contains("chef d'atelier"),
                "la case du chef n'existe que sur la version interne");
    }

    // ------------------------------------------------------------------ pièces

    private static InterventionPhoto cliché(PhotoAngle angle) {
        InterventionPhoto p = new InterventionPhoto();
        p.setId("pht-1");
        p.setInterventionId(ITV);
        p.setAngle(angle);
        p.setUploadedAt(DEPART);
        return p;
    }

    private static InterventionPrime prime() {
        InterventionPrime p = new InterventionPrime();
        p.setId("prm-1");
        p.setInterventionId(ITV);
        p.setPaidMinutes(126L);
        p.setBillableHours(new BigDecimal("2.00"));
        p.setHourlyRate(new BigDecimal("20.000"));
        p.setDispatchAllowance(new BigDecimal("15.000"));
        p.setAmount(new BigDecimal("55.000"));
        p.setAnomalies("photos de preuve manquantes : compteur");
        p.setJustification("Le poste était en panne, cliché repris le lendemain.");
        p.setValidatedByEmail("chef@saphir.tn");
        p.setValidatedAt(DEPART.plusHours(1));
        return p;
    }
}
