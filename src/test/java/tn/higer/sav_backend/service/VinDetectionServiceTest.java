package tn.higer.sav_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le pont Java -> Python qui lit le châssis sur une photo, et surtout le garde-fou serveur :
 * un numéro hors format ISO 3779 ne doit jamais ressortir comme un VIN recevable, même quand le
 * modèle l'affirme. Teste aussi l'enveloppe devenue code HTTP, le quota par utilisateur, et le
 * fait que le détail brut du fournisseur ne remonte pas à l'écran. Aucun secret dans ces tests.
 */
class VinDetectionServiceTest {

    private static final String SCRIPT = "./scripts/detect_vin.py";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static VinDetectionService service(String key, String python, String script,
                                               int perMinute, long maxBytes) {
        return new VinDetectionService(key, "qwen/qwen3.8-27b", "qwen/qwen3.8-27b",
                python, script, 20, maxBytes, perMinute);
    }

    private static VinDetectionService service() {
        return service("cle-de-test", "python", SCRIPT, 0, 3_500_000L);
    }

    // ------------------------------------------------------------- garde-fou sur le format

    private VinDetectionService.Outcome validate(String envelope) {
        try {
            return ReflectionTestUtils.invokeMethod(service(), "validate", JSON.readTree(envelope));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unVinCompleteValidePasse() {
        VinDetectionService.Outcome out =
                validate("{\"ok\":true,\"status\":\"complete\",\"vin\":\"LKLR1KSD5NC123456\"}");

        assertEquals("complete", out.status());
        assertEquals("LKLR1KSD5NC123456", out.vin());
        assertTrue(out.complete());
        assertNull(out.partial());
    }

    /** Le modèle peut affirmer un numéro faux : c'est le serveur qui décide, jamais le modèle. */
    @Test
    void unePropositionHorsFormatNeDevientJamaisUnVin() {
        VinDetectionService.Outcome tropCourt =
                validate("{\"ok\":true,\"status\":\"complete\",\"vin\":\"LKLR1KSD5NC12345\"}");
        assertEquals("partial", tropCourt.status());
        assertNull(tropCourt.vin(), "15 caractères ne peuvent pas pré-remplir le formulaire");

        // I, O et Q n'existent pas dans un VIN : la proposition est écartée telle quelle.
        VinDetectionService.Outcome lettresInterdites =
                validate("{\"ok\":true,\"status\":\"complete\",\"vin\":\"LKLR1KSD50C12345IO\"}");
        assertEquals("partial", lettresInterdites.status());
        assertNull(lettresInterdites.vin());
    }

    @Test
    void uneLecturePartielleRestePartielleEtUneAbsenceResteAucune() {
        VinDetectionService.Outcome partiel = validate(
                "{\"ok\":true,\"status\":\"partial\",\"partial\":\"LKLR1KSD5NC12\",\"read_chars\":14}");
        assertEquals("partial", partiel.status());
        assertEquals("LKLR1KSD5NC12", partiel.partial());
        assertEquals(14, partiel.readChars());

        VinDetectionService.Outcome aucun = validate("{\"ok\":true,\"status\":\"none\"}");
        assertEquals("none", aucun.status());
        assertFalse(aucun.complete());
    }

    // ---------------------------------------------------------------- codes de configuration

    @Test
    void scriptIntrouvable_renvoie503EtNommeLeFichier() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("cle-de-test", "python", "./scripts/inexistant_vin.py", 0, 3_500_000L)
                        .detect("usr-1", new byte[]{1, 2, 3}));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode(),
                "Un script absent est une configuration, pas une panne de l'IA");
        assertTrue(erreur.getReason().contains("inexistant_vin.py"), erreur.getReason());
    }

    @Test
    void sansCle_renvoie503EtDitOùLAjouter() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("", "python", SCRIPT, 0, 3_500_000L).detect("usr-1", new byte[]{1}));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("GROQ_API_KEY"), erreur.getReason());
        assertTrue(erreur.getReason().contains("saisie manuelle"),
                "L'utilisateur doit savoir qu'il peut toujours taper le VIN : " + erreur.getReason());
    }

    @Test
    void photoTropLourde_renvoie413AVantLappel() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service("cle-de-test", "python", SCRIPT, 0, 8L)
                        .detect("usr-1", new byte[9]));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("recadr"), erreur.getReason());
    }

    @Test
    void photoVide_renvoie400() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service().detect("usr-1", new byte[0]));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
    }

    // ------------------------------------------------------------- enveloppe -> code HTTP

    @Test
    void enveloppe_duScriptDevientUnCodeHttpSansFuite() {
        VinDetectionService on = service();

        // ok = true : l'enveloppe est retenue telle quelle.
        JsonNode bonne = ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                "{\"ok\": true, \"status\": \"complete\", \"vin\": \"LKLR1KSD5NC123456\"}", 0, "");
        assertEquals("complete", bonne.path("status").asText());

        ResponseStatusException config = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"config\", \"error\": \"cle refusee\"}", 1, ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, config.getStatusCode(),
                "Une clé ou un modèle incorrect se corrige dans le .env");

        ResponseStatusException amont = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope",
                        "{\"ok\": false, \"kind\": \"upstream\", \"error\": \"limite atteinte\","
                                + " \"detail\": \"org_0123456789 / jetons 7000\"}", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, amont.getStatusCode());
        assertFalse(amont.getReason().contains("org_0123456789"),
                "Le détail du fournisseur va dans les journaux, jamais à l'écran");

        ResponseStatusException illisible = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "parseEnvelope", "Traceback (interne)", 1, ""));
        assertEquals(HttpStatus.BAD_GATEWAY, illisible.getStatusCode());
    }

    @Test
    void quotaTroisLecturesParMinuteEtPasUneDePlus() {
        VinDetectionService on = service("cle-de-test", "python", SCRIPT, 2, 3_500_000L);

        ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-1");
        ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-1");
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-1"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, erreur.getStatusCode());
        // Un autre utilisateur n'est pas pénalisé par celui-ci.
        ReflectionTestUtils.invokeMethod(on, "checkQuota", "usr-2");
    }

    // ------------------------------------------------------------------ pont réel, hors réseau

    /**
     * Vrai test d'intégration sans réseau : le service lance le vrai script sur un fichier qui
     * n'est pas une image. Le script doit répondre une enveloppe JSON, et le serveur doit la
     * traduire en 502 lisible. Désactivé si Python 3 est absent de cette machine.
     */
    @Test
    void leVraiScriptRepondUneEnveloppeQueJavaTraduit() throws Exception {
        Assumptions.assumeTrue(pythonPresent(), "Python 3 absent de cette machine");

        Path fichier = Files.createTempFile("vin-test-", ".jpg");
        try {
            Files.write(fichier, "GIF89a pas une photo".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            VinDetectionService on = service("cle-de-test", "python", SCRIPT, 0, 3_500_000L);
            byte[] octets = Files.readAllBytes(fichier);

            ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                    () -> on.detect("usr-1", octets));
            assertEquals(HttpStatus.BAD_GATEWAY, erreur.getStatusCode());
            assertTrue(erreur.getReason().contains("JPEG"),
                    "Le message doit dire quoi reprendre : " + erreur.getReason());
        } finally {
            Files.deleteIfExists(fichier);
        }
    }

    private static boolean pythonPresent() {
        try {
            Process probe = new ProcessBuilder("python", "-c", "print(1)").start();
            boolean ok = probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0;
            probe.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
