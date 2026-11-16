package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.Facture;

import java.util.List;
import java.util.Optional;

public interface FactureRepository extends JpaRepository<Facture, String> {

    boolean existsByBlId(String blId);

    Optional<Facture> findByBlId(String blId);

    boolean existsByNumberIgnoreCase(String number);

    /**
     * Tous les numéros d'un préfixe (« FA-2026- »). Le plus haut se décide en Java
     * dans l'ordre numérique, comme partout ({@code NumberSequence}) : un tri SQL par
     * longueur retiendrait « -0028 » avant « -029 » et rendrait un numéro déjà posé.
     */
    @Query("SELECT f.number FROM Facture f WHERE f.number LIKE CONCAT(:prefix, '%')")
    List<String> findNumbersByPrefix(String prefix);

    /** Les BL déjà facturés : la liste « à facturer » (I02) les exclut. */
    @Query("SELECT f.blId FROM Facture f")
    List<String> findAllBlIds();

    List<Facture> findAllByOrderByIssuedOnDescNumberDesc();
}