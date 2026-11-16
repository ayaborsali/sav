package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.AuthDtos.ChangePasswordRequest;
import tn.higer.sav_backend.dto.AuthDtos.LoginRequest;
import tn.higer.sav_backend.dto.AuthDtos.LoginResponse;
import tn.higer.sav_backend.dto.AuthDtos.MessageResponse;
import tn.higer.sav_backend.dto.AuthDtos.NewPasswordRequest;
import tn.higer.sav_backend.dto.AuthDtos.ResetRequest;
import tn.higer.sav_backend.dto.AuthDtos.UserResponse;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.UserSession;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.security.JwtService;
import tn.higer.sav_backend.security.SessionService;
import tn.higer.sav_backend.service.PasswordResetService;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final PasswordResetService passwordResetService;

    public AuthController(UserRepository users, PasswordEncoder encoder,
                          JwtService jwtService, SessionService sessionService,
                          PasswordResetService passwordResetService) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
                               @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        AppUser user = users.findByEmailIgnoreCase(request.email().trim())
                .filter(u -> encoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Identifiants invalides"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce compte est désactivé. L'accès au portail SAV est refusé.");
        }

        user.setLastLogin(LocalDateTime.now());
        users.save(user);

        boolean remember = Boolean.TRUE.equals(request.rememberMe());
        UserSession session = sessionService.create(user, remember, userAgent);
        return new LoginResponse(jwtService.generate(user, session), UserResponse.from(user));
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(UUID.fromString(jwt.getSubject()))
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** Déconnexion : termine la session dans PostgreSQL. Le jeton ne marche plus ensuite. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        sessionService.revoke(UUID.fromString(jwt.getClaimAsString("sid")));
    }

    /** Changement de mot de passe personnel : accessible à tous les rôles connectés. */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody ChangePasswordRequest request) {
        AppUser user = users.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le nouveau mot de passe doit contenir au moins 8 caractères.");
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        users.save(user);
    }

    /**
     * Demande d'un lien de réinitialisation (page « Mot de passe oublié ? »).
     * Réponse volontairement identique que le compte existe ou non :
     * on ne révèle pas quels emails sont inscrits.
     */
    @PostMapping("/request-reset")
    public MessageResponse requestReset(@Valid @RequestBody ResetRequest request) {
        passwordResetService.requestReset(request.email());
        return new MessageResponse(
                "Si un compte existe pour cet email, un lien de réinitialisation vient d'être envoyé.");
    }

    /** Finalisation : nouveau mot de passe choisi depuis le lien reçu par email. */
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody NewPasswordRequest request) {
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le nouveau mot de passe doit contenir au moins 8 caractères.");
        }
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return new MessageResponse("Mot de passe modifié avec succès. Vous pouvez vous connecter.");
    }
}