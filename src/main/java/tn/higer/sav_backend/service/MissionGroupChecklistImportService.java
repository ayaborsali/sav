package tn.higer.sav_backend.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.security.Access;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MissionGroupChecklistImportService {
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final String INVALID_RESPONSE = "La lecture locale du PDF n'a pas répondu correctement. Réessayez.";
    private static final String TOO_LARGE = "PDF trop volumineux : compressez-le pour respecter la limite d'envoi (10 Mo maximum).";
    private final ObjectMapper mapper = new ObjectMapper();
    private final long maxBytes;
    private final String pythonExecutable;
    private final String scriptPath;
    private final long timeoutMillis;

    @Autowired
    public MissionGroupChecklistImportService(
            @Value("${app.mission-group.max-attachment-bytes:10000000}") long maxBytes,
            @Value("${app.python.executable:python}") String pythonExecutable,
            @Value("${app.mission-group.checklist-script:./scripts/read_workshop_pdf.py}") String scriptPath) {
        this(maxBytes, pythonExecutable, scriptPath, 30_000);
    }

    MissionGroupChecklistImportService(long maxBytes, String pythonExecutable, String scriptPath,
                                      long timeoutMillis) {
        this.maxBytes = Math.min(maxBytes, 10_000_000L);
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutMillis = timeoutMillis;
    }

    public Preview preview(MultipartFile file, Authentication auth) {
        if (!Access.usesMissionGroupe(auth)) {
            throw error(HttpStatus.FORBIDDEN, "L'import de tâches est réservé à l'atelier.");
        }
        if (file == null || file.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "Aucun fichier reçu : choisissez un PDF ou un fichier Excel contenant des tâches.");
        }
        String excelType = MissionGroupExcelReader.contentType(file);
        if (excelType != null) {
            byte[] bytes = MissionGroupExcelReader.readBounded(file, maxBytes);
            return new Preview(MissionGroupExcelReader.tasks(bytes, excelType), null);
        }
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw error(HttpStatus.BAD_REQUEST, "Format refusé : choisissez un PDF (application/pdf) ou un fichier Excel .xlsx ou .xls.");
        }
        if (file.getSize() > maxBytes) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
        }

        Path pdf = null;
        Path output = null;
        Process process = null;
        try {
            pdf = Files.createTempFile("workshop-checklist-", ".pdf");
            copyPdf(file, pdf);
            output = Files.createTempFile("workshop-checklist-", ".json");
            process = startProcess(pdf, output);
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            // Aucun flux du fils n'est lu avant la fin : les deux sorties vont sur disque.
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                checkOutputSize(output);
                if (System.nanoTime() >= deadline) {
                    throw error(HttpStatus.GATEWAY_TIMEOUT,
                            "La lecture du PDF a dépassé le délai de 30 secondes. Simplifiez le PDF et réessayez.");
                }
            }
            checkOutputSize(output);
            try (InputStream in = Files.newInputStream(output)) {
                byte[] bytes = in.readNBytes(MAX_OUTPUT_BYTES + 1);
                if (bytes.length > MAX_OUTPUT_BYTES) {
                    throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
                }
                return parseEnvelope(new String(bytes, StandardCharsets.UTF_8), process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.BAD_GATEWAY, "La lecture du PDF a été interrompue. Réessayez.");
        } catch (IOException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE,
                    "La lecture locale du PDF est indisponible. Faites vérifier Python et le script par l'administrateur.");
        } finally {
            stopAndClean(process, pdf, output);
        }
    }

    private void copyPdf(MultipartFile file, Path target) throws IOException {
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] signature = in.readNBytes(5);
            if (!java.util.Arrays.equals(signature, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                throw error(HttpStatus.BAD_REQUEST, "Le contenu reçu n'est pas un PDF valide (signature PDF absente).");
            }
            long total = signature.length;
            if (total > maxBytes) {
                throw error(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
            }
            out.write(signature);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw error(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
                }
                out.write(buffer, 0, read);
            }
        }
    }

    Process startProcess(Path pdf, Path output) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(pythonExecutable,
                Path.of(scriptPath).toAbsolutePath().normalize().toString(), pdf.toAbsolutePath().toString());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        return builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private void checkOutputSize(Path output) throws IOException {
        if (Files.size(output) > MAX_OUTPUT_BYTES) {
            throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
        }
    }

    private Preview parseEnvelope(String json, int exitCode) {
        JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (IOException e) {
            throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
        }
        if (root == null || !root.isObject() || !root.path("ok").isBoolean()) {
            throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
        }
        if (!root.path("ok").booleanValue()) {
            if (!root.path("kind").isTextual() || !root.path("error").isTextual()) {
                throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
            }
            throw scriptError(root.path("kind").textValue());
        }
        JsonNode pages = root.path("pageCount");
        JsonNode tasks = root.path("tasks");
        if (exitCode != 0 || !pages.isIntegralNumber() || !pages.canConvertToInt() || !tasks.isArray()) {
            throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
        }
        if (pages.intValue() < 1) {
            throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
        }
        if (pages.intValue() > 20) {
            throw scriptError("pages");
        }
        if (tasks.isEmpty()) {
            throw scriptError("no_tasks");
        }
        if (tasks.size() > 200) {
            throw scriptError("task_count");
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode task : tasks) {
            if (!task.isTextual() || task.textValue().isBlank()) {
                throw error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
            }
            String label = task.textValue();
            if (label.length() > 300) {
                throw scriptError("task_length");
            }
            labels.add(label);
        }
        return new Preview(List.copyOf(labels), pages.intValue());
    }

    private static ResponseStatusException scriptError(String kind) {
        return switch (kind) {
            case "invalid_pdf" -> error(HttpStatus.BAD_REQUEST, "PDF malformé ou illisible : exportez un nouveau PDF contenant du texte.");
            case "encrypted" -> error(HttpStatus.BAD_REQUEST, "PDF chiffré ou protégé : fournissez un PDF sans protection.");
            case "no_text" -> error(HttpStatus.BAD_REQUEST, "Le PDF contient une page sans texte ou scannée. L'OCR n'est pas réalisé : fournissez un PDF avec du texte sélectionnable.");
            case "pages" -> error(HttpStatus.BAD_REQUEST, "Le PDF dépasse 20 pages : simplifiez-le avant l'import.");
            case "text" -> error(HttpStatus.BAD_REQUEST, "Le PDF dépasse 100 000 caractères de texte : simplifiez-le avant l'import.");
            case "task_length" -> error(HttpStatus.BAD_REQUEST, "Une tâche dépasse 300 caractères : simplifiez le PDF ou séparez les tâches avant l'import.");
            case "task_count" -> error(HttpStatus.BAD_REQUEST, "Le PDF contient plus de 200 tâches : simplifiez-le avant l'import.");
            case "no_tasks" -> error(HttpStatus.BAD_REQUEST, "Aucune tâche identifiable : fournissez un PDF avec une liste de tâches à relire.");
            case "size" -> error(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
            case "config" -> error(HttpStatus.SERVICE_UNAVAILABLE, "La lecture locale du PDF est indisponible. Faites vérifier Python et pypdfium2 par l'administrateur.");
            default -> error(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE);
        };
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private static void stopAndClean(Process process, Path... files) {
        boolean interrupted = Thread.interrupted();
        try {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                try {
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
        } finally {
            for (Path file : files) {
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LoggerFactory.getLogger(MissionGroupChecklistImportService.class)
                                .warn("Un fichier temporaire de lecture PDF n'a pas pu être supprimé.");
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Le processus arrêté peut déjà avoir fermé ses flux.
            }
        }
    }

    public record Preview(List<String> tasks, @JsonInclude(JsonInclude.Include.ALWAYS) Integer pageCount) {}
}
