package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Mise en page PDF du bon d'intervention, déléguée à {@code scripts/render_intervention_pdf.py}.
 *
 * <p>Deux scripts, deux poses de page : le contrat et le bon ne se ressemblent pas, et une
 * seule mise en page « fourre-tout » finirait par servir les deux pires documents. Le contenu
 * est décidé par {@link InterventionDocumentService}, y compris la question de savoir si la
 * version imprimée comporte ou non les lignes de paie.</p>
 *
 * <p>Clé de configuration propre ({@code app.depannage.pdf-script}) : on peut changer la
 * largeur d'un bon sans toucher au contrat.</p>
 */
@Service
public class InterventionPdfService extends PdfScriptLauncher {

    public InterventionPdfService(
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.depannage.pdf-script:./scripts/render_intervention_pdf.py}") String scriptPath,
            @Value("${app.depannage.pdf-timeout-seconds:30}") int timeoutSeconds) {
        super(pythonExecutable, scriptPath, timeoutSeconds, "intervention-");
    }

    /** Le plan du bon d'intervention en octets PDF. */
    public byte[] render(Map<String, Object> payload) {
        return renderPdf(payload);
    }
}
