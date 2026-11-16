package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.AuthDtos.MessageResponse;
import tn.higer.sav_backend.dto.UserDtos.CreateUserRequest;
import tn.higer.sav_backend.dto.UserDtos.UpdateUserRequest;
import tn.higer.sav_backend.dto.UserDtos.UserResponse;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.service.PasswordResetService;

import java.util.List;
import java.util.UUID;

/**
 * Contrôleur d'administration des utilisateurs.
 * Toutes les modifications sont persistées dans PostgreSQL.
 * Accès restreint à ROLE_ADMIN (configuré dans SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordResetService passwordResetService;

    public AdminUserController(UserRepository users, PasswordEncoder encoder,
                               PasswordResetService passwordResetService) {
        this.users = users;
        this.encoder = encoder;
        this.passwordResetService = passwordResetService;
    }

    /** GET /api/admin/users — Liste tous les utilisateurs. */
    @GetMapping
    public List<UserResponse> list() {
        return users.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    /** GET /api/admin/users/{id} — Récupère un utilisateur. */
    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return users.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
    }

    /** POST /api/admin/users — Crée un utilisateur (mot de passe haché BCrypt). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        // Vérifier que l'email n'existe pas déjà
        if (users.existsByEmailIgnoreCase(request.email().trim())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Un utilisateur avec cet email existe déjà");
        }

        AppUser user = new AppUser();
        user.setName(request.name().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setPasswordHash(encoder.encode(request.password()));  // ← HACHAGE BCRYPT
        user.setRole(request.role());
        user.setActive(request.active() != null ? request.active() : true);

        users.save(user);
        return UserResponse.from(user);
    }

    /** PUT /api/admin/users/{id} — Modifie un utilisateur (sans changer le mot de passe). */
    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id,
                               @AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody UpdateUserRequest request) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        boolean deactivating = request.active() != null && !request.active() && user.isActive();
        boolean demoting = user.getRole() == Role.ADMIN && request.role() != Role.ADMIN;
        if (user.getId().equals(selfId(jwt)) && (deactivating || demoting)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vous ne pouvez pas désactiver ou rétrograder votre propre compte.");
        }
        if ((deactivating || demoting) && isLastActiveAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Impossible : c'est le dernier administrateur actif du portail.");
        }

        // Vérifier que le nouvel email n'est pas déjà utilisé par un AUTRE utilisateur
        if (!user.getEmail().equalsIgnoreCase(request.email().trim())
                && users.existsByEmailIgnoreCase(request.email().trim())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Un autre utilisateur utilise déjà cet email");
        }

        user.setName(request.name().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setRole(request.role());
        if (request.active() != null) {
            user.setActive(request.active());
        }

        users.save(user);
        return UserResponse.from(user);
    }

    /** PATCH /api/admin/users/{id}/toggle — Active/désactive un utilisateur. */
    @PatchMapping("/{id}/toggle")
    public UserResponse toggle(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        if (user.getId().equals(selfId(jwt))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vous ne pouvez pas désactiver votre propre compte.");
        }
        if (user.isActive() && isLastActiveAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Impossible : c'est le dernier administrateur actif du portail.");
        }

        user.setActive(!user.isActive());
        users.save(user);
        return UserResponse.from(user);
    }

    /** DELETE /api/admin/users/{id} — Supprime un utilisateur. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable"));

        if (user.getId().equals(selfId(jwt))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Vous ne pouvez pas supprimer votre propre compte.");
        }
        if (user.isActive() && isLastActiveAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Impossible : c'est le dernier administrateur actif du portail.");
        }

        users.deleteById(id);
    }

    /**
     * POST /api/admin/users/{id}/send-reset-link — Envoie à l'utilisateur un email
     * contenant un lien pour choisir lui-même un nouveau mot de passe.
     * L'admin ne voit ni ne choisit jamais le mot de passe.
     */
    @PostMapping("/{id}/send-reset-link")
    public MessageResponse sendResetLink(@PathVariable UUID id) {
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
        passwordResetService.requestReset(user.getEmail());
        return new MessageResponse("Lien de réinitialisation envoyé à " + user.getEmail());
    }

    // --- Protections anti-verrouillage ---

    private static UUID selfId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Vrai si cette action ferait perdre au portail son dernier administrateur actif. */
    private boolean isLastActiveAdmin(AppUser user) {
        return user.getRole() == Role.ADMIN
                && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1;
    }
}