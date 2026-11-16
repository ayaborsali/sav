package tn.higer.sav_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lecture du numéro de châssis (VIN) sur une photo, faite par le modèle vision de Groq.
 *
 * <p>Le script {@code scripts/detect_vin.py} est lancé comme processus externe, comme la rédaction
 * des clauses et la mise en page du PDF : la photo part dans un fichier temporaire, l'enveloppe
 * JSON de la sortie standard dit ce qui a été lu. C'est ici que le résultat devient recevable ou
 * non : le format d'un VIN est vérifié côté serveur avant que quoi que ce soit soit proposé au
 * formulaire, un numéro mal lu ne peut donc pas être enregistre.</p>
 *
 * <p>EasyOCR et torch ne sont pas utilisés : sur la machine de déploiement, la DLL de numpy est
 * bloquée par une stratégie Windows, donc aucun OCR local ne démarre. La lecture est déléguée au
 * modèle, et Python reste la bibliothèque standard uniquement.</p>
 */
@Service
public class VinDetectionService {

    private static final Logger log = LoggerFactory.getLogger(VinDetectionService.class);

    /** Format ISO 3779 : 17 caractères, jamais I, O ni Q. Autorité serveur, non négociable. */
    private static final Pattern VIN_FORMAT = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

    private static final int MAX_ENVELOPE_BYTES = 16 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String pythonExecutable;
    private final String scriptPath;
    private final int timeoutSeconds;
    private final long maxImageBytes;
    private final int requestsPerMinute;

    /** Fenêtre glissante 60 s par utilisateur : Groq limite les jetons d'entrée par minute. */
    private final Map<String, Deque<Long>> recentCalls = new ConcurrentHashMap<>();

    /** Ce que le serveur retient de la lecture : rien d'enregistrable sans validation. */
    public record Outcome(String status, String vin, String partial, Integer readChars) {

        public boolean complete() {
            return "complete".equals(status);
        }
    }

