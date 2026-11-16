package tn.higer.sav_backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Une règle que seul le démarrage du serveur vérifiait jusqu'ici.
 *
 * Hibernate refuse un champ à virgule (double) portant une précision et une
 * échelle : « scale has no meaning for SQL floating point types ». Ce n'est pas
 * une erreur de compilation, ni une erreur de test unitaire — c'est le backend
 * qui ne démarre plus, et l'atelier qui attend. Comme un prix de catalogue est
 * un montant, la faute se reproduira dès qu'une colonne s'ajoutera.
 *
 * La règle du projet : un montant se déclare en BigDecimal (Vehicle.unitPriceHt,
 * Technician.hourlyRate) ; un nombre simple reste sans taille dans l'annotation.
 */
class ColonnesMontantsTest {

    @Test
    void toutChampAEchelleEstUnBigDecimal() throws Exception {
        List<Class<?>> entites = entitesDuProjet();
        assertFalse(entites.isEmpty(), "Aucune entité trouvée : le scan du paquet model ne marche plus.");

        for (Class<?> entite : entites) {
            for (Field champ : entite.getDeclaredFields()) {
                Column colonne = champ.getAnnotation(Column.class);
                if (colonne == null || (colonne.scale() == 0 && colonne.precision() == 0)) {
                    continue;
                }
                assertEquals(BigDecimal.class, champ.getType(),
                        entite.getSimpleName() + "." + champ.getName()
                                + " porte une précision monétaire (" + colonne.precision() + "," + colonne.scale()
                                + ") mais n'est pas un BigDecimal : le serveur ne démarrera pas.");
            }
        }
    }

    /**
     * La vraie vérification : assembler le modèle Hibernate de toutes les entités,
     * comme le fait le serveur au démarrage, mais sans base de données. C'est là
     * que « scale has no meaning for SQL floating point types » se déclare — donc
     * là où une annotation fautive doit échouer pendant `mvnw test`, pas pendant
     * que l'atelier attend.
     */
    @Test
    void leModeleHibernateSeConstruitSansBase() throws Exception {
        List<Class<?>> entites = entitesDuProjet();
        assertFalse(entites.isEmpty(), "Aucune entité trouvée : le scan du paquet model ne marche plus.");

        org.hibernate.boot.registry.StandardServiceRegistry registry =
                new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                        .applySetting(org.hibernate.cfg.AvailableSettings.DIALECT,
                                "org.hibernate.dialect.PostgreSQLDialect")
                        .build();
        try {
            org.hibernate.boot.MetadataSources sources =
                    new org.hibernate.boot.MetadataSources(registry);
            for (Class<?> entite : entites) {
                sources.addAnnotatedClass(entite);
            }
            org.hibernate.boot.Metadata metadonnees = sources.buildMetadata();
            assertFalse(metadonnees.getEntityBindings().isEmpty(),
                    "Aucune entité mappée : le test ne protège plus rien.");
            // Toucher les colonnes de chaque table : c'est là que la taille d'une
            // colonne monétaire se calcule, et donc qu'une annotation fautive tombe.
            metadonnees.getEntityBindings().forEach(entiteMapee ->
                    assertNotNull(entiteMapee.getTable().getName()));
        } finally {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** Les classes de ce paquet, lues dans le dossier de compilation — sans dépendre d'un scanner. */
    private static List<Class<?>> entitesDuProjet() throws Exception {
        // Le dossier des classes du projet, pas celui des tests : c'est une entité
        // qui indique où elle se trouve.
        Path dossier = Path.of(MaintenancePlan.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path paquet = dossier.resolve("tn/higer/sav_backend/model");
        if (!Files.isDirectory(paquet)) {
            return List.of();
        }
        try (Stream<Path> fichiers = Files.list(paquet)) {
            return fichiers
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".class") && !n.contains("$"))
                    .map(n -> "tn.higer.sav_backend.model." + n.replace(".class", ""))
                    .map(ColonnesMontantsTest::charger)
                    .filter(c -> c != null && c.isAnnotationPresent(Entity.class))
                    .toList();
        }
    }

    private static Class<?> charger(String nom) {
        try {
            return Class.forName(nom, false, ColonnesMontantsTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
