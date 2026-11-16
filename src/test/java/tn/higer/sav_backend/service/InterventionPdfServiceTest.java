package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le pont Java -> Python du bon d'intervention : mêmes règles que le contrat (script absent =
 * 503 qui nomme le fichier, dépendance Python absente = 503 qui nomme la commande, panne du
 * script = 502), et surtout le vrai script doit rendre les DEUX versions du document. Une page
 * qui casse sur la version interne serait un faux contrôle : c'est celle que le chef d'atelier
 * imprime pour payer.
 */
class InterventionPdfServiceTest {

    private static final String SCRIPT = "./scripts/render_intervention_pdf.py";

    private static InterventionPdfService service(String python, String script) {
        return new InterventionPdfService(python, script, 20);
    }

    /** Un plan complet, dans la forme exacte que InterventionDocumentService produit. */
    private static Map<String, Object> planClient() {
        return Map.of(
                "brand", "Saphir Véhicules et Automobiles",
                "subtitle", "Bon d'intervention — dépannage sur site",
                "number", "DP-2026-007",
                "date_label", "Édité le 19/10/2026",
                "badge", "Version client — constat de l'intervention, aucun montant n'y figure.",
                "boxes", List.of(
                        Map.of("heading", "Bus en panne", "rows",
                                List.of(List.of("Client", "Transports du Sahel"),
                                        List.of("Châssis (VIN)", "LTSD45H32D0000123"))),
                        Map.of("heading", "Équipe et véhicule de service", "rows",
                                List.of(List.of("Technicien intervenu", "Ali Trabelsi"),
                                        List.of("Distance parcourue", "58 km")))),
                "tables", List.of(timingsTable()),
                "texts", List.of(Map.of("heading", "Diagnostic constaté sur place",
                        "body", "Code défaut : P0217\nSurchauffe, ventilateur non alimenté.")),
                "signatures", List.of(
                        Map.of("name", "Ali Trabelsi", "label", "Le technicien — intervention réalisée"),
                        Map.of("name", "Transports du Sahel", "label", "Le client — bus repris, lu et approuvé")),
                "footer", "Saphir Véhicules et Automobiles — document généré automatiquement.");
    }

    /** Les huit étapes tiennent sur une page : c'est la table qui porte tout le contrôle. */
    private static Map<String, Object> timingsTable() {
        return Map.of(
                "heading", "Horodatages enregistrés par le serveur",
                "columns", List.of("Étape", "Date et heure", "Durée déduite"),
                "weights", List.of(1.5, 1.15, 1.0),
                "rows", List.of(
                        List.of("Départ de l'atelier", "19/10/2026 à 08h00", "—"),
                        List.of("Retour à l'atelier", "19/10/2026 à 10h06", "Sortie : 2 h 06")),
                "note", "Aucune étape horodatée.");
    }

    /** La version interne ajoute les preuves et la prime, et une troisième case à signer. */
    private static Map<String, Object> planInterne() {
        Map<String, Object> base = new java.util.LinkedHashMap<>(planClient());
        base.put("badge", "VERSION INTERNE — ce document comporte la prime validée.");
        base.put("tables", List.of(
                timingsTable(),
                Map.of("heading", "Preuves photographiques au dossier",
                        "columns", List.of("Ce que la photo doit montrer", "Clichés versés"),
                        "weights", List.of(1.4, 1.0),
                        "rows", List.of(List.of("avant intervention", "1 cliché"),
                                List.of("compteur", "aucun cliché"))),
                Map.of("heading", "Prime d'intervention — interne",
                        "columns", List.of("Élément", "Valeur"),
                        "weights", List.of(1.0, 1.6),
                        "rows", List.of(List.of("Montant de la prime", "55,000 DT"),
                                List.of("Signaux au moment du calcul",
                                        "photos de preuve manquantes : compteur")))));
        base.put("signatures", List.of(
                Map.of("name", "Ali Trabelsi", "label", "Le technicien"),
                Map.of("name", "Transports du Sahel", "label", "Le client"),
                Map.of("name", "Saphir Véhicules et Automobiles", "label", "Le chef d'atelier")));
        return base;
    }

    @Test
    void scriptIntrouvable_renvoie503EtNommeLeFichier() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python", "./scripts/inexistant.py").render(planClient()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "Un script absent est une configuration, pas une panne de la sortie");
        assertTrue(erreur.getReason().contains("scripts/inexistant.py"), erreur.getReason());
    }

    @Test
    void pythonAbsent_renvoie503EtNommeLaCommande() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("python-dans-le-leur", SCRIPT).render(planClient()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("app.python.executable"),
                "L'administrateur doit savoir quelle clé régler : " + erreur.getReason());
    }

    @Test
    void enveloppe_duScriptDevientUnCodeHttp() {
        InterventionPdfService on = service("python", SCRIPT);

        ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "{\"ok\": true, \"bytes\": 900}", 0, "");

        ResponseStatusException config = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"config\", \"error\": \"reportlab est absent\"}", 1, ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, config.getStatusCode());
        assertTrue(config.getReason().contains("reportlab"), config.getReason());

        ResponseStatusException casse = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"internal\", \"error\": \"mise en page échouée\"}", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, casse.getStatusCode());
    }

    /** Un PDF de 0 octet venu du script est une anomalie, pas un document vide. */
    @Test
    void pdf_vide_renvoie502() throws Exception {
        Path vide = Files.createTempFile("intervention-test-", ".pdf");
        try {
            ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                    () -> ReflectionTestUtils.invokeMethod(service("python", SCRIPT), "readPdf", vide));
            assertEquals(HttpStatus.BAD_GATEWAY, erreur.getStatusCode());
        } finally {
            Files.deleteIfExists(vide);
        }
    }

    /** Vrai test d'intégration : le plan traverse le ProcessBuilder et reportlab écrit le fichier. */
    @Test
    void leVraiScriptProduitLeBonClient() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        byte[] pdf = service("python", SCRIPT).render(planClient());

        assertEstUnPdf(pdf);
    }

    /** La version interne (trois cases, deux tables de plus) doit tenir sur la page. */
    @Test
    void leVraiScriptProduitLeBonInterne() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");

        byte[] pdf = service("python", SCRIPT).render(planInterne());

        assertEstUnPdf(pdf);
    }

    private static void assertEstUnPdf(byte[] pdf) {
        assertTrue(pdf.length > 1000, "Un bon d'une page fait plusieurs kilo-octets : " + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "Signature PDF attendue");
        String queue = new String(pdf, pdf.length - 64, 64, StandardCharsets.ISO_8859_1);
        assertTrue(queue.contains("%%EOF"), "Le fichier doit être terminé, pas tronqué : " + queue);
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
