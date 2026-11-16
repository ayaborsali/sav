package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La rédaction IA est désormais partagée : le script Python (scripts/draft_clause.py) parle au
 * modèle, Java garde la décision. Ces tests vérifient les garde-fous côté Java — sans clé, sans
 * Python, sans script, réponse refusée — plus le nettoyage du texte par le vrai script Python,
 * hors ligne et sans appel réseau (--clean-only).
 */
class GroqDraftServiceTest {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String SCRIPT = "./scripts/draft_clause.py";

    private static GroqDraftService service(String apiKey, int perMinute) {
        return new GroqDraftService(true, apiKey, URL, "qwen/qwen3.8-27b", 0.3, 900, 5, perMinute,
                "python", SCRIPT);
    }

    private static ResponseStatusException rejects(GroqDraftService service, String text) {
        return assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "validate", text));
    }

    @Test
    void sansCle_leServiceEstIndisponibleEtLeResteDit() {
        GroqDraftService off = service("", 8);

        assertFalse(off.available(), "Le bouton IA doit se griser sur la foi du serveur");
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> off.draft("usr-1", "Rédige la clause de garantie", "Client : Transports du Sahel"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "503 : ce n'est pas une erreur utilisateur, c'est une configuration manquante");
        assertTrue(erreur.getReason().contains("GROQ_API_KEY"), erreur.getReason());
    }

    @Test
    void enabledFaux_malgreLaCle_aucunProcessusNestLance() {
        GroqDraftService off = new GroqDraftService(false, "sk-cle-quelconque", URL, "qwen/qwen3.8-27b",
                0.3, 900, 5, 8, "python", SCRIPT);

        assertFalse(off.available());
    }

    @Test
    void modele_remontePourLaTracabilite() {
        assertEquals("qwen/qwen3.8-27b", service("sk-test", 8).model());
    }

    @Test
    void scriptIntrouvable_renvoie503SansLancerRien() {
        GroqDraftService on = new GroqDraftService(true, "sk-test", URL, "qwen/qwen3.8-27b",
                0.3, 900, 5, 8, "python", "./scripts/inexistant.py");

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> on.draft("usr-1", "Rédige la clause d'objet", "Client : Transports du Sahel"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("scripts/inexistant.py"),
                "Le message doit dire quel fichier manque : " + erreur.getReason());
    }

    @Test
    void pythonAbsent_renvoie503EtNommeLaCommande() {
        GroqDraftService on = new GroqDraftService(true, "sk-test", URL, "qwen/qwen3.8-27b",
                0.3, 900, 5, 8, "python-dans-le-leur", SCRIPT);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> on.draft("usr-1", "Rédige la clause d'objet", "Client : Transports du Sahel"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "Python manquant = configuration, pas une panne du contrat");
        assertTrue(erreur.getReason().contains("app.python.executable"), erreur.getReason());
        assertFalse(erreur.getReason().contains("sk-test"),
                "Aucun message ne doit contenir la clé : " + erreur.getReason());
    }

    @Test
    void valide_rejetteToutCeQuiChiffreUneObligation() {
        GroqDraftService on = service("sk-test", 8);

        assertEquals(HttpStatus.BAD_GATEWAY, rejects(on, "Le prix convenu est de 150000 DT.").getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY, rejects(on, "Un acompte de 30 % est dû.").getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY, rejects(on, "Livraison le 15/11/2026.").getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY, rejects(on, "Pénalité de 1.500 dinars.").getStatusCode());
        assertEquals(HttpStatus.BAD_GATEWAY, rejects(on, "   ").getStatusCode(),
                "Un texte vide n'est pas une proposition");
    }

    @Test
    void valide_laissePasserLeTexteDeClauseEtLesNombresNonMonetaires() {
        GroqDraftService on = service("sk-test", 8);

        String clause = "Les véhicules sont garantis vingt-quatre mois à compter de la mise en service.";
        assertEquals(clause, ReflectionTestUtils.invokeMethod(on, "validate", clause));
        // Une année ou un renvoi d'article ne sont pas des montants : rien à bloquer.
        ReflectionTestUtils.invokeMethod(on, "validate",
                "Conformément à l'article 3, le contrat est conclu pour l'exercice 2026.");
    }

    @Test
    void enveloppe_duScriptDevientUnCodeHttp() {
        GroqDraftService on = service("sk-test", 8);

        assertEquals("Clause acceptée.",
                ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": true, \"text\": \"Clause acceptée.\"}", 0, ""));

        ResponseStatusException vide = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"empty\", \"error\": \"aucun texte\"}", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, vide.getStatusCode());

        ResponseStatusException config = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"config\", \"error\": \"GROQ_API_KEY absente\"}", 1, ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, config.getStatusCode(),
                "Une configuration manquante ne doit pas être présentée comme une panne");

        ResponseStatusException illisible = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "Traceback (du script)", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, illisible.getStatusCode());
    }

    @Test
    void quota_glisse_uneFenetreDuneMinuteParUtilisateur() {
        GroqDraftService on = service("sk-test", 2);

        ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-1");
        ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-1");
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-1"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, erreur.getStatusCode());
        // Un autre utilisateur n'écope pas du quota du premier.
        ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-2");
    }

    /**
     * Vrai test d'intégration du côté Python : on lance le script en mode --clean-only (aucun
     * appel réseau) et on vérifie qu'il rend bien le texte de clause seul.
     */
    @Test
    void leScriptPython_nettoieLeRaisonnementDuModele() throws Exception {
        Assumptions.assumeTrue(pythonPresent(), "Python 3 absent de cette machine");

        String brut = "Je dois rédiger une clause sans chiffrer.\n"
                + "</think>\n\n  La   livraison est convenue au dépôt du client,\n"
                + "  selon les termes habituels.  ";
        String sortie = lanceScript("--clean-only", brut);
        Map<String, Object> envelope = envelope(sortie);

        assertEquals(true, envelope.get("ok"), sortie);
        assertEquals("La livraison est convenue au dépôt du client, selon les termes habituels.",
                envelope.get("text"));
    }

    @Test
    void leScriptPython_refuseSansCleSansAppelerLeReseau() throws Exception {
        Assumptions.assumeTrue(pythonPresent(), "Python 3 absent de cette machine");

        String demande = "{\"instruction\":\"Rédige la clause d'objet\",\"facts\":\"Client : Test\","
                + "\"model\":\"qwen/qwen3.8-27b\",\"base_url\":\"http://127.0.0.1:9/chat\"}";
        // Clé vide volontaire : le script doit refuser sans toucher au réseau (le base_url
        // mensonger ne serait jamais utilisé).
        ProcessBuilder builder = new ProcessBuilder("python", SCRIPT);
        builder.environment().put("GROQ_API_KEY", "");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        try (OutputStream out = process.getOutputStream()) {
            out.write(demande.getBytes(StandardCharsets.UTF_8));
        }
        String sortie = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        Map<String, Object> envelope = envelope(sortie);

        assertEquals(false, envelope.get("ok"));
        assertEquals("config", envelope.get("kind"),
                "Sans clé, le script doit le dire et ne rien appeler : " + sortie);
        assertFalse(sortie.contains("Traceback"), sortie);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(String json) {
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new AssertionError("Le script n'a pas renvoyé de JSON : « " + json + " »", e);
        }
    }

    private static String lanceScript(String argument, String entree) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("python", SCRIPT, argument);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        try (OutputStream out = process.getOutputStream()) {
            out.write(entree.getBytes(StandardCharsets.UTF_8));
        }
        String sortie = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Le script Python ne s'est pas terminé");
        return sortie;
    }

    private static boolean pythonPresent() {
        try {
            Process probe = new ProcessBuilder("python", "--version").start();
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
