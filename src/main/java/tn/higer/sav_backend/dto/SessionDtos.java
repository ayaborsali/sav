package tn.higer.sav_backend.dto;

import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.UserSession;

import java.time.LocalDateTime;
import java.util.UUID;

public final class SessionDtos {

    private SessionDtos() {}

    public record SessionInfo(UUID id, UUID userId, String userName, String userEmail,
                              LocalDateTime createdAt, LocalDateTime lastSeenAt,
                              LocalDateTime expiresAt, boolean rememberMe, String userAgent) {

        public static SessionInfo from(UserSession s, AppUser u) {
            return new SessionInfo(
                    s.getId(), s.getUserId(),
                    u != null ? u.getName() : null,
                    u != null ? u.getEmail() : null,
                    s.getCreatedAt(), s.getLastSeenAt(), s.getExpiresAt(),
                    s.isRememberMe(), s.getUserAgent());
        }
    }
}