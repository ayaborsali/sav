package tn.higer.sav_backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Vérifie dès le démarrage que les secrets nécessaires sont bien fournis.
 *
 * <p>But : refuser de démarrer plutôt que de rouler avec un secret par défaut écrit dans le
 * dépôt, ou de découvrir l'absence de secret trop tard (500 au premier login). Les valeurs
 * injectées ici sont celles <i>résolues</i> par Spring : elles peuvent venir d'une variable
 * d'environnement, du fichier {@code .env} ou du fichier de dev non versionné
 * {@code src/main/resources/application-local.properties}.</p>
 */
@Component
public class SecretsStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecretsStartupValidator.class);

    /** Longueur minimale exigée pour la clé HMAC-SHA256 (256 bits). */
    private static final int MIN_JWT_SECRET_BYTES = 32;

    private final String jwtSecret;
    private final String datasourcePassword;
    private final String mailUsername;
    private final String mailPassword;
    private final String adminPassword;

    public SecretsStartupValidator(
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${spring.datasource.password:}") String datasourcePassword,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${app.admin.password:}") String adminPassword) {
        this.jwtSecret = jwtSecret;
        this.datasourcePassword = datasourcePassword;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
        this.adminPassword = adminPassword;
    }

    @Override
    public void afterPropertiesSet() {
        int jwtLength = jwtSecret == null ? 0 : jwtSecret.trim().getBytes(StandardCharsets.UTF_8).length;

        if (jwtLength == 0) {
            throw new IllegalStateException(
                    "ERREUR DE DÉMARRAGE : le secret de signature des jetons est vide. "
                            + "Définissez la variable d'environnement JWT_SECRET (ou le fichier .env) "
                            + "avec une chaîne d'au moins " + MIN_JWT_SECRET_BYTES + " caractères. "
                            + "En développement : local.jwt.secret dans application-local.properties.");
        }
        if (jwtLength < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "ERREUR DE DÉMARRAGE : JWT_SECRET est trop court (" + jwtLength + " octets) — "
                            + "il faut au minimum " + MIN_JWT_SECRET_BYTES + " caractères pour HS256.");
        }

        if (datasourcePassword == null || datasourcePassword.isBlank()) {
            throw new IllegalStateException(
                    "ERREUR DE DÉMARRAGE : le mot de passe de la base de données est vide. "
                            + "Définissez la variable d'environnement DB_PASSWORD (ou le fichier .env). "
                            + "En développement : local.db.password dans application-local.properties.");
        }

        // Le SMTP ne bloque pas le démarrage : le portail fonctionne, seule la réinitialisation
        // du mot de passe par e-mail devient inutilisable.
        boolean mailConfigured = mailUsername != null && !mailUsername.isBlank()
                && mailPassword != null && !mailPassword.isBlank();
        if (!mailConfigured) {
            log.warn("IDENTIFIANTS SMTP ABSENTS : définissez MAIL_USERNAME et MAIL_PASSWORD pour activer "
                    + "l'envoi des e-mails de réinitialisation de mot de passe.");
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("ADMIN_PASSWORD non défini : le compte administrateur créé au premier démarrage "
                    + "recevra le mot de passe de démonstration des comptes de test.");
        }

        log.info("Contrôle de démarrage des secrets : JWT_SECRET et DB_PASSWORD fournis, SMTP {}.",
                mailConfigured ? "configuré" : "non configuré");
    }
}
