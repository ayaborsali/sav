package tn.higer.sav_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final long expirationMinutes;

    public PasswordResetService(UserRepository users, PasswordEncoder encoder, MailService mail,
                                @Value("${app.reset.expiration-minutes}") long expirationMinutes) {
        this.users = users;
        this.encoder = encoder;
        this.mail = mail;
        this.expirationMinutes = expirationMinutes;
    }

    /**
     * Envoie un lien de réinitialisation si un compte existe pour cet email.
     * Aucune information n'est révélée au client sur l'existence du compte.
     */
    public void requestReset(String email) {
        Optional<AppUser> found = users.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            log.info("Demande de réinitialisation ignorée : aucun compte pour {}", email);
            return;
        }

        AppUser user = found.get();
        String rawToken = generateToken();
        user.setResetTokenHash(sha256Hex(rawToken));
        user.setResetTokenExpiresAt(LocalDateTime.now().plusMinutes(expirationMinutes));
        users.save(user);

        try {
            mail.sendPasswordResetEmail(user, rawToken, expirationMinutes);
        } catch (RuntimeException e) {
            user.setResetTokenHash(null);
            user.setResetTokenExpiresAt(null);
            users.save(user);
            throw e;
        }
    }

    public void resetPassword(String rawToken, String newPassword) {
        AppUser user = users.findByResetTokenHash(sha256Hex(rawToken))
                .filter(u -> u.getResetTokenExpiresAt() != null
                        && u.getResetTokenExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Lien de réinitialisation invalide ou expiré. Veuillez refaire une demande."));

        user.setPasswordHash(encoder.encode(newPassword));
        user.setResetTokenHash(null);
        user.setResetTokenExpiresAt(null);
        users.save(user);
        log.info("Mot de passe réinitialisé pour {}", user.getEmail());
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