    public VinDetectionService(
            @Value("${app.ai.groq.api-key:}") String apiKey,
            @Value("${app.ai.groq.model:qwen/qwen3.8-27b}") String model,
            @Value("${app.vin.model:${app.ai.groq.model:qwen/qwen3.8-27b}}") String vinModel,
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.vin.script:./scripts/detect_vin.py}") String scriptPath,
            @Value("${app.vin.timeout-seconds:90}") int timeoutSeconds,
            @Value("${app.vin.max-image-bytes:3500000}") long maxImageBytes,
            @Value("${app.vin.requests-per-minute:3}") int requestsPerMinute) {
        this.apiKey = apiKey;
        // VIN_MODEL permet un modèle vision différent sans toucher au modèle des clauses.
        this.model = (vinModel == null || vinModel.isBlank()) ? model : vinModel;
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
        this.maxImageBytes = maxImageBytes;
        this.requestsPerMinute = requestsPerMinute;
    }

    /** True si la lecture est utilisable : clé présente et script en place. */
    public boolean available() {
        return apiKey != null && !apiKey.isBlank() && Files.isRegularFile(Path.of(scriptPath));
    }

    public String model() {
        return model;
    }

    /**
     * Lit la photo reçue et renvoie un résultat déjà validé. Un statut {@code partial} ou
     * {@code none} n'est pas une erreur HTTP : le formulaire affiche ce qui manque.
     */
    public Outcome detect(String userSubject, byte[] imageBytes) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La lecture du châssis est indisponible : ajoute GROQ_API_KEY dans le fichier .env "
                            + "du backend, puis redémarre-le. La saisie manuelle du VIN reste possible.");
        }
        if (!Files.isRegularFile(Path.of(scriptPath))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Le script de lecture du châssis est introuvable (" + scriptPath + "). "
                            + "Lance le backend depuis le dossier sav-backend.");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aucune photo à lire.");
        }
        if (imageBytes.length > maxImageBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Photo trop lourde (" + imageBytes.length + " octets). Reprends-la recadrée "
                            + "sur la plaque du châssis.");
        }
        checkQuota(userSubject);

        Path image = null;
        try {
            image = Files.createTempFile("vin_", ".jpg");
            Files.write(image, imageBytes);
            return validate(runScript(image));
        } catch (IOException e) {
            log.warn("Préparation de la photo pour la lecture du châssis impossible ({})", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le serveur n'a pas pu préparer la photo pour la lecture.");
        } finally {
            if (image != null) {
                deleteQuietly(image);
            }
        }
    }

    /** Le format est la règle du serveur : une proposition hors format n'est jamais transmise. */
    private Outcome validate(JsonNode node) {
        String status = node.path("status").asText("none");
        if (!"complete".equals(status)) {
            String partial = node.path("partial").asText("");
            int read = node.path("read_chars").asInt(partial.length());
            return new Outcome(partial.isBlank() ? "none" : "partial", null, partial, read);
        }
        String vin = node.path("vin").asText("").toUpperCase();
        if (!VIN_FORMAT.matcher(vin).matches()) {
            log.warn("Le modèle a proposé un numéro hors format VIN ({} caractères) : écarté.", vin.length());
            return new Outcome("partial", null, vin.replaceAll("[^A-HJ-NPR-Z0-9]", ""), vin.length());
        }
        return new Outcome("complete", vin, null, 17);
    }

    private JsonNode runScript(Path imagePath) {
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, scriptPath,
                imagePath.toAbsolutePath().toString());
        builder.environment().put("GROQ_API_KEY", apiKey);
        builder.environment().put("VIN_MODEL", model);
        // Windows peut être en cp1252 sur la console : on force l'UTF-8 des deux côtés du pipe.
        builder.environment().put("PYTHONIOENCODING", "utf-8");

        Process process = null;
        try {
            process = builder.start();
            final Process running = process;
            StringBuilder stderr = new StringBuilder();
            Thread err = new Thread(() -> drain(running.getErrorStream(), stderr));
            err.setDaemon(true);
            err.start();

            String stdout = read(process.getInputStream());
            if (!process.waitFor(timeoutSeconds + 15L, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "La lecture du châssis a dépassé le délai autorisé. Réessaie avec une photo plus proche de la plaque.");
            }
            return parseEnvelope(stdout, process.exitValue(), stderr.toString());
        } catch (IOException e) {
            log.warn("Le script Python de lecture du châssis n'a pas pu être lancé ({})", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Python est introuvable pour la lecture du châssis (commande « " + pythonExecutable
                            + " », script « " + scriptPath + " »). Installe Python 3 ou règle "
                            + "app.python.executable. La saisie manuelle du VIN reste possible.");
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Lecture du châssis interrompue.");
        }
    }

    /**
     * L'enveloppe JSON du script devient un code HTTP : config = 503 (l'administrateur peut
     * corriger), le reste = 502. Le message shown à l'utilisateur ne contient jamais la clé.
     */
    private JsonNode parseEnvelope(String stdout, int exitCode, String stderr) {
        JsonNode node;
        try {
            node = mapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("Réponse illisible du script de lecture du châssis : {} / {}", trim(stdout), trim(stderr));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le service de lecture n'a pas répondu correctement. Réessaie dans un instant.");
        }
        if (node.path("ok").asBoolean(false)) {
            return node;
        }
        String kind = node.path("kind").asText("internal");
        String error = node.path("error").asText("La lecture du châssis a échoué.");
        // Le détail brut vient du fournisseur : journaux uniquement, jamais l'écran.
        log.warn("Lecture VIN refusée par le script Python (kind={}, exit={}) : {} / {}",
                kind, exitCode, error, trim(node.path("detail").asText("")));
        throw new ResponseStatusException("config".equals(kind)
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, error);
    }

    private void checkQuota(String subject) {
        if (requestsPerMinute <= 0) {
            return;
        }
        String key = subject == null ? "anon" : subject;
        long now = System.currentTimeMillis();
        Deque<Long> hits = recentCalls.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > 60_000L) {
                hits.pollFirst();
            }
            if (hits.size() >= requestsPerMinute) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Trop de lectures de châssis en une minute (limite " + requestsPerMinute
                                + "). Le service IA est limité en jetons par minute : attende une minute "
                                + "et réessaie, ou saisis le VIN manuellement.");
            }
            hits.addLast(now);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        long total = 0;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_ENVELOPE_BYTES) {
                throw new IOException("Réponse du script trop longue (" + total + " octets)");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** Vide le flux d'erreurs : un pipe plein bloquerait le processus fils. */
    private static void drain(InputStream in, StringBuilder sink) {
        try {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            synchronized (sink) {
                sink.append(trim(text));
            }
        } catch (IOException e) {
            // Le fils est déjà mort ou le pipe fermé : rien à consommer.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Fichier temporaire d'image non supprimé : {}", path);
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
