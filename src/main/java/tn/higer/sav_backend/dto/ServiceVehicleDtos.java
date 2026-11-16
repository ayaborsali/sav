package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tn.higer.sav_backend.model.ServiceVehicle;

/**
 * Flotte de service : ce qui part en intervention. Le statut n'est pas une
 * saisie libre, il suit les sorties (voir InterventionService).
 */
public final class ServiceVehicleDtos {

    private ServiceVehicleDtos() {}

    public record ServiceVehicleRequest(
            @NotBlank @Size(max = 40) String registration,
            @NotBlank @Size(max = 120) String label,
            @NotBlank @Size(max = 30) String kind,
            Long currentMileage,
            @Size(max = 500) String notes,
            Boolean active) {}

    /** Changement de statut manuel (mise à l'atelier, panne) — l'affectation ne passe pas par là. */
    public record ServiceVehicleStatusRequest(@NotBlank String status) {}

    public record ServiceVehicleResponse(String id, String registration, String label, String kind,
                                         String status, Long currentMileage, boolean active, String notes) {

        public static ServiceVehicleResponse from(ServiceVehicle v) {
            return new ServiceVehicleResponse(
                    v.getId(), v.getRegistration(), v.getLabel(), v.getKind(),
                    v.getStatus() != null ? v.getStatus().name().toLowerCase() : null,
                    v.getCurrentMileage(), v.isActive(), v.getNotes());
        }
    }
}
