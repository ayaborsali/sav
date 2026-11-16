package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.MileageOrigin;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La porte unique du compteur du bus (phase G).
 *
 * Ce qui se joue ici se voit à deux écrans — la colonne « kilometrage » de
 * l'écran Véhicules et les compteurs relus sur les sorties de dépannage — et le
 * portail doit donner le même chiffre des deux côtés. Les tests portent donc sur
 * le sens unique (ça monte, ça ne redescend pas), sur le refus qui nomme la sortie
 * d'en face, et sur la baisse qui ne se paie que par un motif écrit.
 */
@ExtendWith(MockitoExtension.class)
class VehicleMileageServiceTest {

    @Mock private VehicleRepository vehicles;
    @Mock private InterventionRepository interventions;
    @Mock private TechnicianRepository technicians;
    @Mock private AuditService audit;

    private static final String BUS_ID = "veh-1";

    private VehicleMileageService porte() {
        return new VehicleMileageService(vehicles, interventions, technicians, audit);
    }

    // ------------------------------------------------------------------ fixtures

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "gestionnaire@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Le bus de la fiche Véhicules : 8 000 km tapés à l'atelier en août. */
    private static Vehicle busALaFiche(long km) {
        Vehicle v = new Vehicle();
        v.setId(BUS_ID);
        v.setVin("LK1H2A2B3C4D5E6F7");
        v.setRegistration("CS-1234-26");
        v.setMileage(km);
        v.setMileageOrigin(MileageOrigin.ATELIER);
        return v;
    }

    /** La sortie du 18/10 : Ali a relu 10 120 km sur le tableau de bord. */
    private static Intervention sortieRelevee(long arrivee, Long fin) {
        Intervention i = new Intervention();
        i.setId("itv-14");
        i.setNumber("DP-2026-014");
        i.setVehicleId(BUS_ID);
        i.setTechnicianId("tch-1");
        i.setReportedAt(LocalDateTime.of(2026, 10, 18, 8, 30));
        i.setBusMileage(arrivee);
        i.setBusMileageEnd(fin);
        return i;
    }

    private static Technician ali() {
        Technician t = new Technician();
        t.setId("tch-1");
        t.setName("Ali Trabelsi");
        t.setUserId(UUID.randomUUID());
        t.setActive(true);
        return t;
    }

    // ------------------------------------------------------------------ le relevé fait monter la fiche

