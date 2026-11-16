package tn.higer.sav_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;

/**
 * Lecture de l'horodatage EXIF d'une photo, déléguée à {@code scripts/read_exif.py}.
 *
 * <p>Le script ne décide rien : il lit le champ « DateTimeOriginal » du fichier. La
 * décision appartient au service de photos, qui garde cette heure À CÔTÉ de l'heure du
 * serveur et non à sa place. Un appareil mal réglé ou une image recadrée se voient par
 * l'écart entre les deux, et c'est cet écart qui intéresse le chef d'atelier.</p>
 *
 * <p>Échec de Python = photo acceptée sans EXIF. Refuser un cliché parce que l'outil de
 * métadonnées est absent punirait l'atelier pour une panne de configuration ; le journal
 * garde la trace, et le compte rendu dira « heure de l'appareil inconnue ».</p>
 */
@Service
public class PhotoExifService {

    private static final Logger log = LoggerFactory.getLogger(PhotoExifService.class);
    private static final int MAX_ENVELOPE_BYTES = 8 * 1024;
    private static final DateTimeFormatter EXIF_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Ce que le fichier dit de sa propre prise de vue. Une heure absente n'est pas une erreur. */
    public record Exif(LocalDateTime takenAt, String camera) {

        public static final Exif UNKNOWN = new Exif(null, null);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final String pythonExecutable;
    private final String scriptPath;
    private final int timeoutSeconds;

    public PhotoExifService(@Value("${app.python.executable:python}") String pythonExecutable,
                            @Value("${app.depannage.exif-script:./scripts/read_exif.py}") String scriptPath,
                            @Value("${app.depannage.exif-timeout-seconds:15}") int timeoutSeconds) {
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Jamais d'exception : la photo est déjà stockée, l'EXIF est un complément. */
    public Exif read(Path imagePath) {
        if (!Files.isRegularFile(Path.of(scriptPath))) {
            log.warn("Script de lecture EXIF absent ({}), l'heure de l'appareil ne sera pas lue.", scriptPath);
            return Exif.UNKNOWN;
        }
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable, scriptPath,
                "--file", imagePath.toAbsolutePath().toString());
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
            if (!process.waitFor(timeoutSeconds + 5L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Lecture EXIF interrompue (délai dépassé) : {}", imagePath);
                return Exif.UNKNOWN;
            }
            return parse(stdout, stderr.toString());
        } catch (IOException e) {
            log.warn("Python indisponible pour la lecture EXIF ({}) : {}", pythonExecutable, e.getMessage());
            return Exif.UNKNOWN;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return Exif.UNKNOWN;
        }
    }

    private Exif parse(String stdout, String stderr) {
        JsonNode node;
        try {
            node = mapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("Réponse EXIF illisible : {} / {}", trim(stdout), trim(stderr));
            return Exif.UNKNOWN;
        }
        if (!node.path("ok").asBoolean(false)) {
            log.warn("Lecture EXIF refusée par le script : {}", trim(node.path("error").asText("")));
            return Exif.UNKNOWN;
        }
        String raw = node.path("taken_at").asText("");
        LocalDateTime takenAt = null;
        try {
            if (!raw.isBlank()) {
                takenAt = LocalDateTime.parse(raw, EXIF_ISO);
            }
        } catch (DateTimeParseException e) {
            log.warn("Horodatage EXIF dans un format inattendu ({}), écarté.", trim(raw));
        }
        String camera = node.path("camera").asText("");
        return new Exif(takenAt, camera.isBlank() ? null : trim(camera));
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        long total = 0;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_ENVELOPE_BYTES) {
                throw new IOException("Réponse du script EXIF trop longue (" + total + " octets)");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void drain(InputStream in, StringBuilder sink) {
        try {
            sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Le fils est déjà mort : rien à consommer.
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
