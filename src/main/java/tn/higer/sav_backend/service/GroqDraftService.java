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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Rédaction assistée des clauses du contrat par un modèle IA (Groq, qwen3-32b).
 *
 * <p>L'appel au modèle est écrit en Python — {@code scripts/draft_clause.py} — et ce service le
 * lance comme un processus externe, comme le fait déjà la détection de plaque : la demande part
 * en JSON sur l'entrée standard du script, la proposition revient en JSON sur sa sortie
 * standard. La clé n'est jamais passée en argument (les arguments d'un process sont lisibles par
 * tout le compte) : elle voyage par l'environnement du fils.</p>
 *
 * <p>Le partage des responsabilités est volontaire. Python ne fait que parler au modèle. Java
 * garde toute autorité : disponibilité réelle du service (clé présente), quota glissant par
 * utilisateur, rejet de toute proposition qui chiffrerait, et écriture en base après validation
 * humaine. Les montants du contrat ne quittent donc jamais Java — ils ne sont ni envoyés au
 * modèle, ni relus depuis sa réponse.</p>
 */
@Service
public class GroqDraftService {

    private static final Logger log = LoggerFactory.getLogger(GroqDraftService.class);

    /** Un texte de clause ne doit jamais contenir de montant, de % ni de date chiffrée. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\d[\\d .,]*\\s*(?:DT|dinars?|millimes?|€|EUR)"
                    + "|\\d+\\s*%|\\d+[.,]\\d{2,}|\\d{1,2}/\\d{1,2}/\\d{2,4}",
            Pattern.CASE_INSENSITIVE);

    /** Garde-fou mémoire : une réponse démesurée est anormale, on coupe plutôt que de sature. */
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int requestsPerMinute;
    private final int timeoutSeconds;
    private final String pythonExecutable;
    private final String scriptPath;

    /** Fenêtre glissante 60 s, par utilisateur (subject du jeton). */
    private final Map<String, Deque<Long>> recentCalls = new ConcurrentHashMap<>();

    public GroqDraftService(
            @Value("${app.ai.groq.enabled:true}") boolean configured,
            @Value("${app.ai.groq.api-key:}") String apiKey,
            @Value("${app.ai.groq.base-url}") String baseUrl,
            @Value("${app.ai.groq.model}") String model,
            @Value("${app.ai.groq.temperature:0.3}") double temperature,
            @Value("${app.ai.groq.max-tokens:900}") int maxTokens,
            @Value("${app.ai.groq.timeout-seconds:45}") int timeoutSeconds,
            @Value("${app.ai.groq.requests-per-minute:8}") int requestsPerMinute,
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.ai.groq.script:./scripts/draft_clause.py}") String scriptPath) {
        // Une clé vide désactive l'IA même si enabled=true : aucun process ne part dans le vide.
        this.enabled = configured && apiKey != null && !apiKey.isBlank();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.requestsPerMinute = requestsPerMinute;
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
    }

    /** True si une clé est présente : pilote le bouton « Proposer une rédaction » côté interface. */
    public boolean available() {
        return enabled;
    }

    public String model() {
        return model;
    }

    /**
     * Propose le texte d'une clause en lançant le script Python. {@code facts} décrit le contrat
     * sans aucun montant. Échec du script, du réseau ou contenu chiffré => ResponseStatusException
     * avec un message que l'utilisateur peut agir.
     */
    public String draft(String userSubject, String instruction, String facts) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Rédaction IA indisponible : ajoute GROQ_API_KEY dans le fichier .env du backend, "
                            + "puis redémarre-le. La rédaction standard et le PDF du contrat restent disponibles.");
        }
        checkQuota(userSubject);
        if (!Files.isRegularFile(Path.of(scriptPath))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Le script de rédaction est introuvable (" + scriptPath + "). "
                            + "Lance le backend depuis le dossier sav-backend.");
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "instruction", instruction,
                    "facts", facts,
                    "model", model,
                    "base_url", baseUrl,
                    "temperature", temperature,
                    "max_tokens", maxTokens));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "La demande de rédaction n'a pas pu être préparée.");
        }

        return validate(runScript(payload));
    }

    /** Lance {@code python scripts/draft_clause.py}, lui passe le JSON, relit sa réponse. */
    private String runScript(String payload) {
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, scriptPath);
        builder.environment().put("GROQ_API_KEY", apiKey);
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

            try (OutputStream out = process.getOutputStream()) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            String stdout = read(process.getInputStream());

            if (!process.waitFor(timeoutSeconds + 15L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "La rédaction IA a dépassé le délai autorisé. Réessaie dans un instant.");
            }
            return parseEnvelope(stdout, process.exitValue(), stderr.toString());
        } catch (IOException e) {
            // Python absent du PATH, exécutable introuvable : c'est une configuration, pas un bug.
            log.warn("Le script Python de rédaction n'a pas pu être lancé ({})", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Python est introuvable pour la rédaction IA (commande « " + pythonExecutable
                            + " », script « " + scriptPath + " »). Installe Python 3 ou règle "
                            + "app.python.executable. Le contrat standard et son PDF restent disponibles.");
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Rédaction IA interrompue.");
        }
    }

    /**
     * L'enveloppe JSON du script devient un code HTTP : config/absent = 503, le reste = 502.
     * Le message remonte tel quel — il ne contient jamais la clé.
     */
    private String parseEnvelope(String stdout, int exitCode, String stderr) {
        JsonNode node;
        try {
            node = mapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("Réponse illisible du script Python : {} / {}", trim(stdout), trim(stderr));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le service de rédaction n'a pas répondu correctement. Réessaie dans un instant.");
        }
        if (node.path("ok").asBoolean(false)) {
            return node.path("text").asText("");
        }
        String kind = node.path("kind").asText("internal");
        String error = node.path("error").asText("Le modèle n'a produit aucun texte réutilisable.");
        log.warn("Rédaction IA refusée par le script Python (kind={}, exit={}) : {}", kind, exitCode, error);
        HttpStatus status = "config".equals(kind) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        throw new ResponseStatusException(status, switch (kind) {
            case "network" -> "Le service IA est injoignable (réseau ou Groq indisponible). "
                    + "La rédaction standard du contrat reste imprimable.";
            case "upstream" -> error + " Réessaie dans un instant, ou garde la rédaction standard.";
            case "empty" -> "Le modèle n'a produit aucun texte réutilisable. Réessaie.";
            case "config" -> "Rédaction IA indisponible : " + error
                    + " Ajoute la clé dans le .env du backend puis redémarre-le.";
            default -> "Rédaction IA indisponible pour le moment.";
        });
    }

    /** Une clause n'a rien à chiffrer : tout motif monétaire / % / date est une invention => rejet. */
    private String validate(String text) {
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le modèle n'a produit aucun texte réutilisable. Réessaie.");
        }
        if (FORBIDDEN.matcher(text).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "La proposition a été écartée : elle contient un montant, un pourcentage ou une "
                            + "date que le modèle n'aurait pas dû inventer. Réessaie ou garde la "
                            + "rédaction standard.");
        }
        return text;
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
                        "Trop de demandes de rédaction IA d'un coup (" + requestsPerMinute
                                + "/minute). Attends une minute.");
            }
            hits.addLast(now);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        int total = 0;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_OUTPUT_BYTES) {
                throw new IOException("Réponse du script trop longue (" + total + " octets)");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** Vide le flux d'erreurs dans un tampon : un pipe plein bloquerait le processus fils. */
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

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
