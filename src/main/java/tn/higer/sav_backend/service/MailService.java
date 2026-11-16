package tn.higer.sav_backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.AppUser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String from;
    private final String frontendUrl;

    public MailService(JavaMailSender mailSender,
                       @Value("${spring.mail.username}") String from,
                       @Value("${app.frontend.url}") String frontendUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.frontendUrl = frontendUrl;
    }

    public void sendPasswordResetEmail(AppUser user, String rawToken, long expirationMinutes) {
        // Harmonisation du lien avec la route Angular active : /reset-password?token=...
        String resetLink = frontendUrl + "/reset-password?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            
            // CORRECTION ICI : Le second paramètre passe à 'true' pour activer le mode Multipart alternatif (Texte + HTML)
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject("Réinitialisation de votre mot de passe — Portail SAV Higer");
            
            // Utilisation de la méthode native pour injecter à la fois le texte brut ET la structure HTML
            helper.setText(plainText(user, resetLink, expirationMinutes), html(user, resetLink, expirationMinutes));
            
            mailSender.send(message);
            log.info("Email de réinitialisation envoyé à {}", user.getEmail());
        } catch (MessagingException | MailException e) {
            log.error("Échec de l'envoi de l'email de réinitialisation à {}", user.getEmail(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "L'envoi de l'email de réinitialisation a échoué. Vérifiez la configuration SMTP du serveur.");
        }
    }

    private String plainText(AppUser user, String link, long expirationMinutes) {
        return """
                Bonjour %s,

                Vous avez demandé la réinitialisation de votre mot de passe sur le Portail SAV Higer.

                Cliquez sur ce lien pour choisir un nouveau mot de passe :
                %s

                Ce lien expire dans %d minutes. Si vous n'êtes pas à l'origine de cette demande,
                ignorez simplement cet email : votre mot de passe reste inchangé.

                Saphir Véhicules et Automobiles — Portail SAV
                """.formatted(user.getName(), link, expirationMinutes);
    }

    private String html(AppUser user, String link, long expirationMinutes) {
        return """
                <div style="font-family:Segoe UI,Arial,sans-serif;max-width:520px;margin:0 auto;color:#0f172a">
                  <div style="background:#1e3a8a;background:linear-gradient(90deg,#1e3a8a,#312e81);padding:20px 24px;border-radius:12px 12px 0 0">
                    <h1 style="margin:0;font-size:18px;color:#ffffff">Saphir Véhicules et Automobiles — Portail SAV</h1>
                  </div>
                  <div style="border:1px solid #e2e8f0;border-top:none;border-radius:0 0 12px 12px;padding:24px">
                    <h2 style="margin:0 0 12px;font-size:16px">Réinitialisation de mot de passe</h2>
                    <p style="font-size:13px;line-height:1.6">Bonjour <strong>%s</strong>,</p>
                    <p style="font-size:13px;line-height:1.6">
                      Vous avez demandé la réinitialisation de votre mot de passe. Cliquez sur le bouton ci-dessous
                      pour choisir un nouveau mot de passe.
                    </p>
                    <p style="text-align:center;margin:28px 0">
                      <a href="%s"
                         style="background:#2563eb;color:#ffffff;text-decoration:none;font-weight:600;font-size:13px;padding:12px 24px;border-radius:8px;display:inline-block">
                        Choisir un nouveau mot de passe
                      </a>
                    </p>
                    <p style="font-size:12px;color:#64748b;line-height:1.6">
                      Ce lien expire dans <strong>%d minutes</strong>. Si le bouton ne fonctionne pas, copiez ce lien
                      dans votre navigateur :<br/>
                      <a href="%s" style="color:#2563eb;word-break:break-all">%s</a>
                    </p>
                    <p style="font-size:12px;color:#64748b;line-height:1.6">
                      Si vous n'êtes pas à l'origine de cette demande, ignorez simplement cet email :
                      votre mot de passe reste inchangé.
                    </p>
                  </div>
                </div>
                """.formatted(user.getName(), link, expirationMinutes, link, link);
    }
}
