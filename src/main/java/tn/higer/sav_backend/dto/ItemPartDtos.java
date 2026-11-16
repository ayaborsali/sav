package tn.higer.sav_backend.dto;

import jakarta.validation.constraints.NotNull;
import tn.higer.sav_backend.model.ItemPart;

import java.time.LocalDate;

public final class ItemPartDtos {

    private ItemPartDtos() {}

    /**
     * Une pièce posée pendant un papier de maintenance. Le bus n'est pas demandé :
     * il est celui du document, et le serveur l'écrit lui-même.
     */
    public record ItemPartRequest(String reference,
                                  String designation,
                                  @NotNull Double quantity,
                                  LocalDate fittedOn) {}

    public record ItemPartResponse(String id, String maintenanceItemId, String vehicleId,
                                   String savCaseId, String reference, String designation,
                                   Double quantity, LocalDate fittedOn, boolean returned,
                                   LocalDate returnedAt, String returnReason) {

        public static ItemPartResponse from(ItemPart p) {
            return new ItemPartResponse(p.getId(), p.getMaintenanceItemId(), p.getVehicleId(),
                    p.getSavCaseId(), p.getReference(), p.getDesignation(), p.getQuantity(),
                    p.getFittedOn(), p.isReturned(), p.getReturnedAt(), p.getReturnReason());
        }
    }

    /**
     * Le sort de la pièce déposée : elle est repartie chez le constructeur, ou elle
     * n'y est pas partie et ça s'écrit. Une seule des deux branches est licite, et
     * le service la contrôle — « la pièce est retournée » sans date, ou « pas
     * retournée » sans raison, ne défend rien devant un contrôleur.
     */
    public record RetourRequest(boolean envoyee, LocalDate date, String motif) {}
}
