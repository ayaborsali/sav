package tn.higer.sav_backend.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.service.AgPaperAssemblyService;
import tn.higer.sav_backend.service.AgPaperDocumentService;
import tn.higer.sav_backend.service.AgPaperPdfService;
import tn.higer.sav_backend.service.AuditService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Le dossier d'appel en garantie, relu avec ses justificatifs.
 *
 * Deux entrées, en lecture seule :
 *  - {@code GET /api/sav-cases/{id}/papier-ag} — les sorties qui répondent au
 *    dossier, les pièces posées qu'il réclame au constructeur, et la liste de ce qui
 *    reste à écrire avant de déposer le papier ;
 *  - {@code GET /api/sav-cases/{id}/papier-ag.pdf} — la même lecture, posée sur une
 *    feuille A4 que le guichet peut joindre à sa demande.
 *
 * Le papier AG lui-même continue de se lire et de s'écrire avec le dossier
 * ({@code SavCaseResponse.agPaper}, {@code PUT /api/sav-cases/{id}}) : aucune de ces
 * deux entrées n'ajoute une case à saisir, elles montrent ce que le dossier sait
 * déjà sans que personne ne l'ait ressaisi.
 *
 * Le PDF passe par le même contrôle que la lecture à l'écran — guichet et atelier
 * seuls — et son édition est journalisée : un papier qui part chez Higer engage la
 * maison, il se sait.
 */
@RestController
@RequestMapping("/api/sav-cases")
public class AgPaperController {

    private final AgPaperAssemblyService papier;
    private final AgPaperDocumentService documentService;
    private final AgPaperPdfService pdfService;
    private final AuditService audit;

    public AgPaperController(AgPaperAssemblyService papier, AgPaperDocumentService documentService,
                             AgPaperPdfService pdfService, AuditService audit) {
        this.papier = papier;
        this.documentService = documentService;
        this.pdfService = pdfService;
        this.audit = audit;
    }

    @GetMapping("/{savCaseId}/papier-ag")
    public AgPapierJustifie relire(@PathVariable String savCaseId, Authentication auth) {
        return papier.relire(savCaseId, auth);
    }

    /**
     * Le dossier de l'appel en garantie en PDF, prêt à joindre à la demande envoyée
     * au constructeur. Il est reconstruit intégralement depuis la base à chaque
     * demande : il ne peut pas rester en retard sur le dossier. Les cases vides y
     * restent vides et sont dites en tête de page — un papier incomplet doit se voir
     * sur la feuille, pas seulement à l'écran.
     */
    @GetMapping("/{savCaseId}/papier-ag.pdf")
    public ResponseEntity<byte[]> papierPourHiger(@PathVariable String savCaseId,
                                                 @AuthenticationPrincipal Jwt jwt,
                                                 Authentication auth) {
        Map<String, Object> plan = documentService.payload(savCaseId, auth);
        byte[] pdf = pdfService.render(plan);
        String number = String.valueOf(plan.getOrDefault("number", savCaseId));
        audit.record("DOWNLOAD", "SAV_CASE", savCaseId,
                "Édition du papier d'appel en garantie " + number + " pour le constructeur", jwt);
        String filename = number + "-appel-en-garantie.pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }
}
