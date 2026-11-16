package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ContractDtos.DraftPreviewRequest;
import tn.higer.sav_backend.dto.ContractDtos.DraftResponse;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.ContractDocumentService;
import tn.higer.sav_backend.service.ContractPdfService;
import tn.higer.sav_backend.service.GroqDraftService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La rédaction IA d'un contrat que l'on est en train de saisir : le bouton est ouvert avant le
 * premier enregistrement. Ce qui compte ici tient en une phrase — le navigateur n'envoie que des
 * identifiants, et ce qui part vers le modèle reste du texte sans un seul montant. Le modèle est
 * simulé : l'appel réel à Groq et le rejet de toute proposition chiffrée sont couverts par
 * GroqDraftServiceTest, et la clé ne passe jamais par cette couche.
 */
@ExtendWith(MockitoExtension.class)
class ContractDraftPreviewTest {

    @Mock
    private ContractRepository contracts;

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ClientRepository clients;

    @Mock
    private AuditService audit;

    @Mock
    private ContractDocumentService documentService;

    @Mock
    private ContractPdfService pdfService;

    @Mock
    private GroqDraftService draftService;

    private ContractController controller() {
        return new ContractController(contracts, vehicles, clients, audit,
                documentService, pdfService, draftService);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "x@y.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static Client client(String id, String name) {
        Client cl = new Client();
        cl.setId(id);
        cl.setName(name);
        cl.setCity("Sousse");
        return cl;
    }

    private static Vehicle bus(String id, String clientId, String model) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setClientId(clientId);
        v.setModel(model);
        v.setVin("LTSD1234567890123");
        v.setRegistration("1234-TA-26");
        // Le forfait est en base : il ne doit malgré tout jamais apparaître dans le contexte du modèle.
        v.setUnitPriceHt(new BigDecimal("450000.00"));
        return v;
    }

    /** Le contexte réellement transmis au modèle. */
    private String faitsEnvoyesAuModele() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(draftService).draft(anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void draftPreview_avantEnregistrement_redigeAvecLeClientEtLesBusCoches() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client("cli-1", "Transports du Sahel")));
        when(vehicles.findAllById(List.of("veh-1")))
                .thenReturn(List.of(bus("veh-1", "cli-1", "Confort")));
        when(draftService.draft(any(), any(), any()))
                .thenReturn("Le prestataire assure la maintenance préventive et corrective des véhicules confiés.");

        DraftResponse reponse = controller().draftPreview(
                new DraftPreviewRequest("object", "cli-1", List.of("veh-1")), jwt());

        assertEquals("object", reponse.section());
        assertTrue(reponse.text().contains("maintenance préventive"));
        String faits = faitsEnvoyesAuModele();
        assertTrue(faits.contains("Transports du Sahel"), faits);
        assertTrue(faits.contains("Confort"), faits);
        assertTrue(faits.contains("nombre de véhicules : 1"), faits);
        assertFalse(faits.contains("450"), "Le forfait HT du bus ne quitte pas le serveur : " + faits);
        assertFalse(faits.contains("LTSD"), "Le châssis n'est pas envoyé au modèle : " + faits);
        verify(audit).record(eq("AI_DRAFT"), eq("CONTRACT"), any(), anyString(), any());
    }

    @Test
    void draftPreview_busDuMauvaisClient_estEcarteDuContexte() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client("cli-1", "Transports du Sahel")));
        when(vehicles.findAllById(List.of("veh-9")))
                .thenReturn(List.of(bus("veh-9", "cli-2", "Scania K94")));
        when(draftService.draft(any(), any(), any())).thenReturn("Texte de clause relisible.");

        controller().draftPreview(new DraftPreviewRequest("dispute", "cli-1", List.of("veh-9")), jwt());

        String faits = faitsEnvoyesAuModele();
        assertFalse(faits.contains("Scania"),
                "Un bus qui n'est pas à ce client n'a rien à dire au modèle : " + faits);
        assertTrue(faits.contains("contrat en préparation"), faits);
    }

    @Test
    void draftPreview_sansBusCoche_leClientSuffit() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client("cli-1", "Transports du Sahel")));
        when(draftService.draft(any(), any(), any())).thenReturn("Texte de clause relisible.");

        controller().draftPreview(new DraftPreviewRequest("special", "cli-1", List.of()), jwt());

        String faits = faitsEnvoyesAuModele();
        assertTrue(faits.contains("Transports du Sahel"), faits);
        assertFalse(faits.contains("nombre de véhicules"), faits);
        verify(vehicles, never()).findAllById(any());
    }

    @Test
    void draftPreview_clientIntrouvable_renvoie400SansAppelerLeModele() {
        when(clients.findById("cli-9")).thenReturn(Optional.empty());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().draftPreview(
                        new DraftPreviewRequest("object", "cli-9", List.of()), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        verify(draftService, never()).draft(any(), any(), any());
    }

    @Test
    void draftPreview_sectionInconnue_renvoie400() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().draftPreview(
                        new DraftPreviewRequest("penalties", "cli-1", List.of()), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("Section de clause inconnue"), erreur.getReason());
    }
}
