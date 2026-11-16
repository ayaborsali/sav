package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface InterventionRepository extends JpaRepository<Intervention, String> {

    /**
     * TOUS les numéros de l'année. Le plus haut se décide en Java, dans l'ordre
     * numérique : un tri SQL par longueur retiendrait « -0028 » avant « -029 » et
     * rendrait un numéro déjà posé.
     */
    @Query("SELECT i.number FROM Intervention i WHERE i.number LIKE CONCAT(:prefix, '%')")
    List<String> findNumbersByPrefix(String prefix);

    /** Écran « Sorties du jour » : tout ce qui est sorti depuis une date, dans l'ordre d'appel. */
    List<Intervention> findByReportedAtGreaterThanEqualOrderByReportedAtDesc(LocalDateTime from);

    List<Intervention> findByStatusInOrderByReportedAtDesc(List<InterventionStatus> statuses);

    /** Les sorties rattachées à un dossier SAV, dans l'ordre chronologique d'appel. */
    List<Intervention> findBySavCaseIdOrderByReportedAtAsc(String savCaseId);

    /**
     * La sortie dont un ordre de travail est né, quand aucune mission ne porte le lien
     * (un ordre écrit à la clôture d'une sortie de rappel). La plus récente gagne :
     * c'est elle qui a les photos que l'atelier relit.
     */
    List<Intervention> findByMaintenanceItemIdOrderByReportedAtDesc(String maintenanceItemId);

    /** « Mes interventions » du technicien : uniquement les siennes, jamais celles d'un autre. */
    List<Intervention> findByTechnicianIdOrderByReportedAtDesc(String technicianId);

    List<Intervention> findByTechnicianIdAndStatusInOrderByReportedAtDesc(
            String technicianId, List<InterventionStatus> statuses);

    /** Sert à refuser une seconde sortie ouverte pour le même technicien. */
    boolean existsByTechnicianIdAndStatusIn(String technicianId, List<InterventionStatus> statuses);

    boolean existsByServiceVehicleIdAndStatusIn(String serviceVehicleId, List<InterventionStatus> statuses);

    /** Un bus ne peut pas avoir deux sorties ouvertes en parallèle. */
    boolean existsByVehicleIdAndStatusIn(String vehicleId, List<InterventionStatus> statuses);

    /** La même chose, avec la sortie à nommer dans le refus : « clôturez DP-2026-003 » se comprend. */
    List<Intervention> findByVehicleIdAndStatusInOrderByReportedAtDesc(
            String vehicleId, List<InterventionStatus> statuses);

    long countByStatus(InterventionStatus status);

    /**
     * Les sorties de ce bus qui portent un compteur relu. La porte du compteur
     * ({@code VehicleMileageService}) en tire le plus haut, et surtout la sortie qui
     * l'a lu : refuser une saisie en disant seulement « chiffre trop bas » ne se corrige
     * pas à l'atelier. Le tri par heure d'appel laisse, à valeur égale, la sortie la
     * plus récente gagner.
     */
    @Query("SELECT i FROM Intervention i WHERE i.vehicleId = :vehicleId"
            + " AND (i.busMileage IS NOT NULL OR i.busMileageEnd IS NOT NULL)"
            + " ORDER BY i.reportedAt DESC")
    List<Intervention> findRelevesBus(String vehicleId);
}
