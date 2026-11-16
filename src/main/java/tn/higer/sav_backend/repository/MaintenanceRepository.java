package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.MaintenanceItem;

import java.util.Collection;
import java.util.List;

public interface MaintenanceRepository extends JpaRepository<MaintenanceItem, String> {

    boolean existsByNumberIgnoreCase(String number);

    boolean existsByNumberIgnoreCaseAndIdNot(String number, String id);

    /**
     * TOUS les numéros de l'année. Le plus haut se décide en Java, dans l'ordre
     * numérique : un tri SQL par longueur retiendrait « -0028 » avant « -029 » et
     * rendrait un numéro déjà posé.
     */
    @Query("SELECT m.number FROM MaintenanceItem m WHERE m.number LIKE CONCAT(:prefix, '%')")
    List<String> findNumbersByPrefix(String prefix);

    /** Les papiers nés de ce papier — un devis qui a déjà donné son ordre, un ordre qui a déjà rendu son bus. */
    List<MaintenanceItem> findBySourceItemId(String sourceItemId);

    /** Tous les papiers d'une demande, dans l'ordre où ils ont été écrits : c'est la chaîne qu'on rend à l'écran. */
    List<MaintenanceItem> findByMissionIdOrderByDateAscNumberAsc(String missionId);

    List<MaintenanceItem> findByMissionIdInOrderByDateAscNumberAsc(Collection<String> missionIds);
}
