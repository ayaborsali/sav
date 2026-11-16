package tn.higer.sav_backend.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.UserSession;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.repository.UserSessionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Le « contrôleur d'accès » : à CHAQUE requête, vérifie dans PostgreSQL que
 * la session du jeton est encore valable et que le compte est actif.
 * Le rôle est lu dans la base : un changement de rôle s'applique tout de suite.
 */
@Component
public class SessionJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserSessionRepository sessions;
    private final UserRepository users;

    public SessionJwtConverter(UserSessionRepository sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID sessionId = readSessionId(jwt);

        UserSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new InvalidBearerTokenException("Session inconnue"));

        LocalDateTime now = LocalDateTime.now();
        if (session.getRevokedAt() != null) {
            throw new InvalidBearerTokenException("Session terminée");
        }
        if (session.getExpiresAt().isBefore(now)) {
            throw new InvalidBearerTokenException("Session expirée");
        }

        AppUser user = users.findById(session.getUserId())
                .orElseThrow(() -> new InvalidBearerTokenException("Utilisateur introuvable"));
        if (!user.isActive()) {
            throw new InvalidBearerTokenException("Compte désactivé");
        }

        // On note « vu pour la dernière fois » au plus une fois toutes les 5 minutes.
        if (session.getLastSeenAt() == null || session.getLastSeenAt().isBefore(now.minusMinutes(5))) {
            session.setLastSeenAt(now);
            sessions.save(session);
        }

        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new JwtAuthenticationToken(jwt, authorities, user.getId().toString());
    }

    private UUID readSessionId(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            throw new InvalidBearerTokenException("Jeton sans session");
        }
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Jeton invalide");
        }
    }
}