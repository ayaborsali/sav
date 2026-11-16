package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;

import java.time.LocalDateTime;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {}

    public record CreateUserRequest(
        @NotBlank(message = "Le nom est obligatoire")
        String name,

        @NotBlank(message = "L'email est obligatoire")
        @Email(message = "Format d'email invalide")
        String email,

        @NotBlank(message = "Le mot de passe est obligatoire")
        @Size(min = 8, max = 72, message = "Le mot de passe doit contenir entre 8 et 72 caractères")
        String password,

        @NotNull(message = "Le rôle est obligatoire")
        Role role,

        Boolean active
    ) {}

    public record UpdateUserRequest(
        @NotBlank(message = "Le nom est obligatoire")
        String name,

        @NotBlank(message = "L'email est obligatoire")
        @Email(message = "Format d'email invalide")
        String email,

        @NotNull(message = "Le rôle est obligatoire")
        Role role,

        Boolean active
    ) {}

    public record UserResponse(
        UUID id,
        String name,
        String email,
        String role,
        String status,
        LocalDateTime lastLogin
    ) {
        public static UserResponse from(AppUser u) {
            return new UserResponse(
                u.getId(),
                u.getName(),
                u.getEmail(),
                u.getRole().name().toLowerCase(),
                u.isActive() ? "active" : "inactive",
                u.getLastLogin()
            );
        }
    }
}