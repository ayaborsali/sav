package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.Technician;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fiches techniciens (le chef d'atelier les crée, l'ADMIN aussi).
 *
 * Les deux colonnes de paie (hourlyRate, dispatchAllowance) ne quittent le
 * serveur que pour ADMIN et CHEF_ATELIER : un technicien qui lit sa propre
 * fiche ne doit pas voir le taux d'un autre, et un compte « consultation » ne
 * doit rien voir du tout. Le masquage est fait dans {@link TechnicianResponse},
 * pas à l'écran.
 */
public final class TechnicianDtos {

    private TechnicianDtos() {}

    /**
     * Le compte du technicien se relie soit par son identifiant, soit par son e-mail.
     * L'e-mail existe parce que le chef d'atelier n'a pas accès à la page Utilisateurs
     * (réservée à l'ADMIN) : il saisit l'adresse que l'admin a créée pour lui.
     */
    public record TechnicianRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 30) String phone,
            UUID userId,
            @Size(max = 255) String userEmail,
            @DecimalMin("0.0") BigDecimal hourlyRate,
            @DecimalMin("0.0") BigDecimal dispatchAllowance,
            Boolean active,
            @Size(max = 500) String notes) {}

    public record TechnicianResponse(String id, String name, String phone, String userId, String userEmail,
                                     BigDecimal hourlyRate, BigDecimal dispatchAllowance,
                                     boolean active, String notes) {

        public static TechnicianResponse from(Technician t, boolean seesPayroll, String userEmail) {
            return new TechnicianResponse(
                    t.getId(), t.getName(), t.getPhone(),
                    t.getUserId() != null ? t.getUserId().toString() : null,
                    userEmail,
                    seesPayroll ? t.getHourlyRate() : null,
                    seesPayroll ? t.getDispatchAllowance() : null,
                    t.isActive(), t.getNotes());
        }
    }

    /**
     * La liste des gens que l'on peut envoyer en sortie ou rattacher à un dossier :
     * les comptes techniciens ACTIFS, extraits de `users`. Le chef d'atelier ne
     * tape plus un nom, il choisit une personne qui existe et qui se connecte.
     *
     * Aucun montant n'apparaît ici, et c'est le point : ni taux horaire ni forfait
     * de sortie. Cette liste s'ouvre souvent, parfois devant un écran qui reste
     * allumé ; la paie se lit sur la fiche, par ADMIN et CHEF_ATELIER seuls.
     *
     * {@code technicianId} est l'identifiant de la fiche de paie reliée à ce compte,
     * ou null quand la fiche n'existe pas encore — elle est créée au premier choix.
     * Le formulaire de sortie en a besoin, car une sortie engage la fiche (c'est
     * elle qui porte le taux), pas le compte.
     */
    public record TechnicianLookup(String userId, String name, String email, String technicianId) {}
}
