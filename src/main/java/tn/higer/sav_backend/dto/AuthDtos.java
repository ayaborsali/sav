package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import tn.higer.sav_backend.model.AppUser;

import java.time.LocalDateTime;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    /** rememberMe est facultatif : s'il manque, il vaut false. */
    public record LoginRequest(@NotBlank String email, @NotBlank String password, Boolean rememberMe) {}

    public record UserResponse(UUID id, String name, String email,
                               String role, String status, LocalDateTime lastLogin) {
        public static UserResponse from(AppUser u) {
            return new UserResponse(
                    u.getId(), u.getName(), u.getEmail(),
                    u.getRole().name().toLowerCase(),
                    u.isActive() ? "active" : "inactive",
                    u.getLastLogin());
        }
    }

    public record LoginResponse(String token, UserResponse user) {}

    /** Changement de mot de passe par l'utilisateur connecté (tous rôles). */
    public record ChangePasswordRequest(@NotBlank String newPassword) {}

    /** Demande d'un lien de réinitialisation (page de connexion, accès public). */
    public record ResetRequest(@NotBlank String email) {}

    /** Nouveau mot de passe choisi via le lien reçu par email (accès public). */
    public record NewPasswordRequest(@NotBlank String token, @NotBlank String newPassword) {}

    /** Réponse générique avec un message lisible pour l'écran. */
    public record MessageResponse(String message) {}
}