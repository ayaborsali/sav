package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Mise en page PDF du papier d'appel en garantie, déléguée à
 * {@code scripts/render_claim_pdf.py}.
 *
 * <p>Troisième script, après le contrat et le bon d'intervention, et il a sa raison
 * d'être propre : une feuille de remboursement constructeur se remplit case par case
 * dans l'ordre du document Higer et porte la liste de ses manques en tête de page.
 * Aucun des deux autres gabarits ne pose ça.</p>
 *
 * <p>Le contenu est décidé par {@link AgPaperDocumentService} ; ici on ne fait
 * qu'appeler la mise en page, avec ses propres clés de configuration pour pouvoir
 * changer la pose d'un papier sans toucher au bon ni au contrat.</p>
 */
@Service
public class AgPaperPdfService extends PdfScriptLauncher {

    public AgPaperPdfService(
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.sav.claim-pdf-script:./scripts/render_claim_pdf.py}") String scriptPath,
            @Value("${app.sav.claim-pdf-timeout-seconds:30}") int timeoutSeconds) {
        super(pythonExecutable, scriptPath, timeoutSeconds, "papier-ag-");
    }

    /** Le plan du papier AG en octets PDF. */
    public byte[] render(Map<String, Object> payload) {
        return renderPdf(payload);
    }
}
