package tn.higer.sav_backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SessionDtos.SessionInfo;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.UserSession;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.repository.UserSessionRepository;
import tn.higer.sav_backend.security.SessionService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Réservé à l'administrateur (règle /api/admin/** dans SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminSessionController {

    private final UserSessionRepository sessions;
    private final UserRepository users;
    private final SessionService sessionService;

    public AdminSessionController(UserSessionRepository sessions, UserRepository users,
                                  SessionService sessionService) {
        this.sessions = sessions;
        this.users = users;
        this.sessionService = sessionService;
    }

    /** Liste des sessions actuellement ouvertes. */
    @GetMapping("/sessions")
    public List<SessionInfo> activeSessions() {
        List<UserSession> list =
                sessions.findByRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(LocalDateTime.now());
        List<UUID> userIds = list.stream().map(UserSession::getUserId).distinct().toList();
        Map<UUID, AppUser> byId = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u));
        return list.stream().map(s -> SessionInfo.from(s, byId.get(s.getUserId()))).toList();
    }

    /** Déconnecte une session à distance. */
    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        if (!sessions.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session introuvable");
        }
        sessionService.revoke(id);
    }

    /** Déconnecte un utilisateur partout (toutes ses sessions). */
    @PostMapping("/users/{userId}/sessions/revoke")
    public Map<String, Integer> revokeAll(@PathVariable UUID userId) {
        return Map.of("revoked", sessionService.revokeAllForUser(userId));
    }
}