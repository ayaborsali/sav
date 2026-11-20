package tn.higer.sav_backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.higer.sav_backend.model.ServiceVehicle;
import tn.higer.sav_backend.model.ServiceVehicleStatus;

import java.util.List;
import java.util.Optional;

public interface ServiceVehicleRepository extends JpaRepository<ServiceVehicle, String> {

    /** La ligne de la voiture, tenue jusqu'à la validation : la même règle que pour un homme. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ServiceVehicle v where v.id = :id")
    Optional<ServiceVehicle> findByIdForUpdate(@Param("id") String id);

    List<ServiceVehicle> findAllByOrderByActiveDescRegistrationAsc();

    /** Le chef d'atelier ne choisit que ce qui est disponible : c'est ce filtre qui évite d'affecter la même voiture deux fois. */
    List<ServiceVehicle> findByActiveTrueAndStatusInOrderByRegistrationAsc(List<ServiceVehicleStatus> statuses);

    Optional<ServiceVehicle> findByRegistrationIgnoreCase(String registration);

    boolean existsByRegistrationIgnoreCase(String registration);

    boolean existsByRegistrationIgnoreCaseAndIdNot(String registration, String id);
}
