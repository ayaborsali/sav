package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.model.VehicleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class VehicleDtos {

    private VehicleDtos() {}

    /**
     * Création / modification d'un véhicule (admin et gestionnaire_sav).
     * Le statut arrive en minuscules depuis Angular (« in_workshop ») —
     * accept-case-insensitive-enums est activé dans application.properties.
     *
     * {@code mileageMotif} ne sert que dans un cas : remettre le compteur plus bas que
     * ce que le portail sait déjà du bus (compteur remplacé, relevé faux). Sans écrit,
     * la baisse est refusée par la porte du compteur — voir VehicleMileageService.
     */
    public record VehicleRequest(@NotBlank String vin,
                                 @NotBlank String registration,
                                 String model,
                                 Integer year,
                                 @NotBlank String clientId,
                                 Long mileage,
                                 String mileageMotif,
                                 @NotNull LocalDate commissioningDate,
                                 // Facultatif : si absente, la date de fin de garantie est calculée
                                 // côté serveur (voir VehicleController.apply).
                                 LocalDate warrantyEndDate,
                                 String motorization,
                                 VehicleStatus status,
                                 String notes,
                                 // Rattachement au contrat de maintenance. Un véhicule sous contrat doit
                                 // porter un prix unitaire HT (le forfait de la prestation ; règle
                                 // appliquée côté serveur).
                                 String contractId,
                                 @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal unitPriceHt,
                                 String plateImage) {}

    /**
     * @param mileageOrigin d'où vient le compteur posé sur la fiche : ce que l'atelier
     *                      lit pour ne pas discuter un chiffre sans savoir qui l'a écrit.
     */
    public record VehicleResponse(String id, String vin, String registration, String model,
                                  Integer year, String clientId, Long mileage,
                                  String mileageOrigin, LocalDateTime mileageRecordedAt,
                                  String mileageRecordedBy, String mileageCorrectionReason,
                                  LocalDate commissioningDate, LocalDate warrantyEndDate,
                                  String motorization, String status, String notes,
                                  String contractId, BigDecimal unitPriceHt, String plateImage) {

        public static VehicleResponse from(Vehicle v) {
            return from(v, true);
        }

        /** unitPriceHt n'est servi qu'aux rôles habilités (voir security.Access). */
        public static VehicleResponse from(Vehicle v, boolean includePrice) {
            return new VehicleResponse(
                    v.getId(), v.getVin(), v.getRegistration(), v.getModel(),
                    v.getYear(), v.getClientId(), v.getMileage(),
                    origin(v), v.getMileageRecordedAt(), v.getMileageRecordedBy(),
                    v.getMileageCorrectionReason(),
                    v.getCommissioningDate(), v.getWarrantyEndDate(), v.getMotorization(),
                    v.getStatus() != null ? v.getStatus().name().toLowerCase() : null,
                    v.getNotes(), v.getContractId(),
                    includePrice ? v.getUnitPriceHt() : null,
                    v.getPlateImage());
        }
    }

    /**
     * Projection légère renvoyée par la liste paginée GET /api/vehicles.
     * Elle omet plateImage (blob base64) : l'image de la plaque n'est plus expédiée
     * dans chaque ligne de liste, elle reste accessible via GET /api/vehicles/{id}.
     */
    public record VehicleSummary(String id, String vin, String registration, String model,
                                 Integer year, String clientId, Long mileage,
                                 String mileageOrigin, LocalDateTime mileageRecordedAt,
                                 String mileageRecordedBy,
                                 LocalDate commissioningDate, LocalDate warrantyEndDate,
                                 String motorization, String status, String notes,
                                 String contractId, BigDecimal unitPriceHt) {

        public static VehicleSummary from(Vehicle v) {
            return from(v, true);
        }

        public static VehicleSummary from(Vehicle v, boolean includePrice) {
            return new VehicleSummary(
                    v.getId(), v.getVin(), v.getRegistration(), v.getModel(),
                    v.getYear(), v.getClientId(), v.getMileage(),
                    origin(v), v.getMileageRecordedAt(), v.getMileageRecordedBy(),
                    v.getCommissioningDate(), v.getWarrantyEndDate(), v.getMotorization(),
                    v.getStatus() != null ? v.getStatus().name().toLowerCase() : null,
                    v.getNotes(), v.getContractId(),
                    includePrice ? v.getUnitPriceHt() : null);
        }
    }

    /**
     * L'origine du compteur, en français lisible. Une fiche sans origine est une fiche
     * créée avant cette règle : elle le dit, au lieu de faire croire à une saisie sûre.
     */
    private static String origin(Vehicle v) {
        return v.getMileageOrigin() == null ? null : v.getMileageOrigin().label();
    }

    /**
     * Réponse de POST /api/vehicles/detect-vin (lecture IA du châssis sur une photo).
     * {@code status} vaut « complete », « partial » ou « none ». Un VIN « complete » a déjà passé
     * le contrôle de format du serveur : c'est le seul qui peut pré-remplir le formulaire.
     * {@code message} est écrit ici, en dur : un toast de quelques secondes n'est pas relisible.
     */
    public record VinDetectionResponse(String status, String vin, String partial,
                                       Integer readChars, boolean alreadyRegistered,
                                       String existingVehicleId, String existingRegistration,
                                       String message) {}
}