    @Test
    void unRelevePlusHautQueLaFicheRemonteLaFiche() {
        Vehicle bus = busALaFiche(8_000L);
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus));

        porte().releveTechnicien(BUS_ID, 10_120L, "Ali Trabelsi");

        assertEquals(Long.valueOf(10_120L), bus.getMileage(),
                "l'atelier n'a rien à ressaisir : la mesure du technicien tient la fiche");
        assertEquals(MileageOrigin.RELEVE_TECHNICIEN, bus.getMileageOrigin());
        assertEquals("Ali Trabelsi", bus.getMileageRecordedBy());
        assertNotNull(bus.getMileageRecordedAt(), "la fiche doit dire quand le chiffre a été appris");
        assertNull(bus.getMileageCorrectionReason());
    }

    @Test
    void unRelevePlusBasQueLaFicheNeLaFaitJamaisRedescendre() {
        Vehicle bus = busALaFiche(12_000L);
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus));

        porte().releveTechnicien(BUS_ID, 10_120L, "Ali Trabelsi");

        assertEquals(Long.valueOf(12_000L), bus.getMileage());
        assertEquals(MileageOrigin.ATELIER, bus.getMileageOrigin(),
                "un relevé qui ne dépasse pas la fiche ne réécrit pas son origine");
    }

    @Test
    void unReleveSansBusEncoreAuPortefeuilleNeCasseRien() {
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.empty());

        porte().releveTechnicien(BUS_ID, 10_120L, "Ali Trabelsi");

        verify(vehicles, never()).save(any());
    }

    // ------------------------------------------------------------------ la saisie de l'atelier

    @Test
    void laSaisiePlusBasseQueLaFicheEstRefuseeEnNommantLaSortieQuiLAite() {
        // 10 120 km sur la fiche, et c'est la sortie du 18/10 qui les y a montés :
        // le refus doit nommer cette sortie, pas un chiffre sans provenance.
        Vehicle bus = busALaFiche(10_120L);
        bus.setMileageOrigin(MileageOrigin.RELEVE_TECHNICIEN);
        bus.setMileageRecordedBy("Ali Trabelsi");
        when(interventions.findRelevesBus(BUS_ID)).thenReturn(List.of(sortieRelevee(10_120L, null)));
        when(technicians.findById("tch-1")).thenReturn(Optional.of(ali()));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> porte().saisieAtelier(bus, 9_000L, null, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        String ecrit = refus.getReason();
        assertTrue(ecrit.contains("10120"), "la valeur d'en face doit être écrite : " + ecrit);
        assertTrue(ecrit.contains("DP-2026-014"), "la sortie qui a lu ce compteur doit être nommée : " + ecrit);
        assertTrue(ecrit.contains("Ali Trabelsi"), "l'homme qui l'a lu doit être nommé : " + ecrit);
        assertTrue(ecrit.contains("2026-10-18"), "la date du relevé doit être donnée : " + ecrit);
        assertTrue(ecrit.contains("motif de la baisse"), "la porte de sortie doit être dite : " + ecrit);
        assertEquals(Long.valueOf(10_120L), bus.getMileage(), "un refus n'écrit rien");
    }

    @Test
    void uneBasseEcriteAccepteeEtTracee() {
        Vehicle bus = busALaFiche(10_120L);
        when(interventions.findRelevesBus(BUS_ID)).thenReturn(List.of(sortieRelevee(10_120L, null)));
        when(technicians.findById("tch-1")).thenReturn(Optional.of(ali()));

        porte().saisieAtelier(bus, 5_400L, "compteur remplacé après panne électrique, relevé 18/10 faux", jwt());

        assertEquals(Long.valueOf(5_400L), bus.getMileage());
        assertEquals(MileageOrigin.ATELIER, bus.getMileageOrigin());
        assertEquals("compteur remplacé après panne électrique, relevé 18/10 faux",
                bus.getMileageCorrectionReason());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("MILEAGE_BAISSE"), eq("VEHICLE"), eq(BUS_ID), detail.capture(), any());
        assertTrue(detail.getValue().contains("5400"), detail.getValue());
        assertTrue(detail.getValue().contains("compteur remplacé"), detail.getValue());
    }

    /**
     * La baisse acceptée n'est pas à re-justifier à chaque saisie : une sortie ancienne
     * lue plus haut ne doit plus tenir le plancher. Sans cette règle, l'atelier qui a
     * remis un bus à 4 300 km avec motif se verrait refuser 4 450 km le mois suivant au
     * nom d'une sortie d'octobre.
     */
    @Test
    void uneBaisseEcriteNeLaissePasUneSortieAncienneFaireLaLoi() {
        Vehicle bus = busALaFiche(4_300L);
        bus.setMileageCorrectionReason("compteur remplacé, relevé du 18/10 faux");
        when(interventions.findRelevesBus(BUS_ID)).thenReturn(List.of(sortieRelevee(10_120L, null)));
        when(technicians.findById("tch-1")).thenReturn(Optional.of(ali()));

        porte().saisieAtelier(bus, 4_450L, null, jwt());

        assertEquals(Long.valueOf(4_450L), bus.getMileage());
        assertEquals(MileageOrigin.ATELIER, bus.getMileageOrigin());
        assertNull(bus.getMileageCorrectionReason(), "une remontée n'a plus rien à justifier");
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void uneValeurAuDessusDeLaFicheSPoseSansDemanderDeMotif() {
        Vehicle bus = busALaFiche(8_000L);
        when(interventions.findRelevesBus(BUS_ID)).thenReturn(List.of());

        porte().saisieAtelier(bus, 12_500L, "   ", jwt());

        assertEquals(Long.valueOf(12_500L), bus.getMileage());
        assertEquals(MileageOrigin.ATELIER, bus.getMileageOrigin());
        assertEquals("gestionnaire@saphir.tn", bus.getMileageRecordedBy());
        assertNull(bus.getMileageCorrectionReason(),
                "un motif ne se garde que pour la baisse qu'il justifie");
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(),
                any());
    }

    @Test
    void sansReleveSurSortieLeRefusNommeLaFiche() {
        Vehicle bus = busALaFiche(12_000L);
        when(interventions.findRelevesBus(BUS_ID)).thenReturn(List.of());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> porte().saisieAtelier(bus, 11_000L, null, jwt()));

        assertTrue(refus.getReason().contains("la valeur déjà sur la fiche du bus : 12000 km"),
                refus.getReason());
    }

    @Test
    void uneSaisieVideNeRemetPasLeCompteurAZero() {
        Vehicle bus = busALaFiche(10_120L);

        // L'atelier modifie l'immatriculation et laisse la case vide : le champ vide
        // n'a jamais été une nouvelle valeur du compteur.
        porte().saisieAtelier(bus, null, null, jwt());

        assertEquals(Long.valueOf(10_120L), bus.getMileage());
        assertEquals(MileageOrigin.ATELIER, bus.getMileageOrigin());
    }

    @Test
    void uneFicheNeuveSansValeurPartAZeroAvecSonOrigine() {
        Vehicle bus = new Vehicle();
        bus.setId("veh-99");

        porte().saisieAtelier(bus, null, null, jwt());

        assertEquals(Long.valueOf(0L), bus.getMileage());
        assertEquals(MileageOrigin.ATELIER, bus.getMileageOrigin());
    }

    // ------------------------------------------------------------------ la borne commune

    @Test
    void unCompteurHorsBornesEstRefuseDesDeuxCotes() {
        Vehicle bus = busALaFiche(8_000L);

        // La borne se juge avant même de demander ce que le portail sait du bus.
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> porte().saisieAtelier(bus, 1_000_000L, null, jwt()));
        assertTrue(refus.getReason().contains("Kilométrage invalide"), refus.getReason());

        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus));
        assertThrows(ResponseStatusException.class,
                () -> porte().releveTechnicien(BUS_ID, 9_999_999L, "Ali Trabelsi"));
    }

    // ------------------------------------------------------------------ le plus haut des relevés

    @Test
    void lePlusHautDesDeuxLecturesDuneSortieGagne() {
        when(interventions.findRelevesBus(BUS_ID))
                .thenReturn(List.of(sortieRelevee(9_000L, 15_000L), sortieRelevee(10_120L, null)));

        VehicleMileageService.DernierReleve releve = porte().dernierReleve(BUS_ID);

        assertEquals(15_000L, releve.km(), "la lecture de fin passe avant celle de l'arrivée");
        assertEquals("DP-2026-014", releve.sortie());
        assertEquals(LocalDateTime.of(2026, 10, 18, 8, 30), releve.quand());
    }

    @Test
    void aucunReleveSurSortieNeLaisseLePlancherALaFiche() {
        when(interventions.findRelevesBus(BUS_ID)).thenReturn(List.of());

        assertNull(porte().dernierReleve(BUS_ID));
    }
}
