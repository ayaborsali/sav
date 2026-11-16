package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.InterventionDocumentService;
import tn.higer.sav_backend.service.InterventionPdfService;
import tn.higer.sav_backend.service.InterventionService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La branche HTTP du bon d'intervention : le contrôleur ne décide rien, il nomme le fichier et
 * journalise l'édition. Ces deux points valent le test parce que c'est par eux qu'un chef
 * d'atelier retrouve PLUS TARD la trace d'un bon interne imprimé — et parce que le nom du
 * fichier est ce que le technicien garde dans son dossier papier.
 */
@ExtendWith(MockitoExtension.class)
class InterventionDocumentTest {

    @Mock private InterventionService service;
    @Mock private InterventionDocumentService documentService;
    @Mock private InterventionPdfService pdfService;
    @Mock private AuditService audit;

    private static final String ITV = "itv-1";
    private static final byte[] OCTETS_PDF = "%PDF-1.7 test".getBytes();

    private InterventionController controller() {
        return new InterventionController(service, documentService, pdfService, audit);
    }

    private static Authentication role(String authority) {
        return new UsernamePasswordAuthenticationToken("x@y.tn", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject("usr-1").claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private void planEtPdf(Map<String, Object> plan) {
        when(documentService.payload(eq(ITV), any(), any(), any())).thenReturn(plan);
        when(pdfService.render(plan)).thenReturn(OCTETS_PDF);
    }

    @Test
    void leBonClientEstUnFichierAttacheNommeAvecLeNumeroDeLaSortie() {
        planEtPdf(Map.of("number", "DP-2026-007"));

        ResponseEntity<byte[]> reponse = controller()
                .document(ITV, "client", jwt(), role("ROLE_CHEF_ATELIER"));

        assertTrue(new String(reponse.getBody(), StandardCharsets.ISO_8859_1).startsWith("%PDF"),
                "le navigateur doit enregistrer un PDF, pas du texte");
        assertTrue(MediaType.APPLICATION_PDF.equals(reponse.getHeaders().getContentType()),
                String.valueOf(reponse.getHeaders().getContentType()));
        String disposition = reponse.getHeaders().getFirst("Content-Disposition");
        assertTrue(disposition.contains("attachment"), disposition);
        assertTrue(disposition.contains("DP-2026-007-bon-client.pdf"),
                "une copie client et une copie interne ne doivent pas porter le même nom : " + disposition);
        verify(audit).record(eq("DOWNLOAD"), eq("INTERVENTION"), eq(ITV),
                contains("sans montant"), any());
    }

    @Test
    void leBonInterneEstJournaliseCommeComportantLaPrime() {
        planEtPdf(Map.of("number", "DP-2026-007"));

        ResponseEntity<byte[]> reponse = controller()
                .document(ITV, "interne", jwt(), role("ROLE_ADMIN"));

        assertTrue(reponse.getHeaders().getFirst("Content-Disposition").contains("DP-2026-007-bon-interne.pdf"),
                reponse.getHeaders().getFirst("Content-Disposition"));
        verify(audit).record(eq("DOWNLOAD"), eq("INTERVENTION"), eq(ITV),
                contains("version interne"), any());
    }
}
