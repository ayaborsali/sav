package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.BonLivraisonService;
import tn.higer.sav_backend.service.DevisService;
import tn.higer.sav_backend.service.MaintenanceAttachmentService;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceDocumentService;
import tn.higer.sav_backend.service.MaintenancePdfService;
import tn.higer.sav_backend.service.OrdreService;
import tn.higer.sav_backend.service.SoldPlanService;
import tn.higer.sav_backend.repository.MaintenanceRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La route qui rend le papier téléchargeable. Deux choses se vérifient ici, et ce sont les deux
 * que l'atelier regarde : le nom du fichier qu'il range dans son dossier, et la trace laissée
 * quand le papier sort de la maison.
 *
 * <p>Le nom suit la <b>nature</b> du papier, jamais son numéro : un poste qui a gardé la
 * numérotation d'avant (« DV-… » sur un ordre) ne doit pas faire sortir un « devis ». La trace,
 * elle, dit si l'édition emportait des montants — c'est la question que pose un papier de
 * l'atelier quand il sort.</p>
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceDocumentPdfTest {

    @Mock
    private MaintenanceRepository maintenance;

    @Mock
    private SoldPlanService forfaits;

    @Mock
    private MaintenanceAttachmentService pieces;

    @Mock
    private MaintenanceChainService chaine;

    @Mock
    private DevisService devis;

    @Mock
    private OrdreService ordre;

    @Mock
    private BonLivraisonService bons;

    @Mock
    private MaintenanceDocumentService documentService;

    @Mock
    private MaintenancePdfService pdfService;

    @Mock
    private AuditService audit;

    private static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8);

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "atelier@saphir.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private MaintenanceController controller() {
        return new MaintenanceController(maintenance, forfaits, pieces, chaine, devis, ordre, bons,
                documentService, pdfService, audit);
    }

    /** Un plan dans la forme que MaintenanceDocumentService produit : nature, numéro, octets. */
    private Map<String, Object> plan(String type, String numero, String genre) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("type", type);
        plan.put("number", numero);
        plan.put("kind", genre);
        return plan;
    }

    @Test
    void leBonDeLivraisonSortSousSonNomEtSansMontant() {
        Map<String, Object> plan = plan("bl", "BL-2026-0094", "Bon de livraison");
        when(documentService.payload("maint-2")).thenReturn(plan);
        when(pdfService.render(plan)).thenReturn(PDF);

        var reponse = controller().document("maint-2", jwt());

        assertEquals(MediaType.APPLICATION_PDF, reponse.getHeaders().getContentType());
        String dispositions = reponse.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(dispositions.contains("BL-2026-0094-bon-de-livraison.pdf"), dispositions);
        assertArrayEquals(PDF, reponse.getBody());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("DOWNLOAD"), eq("MAINTENANCE"), eq("maint-2"), detail.capture(), any(Jwt.class));
        assertTrue(detail.getValue().contains("aucun montant"), detail.getValue());
        assertTrue(detail.getValue().contains("BL-2026-0094"), detail.getValue());
    }

    @Test
    void lordreDeTravailNeSortJamaisNommeDevis() {
        // Un vieux papier numéroté « DV-… » alors que c'est un ordre : c'est la nature qui gagne.
        Map<String, Object> plan = plan("operation", "DV-2026-0011", "Ordre de travail");
        when(documentService.payload("maint-3")).thenReturn(plan);
        when(pdfService.render(plan)).thenReturn(PDF);

        var reponse = controller().document("maint-3", jwt());

        String dispositions = reponse.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(dispositions.contains("DV-2026-0011-ordre-de-travail.pdf"), dispositions);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("DOWNLOAD"), eq("MAINTENANCE"), eq("maint-3"), detail.capture(), any(Jwt.class));
        assertTrue(detail.getValue().contains("aucun montant"), detail.getValue());
    }

    /** Le devis est le seul papier dont la trace dit qu'il emporte des montants. */
    @Test
    void leDevisSortAvecSesMontantsEtLaTraceLeDit() {
        Map<String, Object> plan = plan("devis", "DV-2026-0007", "Devis");
        when(documentService.payload("maint-1")).thenReturn(plan);
        when(pdfService.render(plan)).thenReturn(PDF);

        var reponse = controller().document("maint-1", jwt());

        String dispositions = reponse.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(dispositions.contains("DV-2026-0007-devis.pdf"), dispositions);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("DOWNLOAD"), eq("MAINTENANCE"), eq("maint-1"), detail.capture(), any(Jwt.class));
        assertTrue(detail.getValue().contains("avec les montants"), detail.getValue());
    }

    /**
     * Le plan traverse le contrôleur sans être touché : ce sont les cases du JSON qui décident de
     * l'argent, et un contrôleur qui en rajouterait une échapperait au test du service.
     */
    @Test
    void leControleurNeRetouchePasLePlan() {
        Map<String, Object> plan = plan("bl", "BL-2026-0094", "Bon de livraison");
        when(documentService.payload("maint-4")).thenReturn(plan);
        when(pdfService.render(any())).thenReturn(PDF);

        controller().document("maint-4", jwt());

        ArgumentCaptor<Map<String, Object>> passe = ArgumentCaptor.forClass(Map.class);
        verify(pdfService).render(passe.capture());
        assertEquals(plan, passe.getValue());
        assertTrue(!passe.getValue().containsKey("totals"), "le contrôleur n'invente pas de totaux");
    }

    /** La voie de la mise en page est réglée par la config, pas codée en dur. */
    @Test
    void leLanceurLitSaConfig() {
        MaintenancePdfService on = new MaintenancePdfService("python", "./scripts/autre.py", 45);

        assertEquals("./scripts/autre.py", ReflectionTestUtils.getField(on, "scriptPath"));
        assertEquals(45, ReflectionTestUtils.getField(on, "timeoutSeconds"));
    }
}
