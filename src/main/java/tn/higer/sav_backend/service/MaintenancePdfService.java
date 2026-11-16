package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Mise en page PDF des papiers de l'atelier, déléguée à {@code scripts/render_maintenance_pdf.py}.
 *
 * <p>Un script par sorte de papier, comme pour le contrat et le bon d'intervention : le devis
 * a des colonnes de prix, l'ordre de travail a des cases à cocher et des heures, le bon de
 * livraison a des cases à signer. Une seule mise en page « fourre-tout » rendrait mal les trois.</p>
 *
 * <p>Le contenu est décidé par {@link MaintenanceDocumentService}, y compris la question de
 * l'argent : ce service ne sait pas si un papier chiffre, il ne fait que poser sur la page ce
 * qu'on lui envoie.</p>
 */
@Service
public class MaintenancePdfService extends PdfScriptLauncher {

    public MaintenancePdfService(
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.maintenance.pdf-script:./scripts/render_maintenance_pdf.py}") String scriptPath,
            @Value("${app.maintenance.pdf-timeout-seconds:30}") int timeoutSeconds) {
        super(pythonExecutable, scriptPath, timeoutSeconds, "maintenance-");
    }

    /** Le plan du papier d'atelier en octets PDF. */
    public byte[] render(Map<String, Object> payload) {
        return renderPdf(payload);
    }
}
