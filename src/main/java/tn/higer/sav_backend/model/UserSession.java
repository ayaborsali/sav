package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** Une ligne = une connexion (un « badge » délivré à un utilisateur). */
@Entity
@Table(name = "user_sessions")
@Getter @Setter @NoArgsConstructor
public class UserSession {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private boolean rememberMe;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime lastSeenAt;

    /** Rempli quand la session est terminée (déconnexion ou révocation par l'admin). */
    private LocalDateTime revokedAt;

    @Column(length = 300)
    private String userAgent;
}