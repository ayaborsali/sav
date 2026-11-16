package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fiche technicien : la ressource humaine, pas le compte de connexion.
 *
 * Le lien vers {@code users} est optionnel : un technicien sans compte peut être
 * affecté à une sortie (le chef d'atelier remplit alors pour lui), mais ses heures
 * ne peuvent alors pas être horodatées par lui-même.
 *
 * hourly_rate et dispatch_allowance sont des données de PAIE : elles ne sortent du
 * serveur que pour ADMIN et CHEF_ATELIER.
 */
@Entity
@Table(name = "technicians")
@Getter @Setter @NoArgsConstructor
public class Technician {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(name = "user_id")
    private UUID userId;

    /** Ce que Saphir paie au technicien pour une heure travaillée. */
    @Column(name = "hourly_rate", precision = 8, scale = 3)
    private BigDecimal hourlyRate;

    /** Prime forfaitaire accordée par sortie, indépendante de sa durée. */
    @Column(name = "dispatch_allowance", precision = 10, scale = 2)
    private BigDecimal dispatchAllowance;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 500)
    private String notes;
}
