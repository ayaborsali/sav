package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ContractDtos.DraftRequest;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.ContractClauses;
import tn.higer.sav_backend.service.ContractDocumentService;
import tn.higer.sav_backend.service.ContractPdfService;
import tn.higer.sav_backend.service.GroqDraftService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le contrat que l'on enregistre : l'endpoint PDF. La mise en page étant déléguée au script
 * Python, le service de PDF est simulé ici pour observer ce que Java lui transmet — c'est là que
 * se jouent l'autorité serveur (les montants viennent de la base, pas du navigateur) et la
 * confidentialité (le rôle consultation ne reçoit aucun plan chiffré). Le rendu réel du fichier
 * est vérifié dans ContractPdfServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class ContractDocumentTest {

    @Mock
    private ContractRepository contracts;

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ClientRepository clients;

    @Mock
    private AuditService audit;

    @Mock
    private ContractPdfService pdfService;

    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);

    /** Les quatre premiers octets de tout PDF : c'est ce que l'enregistrement utilisateur voit. */
    private static final byte[] OCTETS_PDF = "%PDF-1.7 test".getBytes();

    private static Authentication role(String authority) {
        return new UsernamePasswordAuthenticationToken("x@y.tn", null,
                List.of(new SimpleGrantedAuthority(authority)));
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

    private static ContractDocumentService documentService() {
        return new ContractDocumentService(
                new ContractClauses(60, 24, 100000, "Tribunal de Commerce de Tunis", "Saphir Véhicules et Automobiles"),
                "Zone Industrielle, Ariana", "B7654321", "1111111/T/M/000", "M. le Directeur", 19);
    }

    /** Sans clé : available() = false et draft() renvoie 503, ce qu'on veut tester ici. */
    private static GroqDraftService draftServiceSansCle() {
        return new GroqDraftService(true, "", "https://api.groq.com/openai/v1/chat/completions",
                "qwen/qwen3.8-27b", 0.3, 900, 5, 8, "python", "./scripts/draft_clause.py");
    }

    private ContractController controller() {
        return new ContractController(contracts, vehicles, clients, audit,
                documentService(), pdfService, draftServiceSansCle());
    }

    private void givenContractAvecUnBus() {
        when(contracts.findById("ctr-1")).thenReturn(Optional.of(contrat()));
        when(vehicles.findByContractId("ctr-1")).thenReturn(List.of(vehicule()));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planTransmisAuScript() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pdfService).render(captor.capture());
        return captor.getValue();
    }

    private static Contract contrat() {
        Contract c = new Contract();
        c.setId("ctr-1");
        c.setNumber("CT-HIG-2026-007");
        c.setContractDate(DATE);
        c.setClientId("cli-1");
        c.setPaymentTerms("30 jours virement");
        return c;
    }

    private static Client client() {
        Client cl = new Client();
        cl.setId("cli-1");
        cl.setName("Transports du Sahel");
        cl.setCity("Sousse");
        cl.setTradeRegister("B1234567");
        return cl;
    }

    private static Vehicle vehicule() {
        Vehicle v = new Vehicle();
        v.setId("veh-1");
        v.setVin("LTSD1234567890123");
        v.setRegistration("1234-TA-26");
        v.setModel("Confort");
        v.setClientId("cli-1");
        v.setContractId("ctr-1");
        v.setCommissioningDate(DATE);
        v.setUnitPriceHt(new BigDecimal("250000.00"));
        return v;
    }

    @Test
    void documentPdf_renvoieUnVraiFichierAvecLeNumeroDuContrat() {
        givenContractAvecUnBus();
        when(pdfService.render(any())).thenReturn(OCTETS_PDF);

        ResponseEntity<byte[]> reponse = controller().documentPdf("ctr-1", role("ROLE_ADMIN"));

        assertEquals(MediaType.APPLICATION_PDF, reponse.getHeaders().getContentType());
        assertTrue(new String(reponse.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1)
                .startsWith("%PDF"), "Le navigateur doit enregistrer un PDF, pas du texte");
        String disposition = reponse.getHeaders().getFirst("Content-Disposition");
        assertTrue(disposition.contains("attachment"), disposition);
        assertTrue(disposition.contains("CT-HIG-2026-007.pdf"),
                "Le nom du fichier enregistré est le numéro du contrat : " + disposition);
    }

    @Test
    void documentPdf_lePlanRecuParLeScriptVientDeLaBase_totauxEtAnnexe() {
        givenContractAvecUnBus();
        when(pdfService.render(any())).thenReturn(OCTETS_PDF);

        controller().documentPdf("ctr-1", role("ROLE_ADMIN"));

        Map<String, Object> plan = planTransmisAuScript();
        assertEquals("CT-HIG-2026-007", plan.get("number"), "Le numéro vient du serveur");
        assertTrue(plan.toString().contains("Transports du Sahel"));
        assertTrue(plan.toString().contains("LTSD1234567890123"), "L'annexe cite le châssis");
        assertTrue(plan.toString().contains("RC : B1234567"), "Le client est identifié pour le contrat");

        @SuppressWarnings("unchecked")
        List<List<String>> totaux = (List<List<String>>) ((Map<String, Object>) plan.get("totals")).get("rows");
        assertEquals("Total TTC", totaux.get(2).get(0), String.valueOf(totaux));
        // 250 000 HT + 19 % = 297 500 DT, calculé côté serveur et jamais saisi.
        assertTrue(totaux.get(2).get(1).contains("297"), String.valueOf(totaux));
        assertTrue(((String) ((Map<String, Object>) plan.get("totals")).get("words"))
                .contains("cinq cents dinars"), "La somme en toutes lettres épelle le TTC");
    }

    @Test
    void documentPdf_consultation_lePlanTransmisNeContientAucunMontant() {
        givenContractAvecUnBus();
        when(pdfService.render(any())).thenReturn(OCTETS_PDF);

        controller().documentPdf("ctr-1", role("ROLE_CONSULTATION"));

        Map<String, Object> plan = planTransmisAuScript();
        Map<String, Object> totals = (Map<String, Object>) plan.get("totals");
        assertEquals(List.of(), totals.get("rows"), "Pas de ligne de total, pas de total caché");
        assertEquals("", totals.get("words"), "Le contrat consultable n'arrête aucune somme");

        @SuppressWarnings("unchecked")
        List<String> colonnes = (List<String>) ((Map<String, Object>) plan.get("annex")).get("columns");
        assertFalse(colonnes.contains("Prix unit. HT"), String.valueOf(colonnes));
        assertFalse(plan.toString().contains("250"), "Le prix unitaire ne quitte pas le serveur");
        assertFalse(plan.toString().contains("297"));
        assertFalse(((String) plan.get("footer")).contains("dinars"), String.valueOf(plan.get("footer")));

        assertEquals("CT-HIG-2026-007", plan.get("number"), "Le numéro reste lisible");
        assertTrue(plan.toString().contains("Article 1"), "Le texte des clauses reste imprimable");
        assertTrue(plan.toString().contains("LTSD1234567890123"), "et l'identification du bus");
    }

    @Test
    void documentPdf_contratIntrouvable_renvoie404SansLancerPython() {
        when(contracts.findById("ctr-9")).thenReturn(Optional.empty());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().documentPdf("ctr-9", role("ROLE_ADMIN")));

        assertEquals(HttpStatus.NOT_FOUND, erreur.getStatusCode());
    }

    @Test
    void aiStatus_declareLeServiceIndisponible_sansCle() {
        Map<String, Object> status = controller().aiStatus();

        assertEquals(false, status.get("available"), "Angular grise le bouton sur cette valeur");
        assertEquals("qwen/qwen3.8-27b", status.get("model"));
    }

    @Test
    void draft_sansCle_renvoie503EtRienNEstEnregistre() {
        givenContractAvecUnBus();

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().draft("ctr-1", new DraftRequest("warranty"), jwt()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("GROQ_API_KEY"),
                "L'utilisateur doit savoir quoi faire : " + erreur.getReason());
    }

    @Test
    void draft_sectionInconnue_renvoyee400() {
        when(contracts.findById("ctr-1")).thenReturn(Optional.of(contrat()));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().draft("ctr-1", new DraftRequest("penalties"), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("Section de clause inconnue"), erreur.getReason());
    }
}
