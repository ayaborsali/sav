package tn.higer.sav_backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "vehicles")
@Getter @Setter @NoArgsConstructor
public class Vehicle {

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, unique = true)
    private String vin;

    @Column(nullable = false)
    private String registration;

    private String model;

    private Integer year;

    @Column(nullable = false)
    private String clientId;

    private Long mileage;

    /**
     * Ce qui a écrit {@code mileage}. Nulle part ailleurs dans le code ce champ est
     * posé : la seule porte est {@code VehicleMileageService} (phase G).
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private MileageOrigin mileageOrigin;

    /** Le moment où le portail a appris cette valeur — pas la date du voyage du bus. */
    private LocalDateTime mileageRecordedAt;

    /** L'adresse du compte qui a écrit le compteur (atelier), ou le nom du technicien. */
    @Column(length = 255)
    private String mileageRecordedBy;

    /** Motif écrit obligatoire quand le compteur est volontairement remis plus bas. */
    @Column(length = 500)
    private String mileageCorrectionReason;

    private LocalDate commissioningDate;
    private LocalDate warrantyEndDate;

    private String motorization;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private VehicleStatus status;

    @Column(length = 1000)
    private String notes;

    /** Contrat auquel ce véhicule est rattaché (null = vendu hors contrat). */
    @Column(length = 50)
    private String contractId;

    /** Prix unitaire HT convenu au contrat, en dinar tunisien. Confidentiel : masqué en consultation. */
    @Column(precision = 14, scale = 2)
    private BigDecimal unitPriceHt;

    @Column(columnDefinition = "TEXT")
    private String plateImage;
}
