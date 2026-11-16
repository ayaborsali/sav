package tn.higer.sav_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Pont Java -> Python pour les documents mis en page par un script (contrat, bon d'intervention).
 *
 * <p>Le contenu est calculé par Java et arrive déjà formaté en JSON sur l'entrée standard ; le
 * script écrit le PDF dans un fichier temporaire dont la voie est passée en argument, et son
 * enveloppe JSON sur la sortie standard dit si ça a marché. Le partage est strict : Java calcule
 * et décide quoi montrer, Python ne pose que des valeurs sur la page.</p>
 *
 * <p>Le PDF transite par un fichier et non par la sortie standard : un flux binaire mélangé à du
 * JSON d'erreur serait illisible. Le fichier est détruit dans tous les cas.</p>
 *
 * <p>Les codes HTTP sont une partie de la règle : une dépendance Python absente est une
 * configuration que l'administrateur corrige (503, le message nomme la commande), alors qu'un
 * script qui rend la main est une panne du document (502).</p>
 */
abstract class PdfScriptLauncher {

    /** L'enveloppe JSON du script est courte ; au-delà, ce n'est pas elle (et on ne sature pas). */
    private static final int MAX_ENVELOPE_BYTES = 16 * 1024;
    /** Un document de 20 Mo n'existe pas : une sortie plus grosse est une anomalie. */
    private static final long MAX_PDF_BYTES = 20L * 1024 * 1024;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper = new ObjectMapper();
    private final String pythonExecutable;
    private final String scriptPath;
    private final int timeoutSeconds;
    private final String tempPrefix;

    protected PdfScriptLauncher(String pythonExecutable, String scriptPath,
                                int timeoutSeconds, String tempPrefix) {
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
        this.tempPrefix = tempPrefix;
    }

    /**
     * Transforme le plan du document en octets PDF. Échec de Python, de reportlab ou de la mise
     * en page => ResponseStatusException dont le message indique quoi corriger.
     */
    protected byte[] renderPdf(Map<String, Object> payload) {
        if (!Files.isRegularFile(Path.of(scriptPath))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Le script de mise en page est introuvable (" + scriptPath + "). "
                            + "Lance le backend depuis le dossier sav-backend.");
        }
        String request = writeRequest(payload);
        Path pdf;
        try {
            pdf = Files.createTempFile(tempPrefix, ".pdf");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le serveur n'a pas pu préparer de fichier temporaire pour le PDF.");
        }
        try {
            runScript(request, pdf);
            return readPdf(pdf);
        } finally {
            deleteQuietly(pdf);
        }
    }

    private String writeRequest(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le document n'a pas pu être préparé pour la mise en page.");
        }
    }

    /** Lance {@code python script.py --out fichier.pdf} et lit son enveloppe. */
    private void runScript(String request, Path pdfPath) {
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, scriptPath,
                "--out", pdfPath.toAbsolutePath().toString());
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
                out.write(request.getBytes(StandardCharsets.UTF_8));
            }
            String stdout = read(process.getInputStream());

            if (!process.waitFor(timeoutSeconds + 15L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "La mise en page a dépassé le délai autorisé. Réessaie.");
            }
            parseEnvelope(stdout, process.exitValue(), stderr.toString());
        } catch (IOException e) {
            // Python absent du PATH : c'est une configuration, pas un bug du document.
            log.warn("Le script Python de mise en page n'a pas pu être lancé ({})", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Python est introuvable pour produire le PDF (commande « " + pythonExecutable
                            + " », script « " + scriptPath + " »). Installe Python 3 ou règle "
                            + "app.python.executable.");
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mise en page interrompue.");
        }
    }

    /** config = dépendance absente (503, l'administrateur peut corriger) ; le reste = 502. */
    private void parseEnvelope(String stdout, int exitCode, String stderr) {
        JsonNode node;
        try {
            node = mapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("Réponse illisible du script de mise en page : {} / {}", trim(stdout), trim(stderr));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le service de mise en page n'a pas répondu correctement. Réessaie.");
        }
        if (node.path("ok").asBoolean(false)) {
            return;
        }
        String kind = node.path("kind").asText("internal");
        String error = node.path("error").asText("La mise en page a échoué.");
        log.warn("Mise en page PDF refusée par le script Python (kind={}, exit={}) : {}", kind, exitCode, error);
        throw new ResponseStatusException("config".equals(kind)
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY, error);
    }

    private byte[] readPdf(Path pdfPath) {
        long size;
        try {
            size = Files.size(pdfPath);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le PDF n'a pas pu être relu après la mise en page.");
        }
        if (size == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "La mise en page n'a produit aucun fichier PDF.");
        }
        if (size > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le PDF produit est anormalement volumineux (" + size + " octets).");
        }
        try {
            return Files.readAllBytes(pdfPath);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Le PDF n'a pas pu être lu après la mise en page.");
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LoggerFactory.getLogger(PdfScriptLauncher.class)
                    .debug("Fichier temporaire PDF non supprimé : {}", path);
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
