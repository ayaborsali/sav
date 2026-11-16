package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStatus;

import java.util.List;

public interface SoldPlanRepository extends JpaRepository<SoldPlan, String> {

    List<SoldPlan> findAllByOrderByStartDateDesc();

    /**
     * Les forfaits ACTIVE du même plan pour ce bus : la liste à comparer date à
     * date avant d'enregistrer une vente, pour ne pas vendre deux fois la même
     * période.
     *
     * Le filtre est dans le nom de la requête et non dans un « :status is null » :
     * Hibernate ne sait pas typer un paramètre de statut vide, et la règle, elle,
     * ne porte que sur les forfaits actifs.
     */
    List<SoldPlan> findByVehicleIdAndPlanIdAndStatus(String vehicleId, String planId, SoldPlanStatus status);

    /**
     * Les forfaits dont le serveur doit s'occuper tout seul : un rappel de palier
     * ne se pose que sur un forfait ACTIF. Un forfait clos ou annulé a rendu ses
     * comptes, et ses paliers ne sont plus dus.
     */
    List<SoldPlan> findByStatus(SoldPlanStatus status);
}
