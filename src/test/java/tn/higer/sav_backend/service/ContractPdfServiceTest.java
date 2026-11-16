package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le pont Java -> Python qui fabrique le fichier. Ce que ces tests protègent : les messages de
 * configuration (script ou Python absents = 503 lisible, jamais une stack trace), le fait que
 * l'enveloppe du script devienne le bon code HTTP, et surtout que le vrai script produise un
 * vrai PDF à partir du plan envoyé par le serveur. Aucun secret ne transite par ces tests.
 */
class ContractPdfServiceTest {

    private static final String SCRIPT = "./scripts/render_contract_pdf.py";

    private static ContractPdfService service(String python, String script) {
        return new ContractPdfService(python, script, 20);
    }

    /** Un plan complet, dans la forme exacte que ContractDocumentService produit. */
    private static Map<String, Object> plan() {
        return Map.of(
                "brand", "Saphir Véhicules et Automobiles",
                "subtitle", "Contrat de maintenance de véhicules",
                "number", "CT-HIG-2026-001",
                "date_label", "Date : 15/10/2026",
                "parties", List.of(
                        Map.of("heading", "Le prestataire", "lines", List.of("Saphir Véhicules et Automobiles", "RC : B0000000")),
                        Map.of("heading", "Le client", "lines", List.of("Transports du Sahel", "RC : B1234567"))),
                "clauses", List.of(Map.of("title", "Article 1 — Objet",
                        "body", "La maintenance préventive et corrective, assurée par le prestataire, du véhicule désigné à l'annexe.")),
                "annex", Map.of(
                        "heading", "Annexe — Véhicules entretenus",
                        "columns", List.of("Réf.", "Châssis (VIN)", "Forfait HT"),
                        "rows", List.of(List.of("1", "LTSD1234567890123", "250 000,000 DT")),
                        "note", "Aucun véhicule rattaché à ce contrat."),
                "totals", Map.of(
                        "rows", List.of(List.of("Total HT", "250 000,000 DT"),
                                List.of("TVA 19 %", "47 500,000 DT"),
                                List.of("Total TTC", "297 500,000 DT")),
                        "words", "Arrêté le présent contrat de maintenance à la somme de deux cent "
                                + "quatre-vingt-dix-sept mille cinq cents dinars."),
                "signatures", List.of(Map.of("name", "Saphir Véhicules et Automobiles", "label", "Le prestataire"),
                        Map.of("name", "Transports du Sahel", "label", "Le client (lu et approuvé)")),
                "footer", "Document généré automatiquement — Montants en dinars tunisiens (DT).");
    }

    @Test
    void scriptIntrouvable_renvoie503EtNommeLeFichier() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python", "./scripts/inexistant.py").render(plan()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "Un script absent est une configuration, pas une panne du contrat");
        assertTrue(erreur.getReason().contains("scripts/inexistant.py"), erreur.getReason());
    }

    @Test
    void pythonAbsent_renvoie503EtNommeLaCommande() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python-dans-le-leur", SCRIPT).render(plan()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("app.python.executable"),
                "L'administrateur doit savoir quelle clé régler : " + erreur.getReason());
    }

    @Test
    void enveloppe_duScriptDevientUnCodeHttp() {
        ContractPdfService on = service("python", SCRIPT);

        // ok = true : rien n'est levé, le PDF est censé être écrit.
        ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "{\"ok\": true, \"bytes\": 1200}", 0, "");

        ResponseStatusException config = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"config\", \"error\": \"reportlab est absent\"}", 1, ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, config.getStatusCode(),
                "Une dépendance Python manquante se corrige, elle n'est pas une panne réseau");
        assertTrue(config.getReason().contains("reportlab"), config.getReason());

        ResponseStatusException casse = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"internal\", \"error\": \"mise en page échouée\"}", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, casse.getStatusCode());

        ResponseStatusException illisible = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "Traceback (interne)", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, illisible.getStatusCode());
    }

    /** Le fichier doit être lu après écriture : un PDF vide venu du script est une anomalie. */
    @Test
    void pdf_vide_renvoie502() throws Exception {
        Path vide = Files.createTempFile("contrat-test-", ".pdf");
        try {
            ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            service("python", SCRIPT), "readPdf", vide));
            assertEquals(HttpStatus.BAD_GATEWAY, erreur.getStatusCode());
        } finally {
            Files.deleteIfExists(vide);
        }
    }

    /**
     * Vrai test d'intégration : le plan du serveur traverse le ProcessBuilder et le script
     * reportlab écrit le PDF. Désactivé si Python ou reportlab manque sur la machine.
     */
    @Test
    void leVraiScriptProduitUnVraiPdf() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        byte[] pdf = service("python", SCRIPT).render(plan());

        assertTrue(pdf.length > 1000, "Un contrat d'une page fait plusieurs kilo-octets : " + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "Signature PDF attendue");
        String queue = new String(pdf, pdf.length - 64, 64, StandardCharsets.ISO_8859_1);
        assertTrue(queue.contains("%%EOF"), "Le fichier doit être terminé, pas tronqué : " + queue);
    }

    /** Un contrat sans montant (rôle consultation) se met en page aussi : rien ne casse. */
    @Test
    void leVraiScriptProduitLePdfSansMontants() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        Map<String, Object> plan = plan();
        Map<String, Object> sansTotaux = new java.util.LinkedHashMap<>(plan);
        sansTotaux.put("totals", Map.of("rows", List.of(), "words", ""));
        sansTotaux.put("footer", "Document généré automatiquement.");

        byte[] pdf = service("python", SCRIPT).render(sansTotaux);

        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        assertFalse(pdf.length == 0);
    }

    /** Sonde : le script est-il capable de rendre ici ? (reportlab installé dans ce Python) */
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
