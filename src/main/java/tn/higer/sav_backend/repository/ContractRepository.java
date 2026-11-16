package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.Contract;

import java.util.List;

public interface ContractRepository extends JpaRepository<Contract, String> {

    /**
     * TOUS les numéros de l'année. Le plus haut se décide en Java, dans l'ordre
     * numérique : un tri SQL par longueur retiendrait « -0028 » avant « -029 » et
     * rendrait un numéro déjà posé.
     */
    @Query("SELECT c.number FROM Contract c WHERE c.number LIKE CONCAT(:prefix, '%')")
    List<String> findNumbersByPrefix(String prefix);
}
