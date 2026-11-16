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
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.service.AgPaperAssemblyService;
import tn.higer.sav_backend.service.AgPaperDocumentService;
import tn.higer.sav_backend.service.AgPaperPdfService;
import tn.higer.sav_backend.service.AuditService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La branche HTTP du papier d'appel en garantie : le contrôleur ne décide rien, il
 * nomme le fichier et journalise l'édition. Ces deux points valent le test parce que
 * c'est par eux qu'on retrouve plus tard QUI a envoyé quoi chez le constructeur —
 * un remboursement se discute des mois après le dépôt.
 */
@ExtendWith(MockitoExtension.class)
class AgPaperDocumentTest {

    @Mock private AgPaperAssemblyService papier;
    @Mock private AgPaperDocumentService documentService;
    @Mock private AgPaperPdfService pdfService;
    @Mock private AuditService audit;

    private static final String SAV = "sav-1";
    private static final String NUMERO = "SAV-2026-007";
    private static final byte[] OCTETS_PDF = "%PDF-1.7 papier".getBytes(StandardCharsets.US_ASCII);

    private AgPaperController controller() {
        return new AgPaperController(papier, documentService, pdfService, audit);
    }

    private static Authentication role(String authority) {
        return new UsernamePasswordAuthenticationToken("x@y.tn", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    /** Un seul et même {@code Jwt} pour la stub et la vérification : {@code Jwt} n'est pas comparable. */
    private final Jwt jeton = Jwt.withTokenValue("jeton").header("alg", "HS256")
            .subject("usr-1").claim("email", "guichet@saphir.tn")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();

    @Test
    void laFeuillePourHigerEstUnFichierAttacheNommeAvecLeNumeroDuDossier() {
        Authentication chef = role("ROLE_CHEF_ATELIER");
        Map<String, Object> plan = Map.of("number", NUMERO);
        when(documentService.payload(SAV, chef)).thenReturn(plan);
        when(pdfService.render(plan)).thenReturn(OCTETS_PDF);

        ResponseEntity<byte[]> reponse = controller().papierPourHiger(SAV, jeton, chef);

        assertTrue(new String(reponse.getBody(), StandardCharsets.ISO_8859_1).startsWith("%PDF"),
                "le navigateur doit enregistrer un PDF, pas du texte");
        assertTrue(MediaType.APPLICATION_PDF.equals(reponse.getHeaders().getContentType()),
                String.valueOf(reponse.getHeaders().getContentType()));
        String disposition = reponse.getHeaders().getFirst("Content-Disposition");
        assertTrue(disposition.contains("attachment"), disposition);
        assertTrue(disposition.contains(NUMERO + "-appel-en-garantie.pdf"), disposition);
        verify(audit).record(eq("DOWNLOAD"), eq("SAV_CASE"), eq(SAV),
                contains("constructeur"), eq(jeton));
    }

    /**
     * Un papier édité mais jamais déposé reste une trace : la journalisation porte le
     * numéro du dossier, pas un nom de fichier que l'atelier a pu renommer ensuite.
     */
    @Test
    void leNumeroDuDossierEstCeluiQuiParaitDansLeJournal() {
        Authentication guichet = role("ROLE_GESTIONNAIRE_SAV");
        when(documentService.payload(SAV, guichet)).thenReturn(Map.of("number", NUMERO));
        when(pdfService.render(any())).thenReturn(OCTETS_PDF);

        controller().papierPourHiger(SAV, jeton, guichet);

        verify(audit).record(eq("DOWNLOAD"), eq("SAV_CASE"), eq(SAV), contains(NUMERO), eq(jeton));
    }

    /** La lecture à l'écran reste ouverte, séparée du PDF : le bloc se relit sans mise en page. */
    @Test
    void laLectureSeuleNeDepassePasDuDossierGaranties() {
        Authentication chef = role("ROLE_CHEF_ATELIER");
        AgPapierJustifie lu = new AgPapierJustifie(NUMERO, List.of(), List.of(),
                List.of("La correction faite ne s'écrit nulle part."), List.of(), false);
        when(papier.relire(SAV, chef)).thenReturn(lu);

        assertEquals(lu, controller().relire(SAV, chef));
    }
}
