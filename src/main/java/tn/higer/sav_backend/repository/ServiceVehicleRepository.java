package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.ServiceVehicle;
import tn.higer.sav_backend.model.ServiceVehicleStatus;

import java.util.List;
import java.util.Optional;

public interface ServiceVehicleRepository extends JpaRepository<ServiceVehicle, String> {

    List<ServiceVehicle> findAllByOrderByActiveDescRegistrationAsc();

    /** Le chef d'atelier ne choisit que ce qui est disponible : c'est ce filtre qui évite d'affecter la même voiture deux fois. */
    List<ServiceVehicle> findByActiveTrueAndStatusInOrderByRegistrationAsc(List<ServiceVehicleStatus> statuses);

    Optional<ServiceVehicle> findByRegistrationIgnoreCase(String registration);

    boolean existsByRegistrationIgnoreCase(String registration);

    boolean existsByRegistrationIgnoreCaseAndIdNot(String registration, String id);
}
