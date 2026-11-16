package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.Vehicle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, String> {

    boolean existsByVinIgnoreCase(String vin);

    boolean existsByVinIgnoreCaseAndIdNot(String vin, String id);

    /** Le châssis lu sur une photo est-il déjà au portefeuille ? Une seule ligne, pas de liste. */
    Optional<Vehicle> findByVinIgnoreCase(String vin);

    List<Vehicle> findByContractId(String contractId);

    /** Toutes les lignes des contrats d'une page : une seule requête, pas de N+1. */
    List<Vehicle> findByContractIdIn(Collection<String> contractIds);
}
