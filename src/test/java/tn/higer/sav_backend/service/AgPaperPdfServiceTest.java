package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le pont Java -> Python du papier d'appel en garantie : mêmes règles que le contrat et
 * le bon (script absent = 503 qui nomme le fichier, Python absent = 503 qui nomme la
 * commande, panne du script = 502), plus la particularité de cette feuille — elle doit
 * rendre avec SA liste de manques imprimée en tête, et rendre aussi quand la liste est
 * vide. Une page qui casse sur un dossier complet serait le pire des deux : c'est
 * précisément le papier qui part chez le constructeur.
 *
 * Les plans ci-dessous reprennent la forme exacte que {@link AgPaperDocumentService}
 * produit, note de rubrique comprise : si le service change de clé, ce test tombe avant
 * que l'atelier ne découvre un document amputé.
 */
class AgPaperPdfServiceTest {

    private static final String SCRIPT = "./scripts/render_claim_pdf.py";

    private static AgPaperPdfService service(String python, String script) {
        return new AgPaperPdfService(python, script, 25);
    }

    /** Le plan d'un papier encore incomplet : deux rubriques, une sortie, une pièce, des manques. */
    private static Map<String, Object> feuilleIncomplete() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("brand", "Saphir Véhicules et Automobiles");
        plan.put("subtitle", "Appel en garantie — demande de remboursement au constructeur");
        plan.put("number", "SAV-2026-007");
        plan.put("date_label", "Édité le 28/10/2026");
        plan.put("badge", "Demande « À déposer » — aucun montant n'est calculé par le portail.");
        plan.put("boxes", List.of(
                Map.of("heading", "Qui écrit le papier, et sur quel ordre", "rows",
                        List.of(List.of("Nom du rédacteur", "Mounir Bouazizi"),
                                List.of("N° OR", "OR-2026-041"),
                                List.of("Date d'OR", "12/10/2026"))),
                Map.of("heading", "Le bus chez Higer", "rows",
                        List.of(List.of("Importateur", "—"), List.of("N° distributeur", "—"),
                                List.of("Unit No.", "—"), List.of("CAM N°", "—")),
                        "note", "Les quatre codes ci-dessus sont ceux que le constructeur "
                                + "attribue : tant qu'il ne les a pas donnés, la case reste vide.")));
        plan.put("tables", List.of(Map.of(
                "heading", "Les sorties qui répondent à cet appel (1 ligne(s))",
                "columns", List.of("Sortie", "Où en est la sortie", "Signalée -> clôturée",
                        "Compteurs relevés sur le bus", "Diagnostic lu devant le bus",
                        "Papier d'atelier"),
                "weights", List.of(0.9, 1.1, 1.5, 1.3, 1.7, 1.0),
                "rows", List.of(List.of("DP-2026-007", "clôturée",
                        "08/10/2026 -> 12/10/2026", "148 320 km -> 148 405 km",
                        "code : 2115U • pièce : Alternateur", "OR-2026-041")),
                "note", "Aucune sortie ne répond à ce dossier.")));
        plan.put("texts", List.of(Map.of("heading", "Cause",
                "body", "Défaut de fabrication du régulateur intégré.\nContrôle fait au banc.")));
        plan.put("manquants", List.of(
                "Le montant demandé au constructeur reste vide. Il s'écrit au guichet : le portail "
                        + "ne le calcule pas, le barème de remboursement de Higer n'ayant jamais "
                        + "été fourni.",
                "La pièce 37300-10300 (Alternateur 28V 120A) n'est pas repartie chez le "
                        + "constructeur (aucun motif écrit)."));
        plan.put("manquants_heading", "CE QUI RESTE À ÉCRIRE AVANT DE DÉPOSER CE PAPIER");
        plan.put("signes", List.of("Les codes que seul le constructeur détient sont encore vides."));
        plan.put("signes_heading", "À REGARDER AVANT D'ENVOYER CE PAPIER");
        plan.put("signatures", List.of(
                Map.of("name", "Mounir Bouazizi",
                        "label", "Le rédacteur du papier — cases relues avant envoi"),
                Map.of("name", "Saphir Véhicules et Automobiles",
                        "label", "Le chef d'atelier — justificatifs joints, demande déposée")));
        plan.put("footer", "Saphir Véhicules et Automobiles — le portail compte des lignes et "
                + "nomme des cases vides, il ne calcule aucun montant.");
        return plan;
    }

    /** Le même papier, plus aucune liste : la feuille doit rester imprimable. */
    private static Map<String, Object> feuilleComplete() {
        Map<String, Object> plan = new LinkedHashMap<>(feuilleIncomplete());
        plan.put("manquants", List.of());
        plan.put("signes", List.of());
        plan.put("badge", "Rien ne manque aux cases — c'est un humain qui signe le dépôt.");
        return plan;
    }

    @Test
    void scriptIntrouvable_renvoie503EtNommeLeFichier() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python", "./scripts/papier-inexistant.py").render(feuilleIncomplete()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "Un script absent est une configuration, pas une panne du dossier");
        assertTrue(erreur.getReason().contains("papier-inexistant.py"), erreur.getReason());
    }

    @Test
    void pythonAbsent_renvoie503EtNommeLaCommande() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python-dans-le-leur", SCRIPT).render(feuilleIncomplete()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("app.python.executable"),
                "L'administrateur doit savoir quelle clé régler : " + erreur.getReason());
    }

    /** Une liste de manques vide n'est pas une erreur : le dossier complet s'imprime aussi. */
    @Test
    void leVraiScriptProduitLaFeuilleIncomplete() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        assertEstUnPdf(service("python", SCRIPT).render(feuilleIncomplete()));
    }

    @Test
    void leVraiScriptProduitLaFeuilleComplete() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        assertEstUnPdf(service("python", SCRIPT).render(feuilleComplete()));
    }

    /**
     * Les clés du plan sont le contrat entre Java et le script : si l'une disparaît, la
     * feuille perd une partie de sa règle sans que personne ne voie rien à l'écran.
     */
    @Test
    void lesCleesDuPlanSontCellesQueLeScriptLit() {
        List<String> attendues = new ArrayList<>(List.of("brand", "subtitle", "number",
                "date_label", "badge", "boxes", "tables", "texts", "manquants",
                "manquants_heading", "signes", "signes_heading", "signatures", "footer"));
        List<String> rendues = new ArrayList<>(feuilleIncomplete().keySet());

        assertEquals(attendues, rendues, "le service et le script ne parlent plus du même document");
    }

    @Test
    void enveloppe_duScriptDevientUnCodeHttp() {
        AgPaperPdfService on = service("python", SCRIPT);

        ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "{\"ok\": true, \"bytes\": 6800}", 0, "");

        ResponseStatusException config = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"config\", \"error\": \"reportlab est absent\"}",
                        1, ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, config.getStatusCode());
        assertTrue(config.getReason().contains("reportlab"), config.getReason());

        ResponseStatusException casse = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"internal\", \"error\": \"mise en page échouée\"}",
                        1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, casse.getStatusCode());
    }

    private static void assertEstUnPdf(byte[] pdf) {
        assertTrue(pdf.length > 1000, "une feuille A4 avec deux rubriques fait plusieurs kilo-octets : "
                + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "Signature PDF attendue");
        String queue = new String(pdf, pdf.length - 64, 64, StandardCharsets.ISO_8859_1);
        assertTrue(queue.contains("%%EOF"), "le fichier doit être terminé, pas tronqué : " + queue);
    }

    private static boolean reportlabPresent() {
        try {
            Process probe = new ProcessBuilder("python", "-c", "import reportlab").start();
            boolean ok = probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0;
            probe.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
