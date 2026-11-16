package tn.higer.sav_backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.UserSession;
import tn.higer.sav_backend.repository.UserSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class SessionService {

    private final UserSessionRepository sessions;
    private final long normalHours;
    private final long rememberDays;

    public SessionService(UserSessionRepository sessions,
                          @Value("${app.session.normal-hours}") long normalHours,
                          @Value("${app.session.remember-days}") long rememberDays) {
        this.sessions = sessions;
        this.normalHours = normalHours;
        this.rememberDays = rememberDays;
    }

    /** Crée une session dans PostgreSQL : 8 h par défaut, 30 jours si « Mémoriser ma session ». */
    public UserSession create(AppUser user, boolean rememberMe, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        UserSession s = new UserSession();
        s.setUserId(user.getId());
        s.setRememberMe(rememberMe);
        s.setCreatedAt(now);
        s.setLastSeenAt(now);
        s.setExpiresAt(rememberMe ? now.plusDays(rememberDays) : now.plusHours(normalHours));
        if (userAgent != null) {
            s.setUserAgent(userAgent.substring(0, Math.min(300, userAgent.length())));
        }
        return sessions.save(s);
    }

    /** Termine une session (déconnexion). Sans effet si elle est déjà terminée. */
    public void revoke(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(s -> {
            if (s.getRevokedAt() == null) {
                s.setRevokedAt(LocalDateTime.now());
                sessions.save(s);
            }
        });
    }

    /** Termine toutes les sessions d'un utilisateur. Renvoie leur nombre. */
    public int revokeAllForUser(UUID userId) {
        return sessions.revokeAllByUser(userId, LocalDateTime.now());
    }
}