package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Mise en page PDF du contrat, déléguée au script {@code scripts/render_contract_pdf.py}.
 *
 * <p>Le plan vient de {@link ContractDocumentService} : il contient déjà les valeurs décidées
 * par le serveur (numéro, parties, clauses, annexe, totaux, somme en toutes lettres), y compris
 * le fait de retirer les montants quand le compte qui demande ne doit pas les voir. Ce service
 * ne fait que lancer Python et récupérer les octets — la mécanique commune aux documents PDF
 * (processus, enveloppe, fichier temporaire) vit dans {@link PdfScriptLauncher}.</p>
 */
@Service
public class ContractPdfService extends PdfScriptLauncher {

    public ContractPdfService(
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.pdf.script:./scripts/render_contract_pdf.py}") String scriptPath,
            @Value("${app.pdf.timeout-seconds:30}") int timeoutSeconds) {
        super(pythonExecutable, scriptPath, timeoutSeconds, "contrat-");
    }

    /** Le plan du contrat en octets PDF. */
    public byte[] render(Map<String, Object> payload) {
        return renderPdf(payload);
    }
}
