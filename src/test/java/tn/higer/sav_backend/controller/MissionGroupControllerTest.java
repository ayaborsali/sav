package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusLineRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DepartRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.HistoryRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.NextNumberResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.RetourRequest;
import tn.higer.sav_backend.service.MissionGroupService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les routes de Mission groupe ne portent aucune règle : elles passent la demande
 * telle quelle au service, jeton et rôle compris. Ce qui est figé ici est le
 * face-à-face bouton / route.
 */
@ExtendWith(MockitoExtension.class)
class MissionGroupControllerTest {

    @Mock private MissionGroupService service;

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    private static DossierResponse vue() {
        return new DossierResponse("mgd-1", "1740", "cli-1", "SRT Sousse",
                "Zone industrielle, Sousse", "Sousse", "73 200 100",
                "telephone", "Téléphone",
                "2026-11-11T08:00", "2026-11-11T18:00", 45_120L, 45_500L,
                "usr-1", "Moez", "sv-1", "TU 1234",
                null, null, null, null, null,
                "prevu", "Départ prévu", "planifiee", "Planifiée",
                null, null, null,
                List.of(), List.of(), List.of(), false, List.of(),
                "usr-chef", "2026-11-10T09:00");
    }

    private static DossierRequest demande() {
        return new DossierRequest("1740", "cli-1",
                LocalDateTime.of(2026, 11, 11, 8, 0), "usr-1", "sv-1",
                List.of(new BusLineRequest("veh-1", "problème bielle", null)),
                List.of("Changer la bielle"),
                "telephone", LocalDateTime.of(2026, 11, 11, 18, 0), null, null);
    }

    @Test
    void creer_transmet_le_dossier_et_ses_bus() {
        DossierRequest requete = demande();
        DossierResponse rendu = vue();
        when(service.creer(any(), any(), any())).thenReturn(rendu);
        Jwt jwt = jeton();

        assertSame(rendu, new MissionGroupController(service).creer(requete, jwt, chef()));
        verify(service).creer(requete, jwt, chef());
    }

    @Test
    void le_depart_transmet_l_heure_et_le_kilometrage() {
        DepartRequest requete = new DepartRequest(
                LocalDateTime.of(2026, 11, 11, 8, 15), 45_120L);
        DossierResponse rendu = vue();
        when(service.depart(any(), any(), any(), any())).thenReturn(rendu);
        Jwt jwt = jeton();
        Authentication auth = chef();

        assertSame(rendu, new MissionGroupController(service).depart("mgd-1", requete, jwt, auth));
        verify(service).depart("mgd-1", requete, jwt, auth);
    }

    @Test
    void le_retour_transmet_la_date_le_km_et_les_diagnostics() {
        RetourRequest requete = new RetourRequest(LocalDateTime.of(2026, 11, 11, 16, 0), 45_240L,
                List.of(new tn.higer.sav_backend.dto.MissionGroupDtos.BusDiagnosisRequest(
                        "mgb-1", null, "bielle remplacée")));
        DossierResponse rendu = vue();
        when(service.retour(any(), any(), any(), any())).thenReturn(rendu);
        Jwt jwt = jeton();
        Authentication auth = chef();

        assertSame(rendu, new MissionGroupController(service).retour("mgd-1", requete, jwt, auth));
        verify(service).retour("mgd-1", requete, jwt, auth);
    }

    @Test
    void l_historique_transmet_la_plaque_telle_quelle() {
        List<HistoryRow> rendu = List.of();
        Authentication auth = chef();
        when(service.historique("230-TU-3342", auth)).thenReturn(rendu);
        assertEquals(rendu, new MissionGroupController(service).historique("230-TU-3342", auth));
        verify(service).historique("230-TU-3342", auth);
    }

    @Test
    void le_numero_propose_vient_du_service() {
        when(service.prochainNumero()).thenReturn("1741");
        assertEquals(new NextNumberResponse("1741"),
                new MissionGroupController(service).prochainNumero());
    }

    @Test
    void la_liste_passe_la_pagination_le_jeton_et_le_role_au_service() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<DossierResponse> rendu = Page.empty(pageable);
        Jwt jwt = jeton();
        Authentication auth = chef();
        when(service.list(pageable, jwt, auth)).thenReturn(rendu);

        assertSame(rendu, new MissionGroupController(service).list(pageable, jwt, auth));
    }

    @Test
    void la_fiche_passe_le_jeton_et_le_role_au_service() {
        DossierResponse rendu = vue();
        Jwt jwt = jeton();
        Authentication auth = chef();
        when(service.get("mgd-1", jwt, auth)).thenReturn(rendu);

        assertSame(rendu, new MissionGroupController(service).get("mgd-1", jwt, auth));
    }

    @Test
    void la_correction_transmet_le_dossier_et_sa_periode() {
        DossierRequest requete = demande();
        DossierResponse rendu = vue();
        Authentication auth = chef();
        when(service.modifier("mgd-1", requete, null, auth)).thenReturn(rendu);

        assertSame(rendu, new MissionGroupController(service).modifier("mgd-1", requete, null, auth));
        verify(service).modifier("mgd-1", requete, null, auth);
    }

    @Test
    void les_disponibilites_transmettent_la_periode_et_le_dossier_exclu() {
        LocalDateTime depart = LocalDateTime.of(2026, 11, 11, 8, 0);
        LocalDateTime retour = LocalDateTime.of(2026, 11, 11, 18, 0);
        Authentication auth = chef();
        var rendu = new tn.higer.sav_backend.dto.MissionGroupDtos.AvailabilityResponse(
                List.of("usr-1"), List.of("sv-1"));
        when(service.disponibilites(depart, retour, "mgd-1", auth)).thenReturn(rendu);

        assertSame(rendu, new MissionGroupController(service)
                .disponibilites(depart, retour, "mgd-1", auth));
        verify(service).disponibilites(depart, retour, "mgd-1", auth);
    }
}
