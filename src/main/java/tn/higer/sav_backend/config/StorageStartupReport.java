package tn.higer.sav_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Écrit au démarrage l'endroit exact où vivent les fichiers du portail (photos de
 * sortie, pièces jointes) et la taille du volume qui les porte.
 *
 * <p>Sur un hébergement à disque éphémère, le volume affiché est celui du conteneur :
 * les fichiers partent au redéploiement et la base garde des chemins dans le vide.
 * Quand un disque persistant est monté sur {@code /app/data}, le volume affiché est
 * celui de ce disque (1 Go ou plus) : c'est la preuve, dans le journal, que les
 * photos survivront au redémarrage.</p>
 */
@Component
public class StorageStartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageStartupReport.class);

    private final Path filesDir;
    private final Path photosDir;

    public StorageStartupReport(
            @Value("${app.storage.dir:./data}") String storageDir,
            @Value("${app.depannage.photos-dir:./data/photos}") String photosDir) {
        this.filesDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.photosDir = Path.of(photosDir).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        Path porteur = existingAncestor(filesDir);
        try {
            FileStore volume = Files.getFileStore(porteur);
            log.info("Fichiers du portail : {} (photos : {}, pièces jointes : {}) — "
                            + "volume {} au total, {} libres, écriture {}",
                    filesDir, photosDir, filesDir.resolve("attachments"),
                    gigabytes(volume.getTotalSpace()), gigabytes(volume.getUsableSpace()),
                    Files.isWritable(porteur) ? "possible" : "À VÉRIFIER");
        } catch (IOException e) {
            log.warn("Le volume des fichiers n'a pas pu être lu ({}). Les fichiers sont prévus dans {}.",
                    e.getMessage(), filesDir);
        }
    }

    /** Le disque se lit sur un chemin existant : les dossiers, eux, se créent à la première écriture. */
    private static Path existingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? Path.of(".").toAbsolutePath() : current;
    }

    private static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f Go", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
