package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.ItemPart;

import java.util.List;

public interface ItemPartRepository extends JpaRepository<ItemPart, String> {

    List<ItemPart> findByMaintenanceItemIdOrderByFittedOnAsc(String maintenanceItemId);

    /** Les pièces posées sur un bus, de la plus récente à la plus ancienne. */
    List<ItemPart> findByVehicleIdOrderByFittedOnDesc(String vehicleId);

    /** Les pièces qu'un dossier garantie réclame au constructeur. */
    List<ItemPart> findBySavCaseId(String savCaseId);
}
