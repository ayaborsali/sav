package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.SoldPlanAlert;

import java.util.List;

public interface SoldPlanAlertRepository extends JpaRepository<SoldPlanAlert, String> {

    /** Le bandeau de l'atelier : les rappels encore ouverts, le plus ancien d'abord. */
    List<SoldPlanAlert> findByResolvedFalseOrderByDetectedAtAsc();

    /** Tout ce qui a été signalé sur un forfait, ouvert ou non — pour sa fiche. */
    List<SoldPlanAlert> findBySoldPlanIdOrderByDetectedAtDesc(String soldPlanId);

    /** Le scan ne repose pas la même question deux fois : une seule alerte ouverte par palier suivi. */
    boolean existsBySoldPlanStepIdAndResolvedFalse(String soldPlanStepId);

    /** Une ligne de suivi qui se fait, ou un compteur corrigé : son rappel ouvert doit se fermer. */
    List<SoldPlanAlert> findBySoldPlanStepIdAndResolvedFalse(String soldPlanStepId);
}
