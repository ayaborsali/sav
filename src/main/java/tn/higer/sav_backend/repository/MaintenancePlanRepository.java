package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MaintenancePlan;

import java.util.List;

public interface MaintenancePlanRepository extends JpaRepository<MaintenancePlan, String> {

    List<MaintenancePlan> findAllByOrderByCreatedAtDesc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, String id);

    /**
     * Les plans qu'un compte peut proposer à une vente : actifs, les retirés
     * restent lisibles dans le catalogue mais disparaissent de cette liste.
     */
    List<MaintenancePlan> findByActiveTrueOrderByBusModelAscNameAsc();
}
