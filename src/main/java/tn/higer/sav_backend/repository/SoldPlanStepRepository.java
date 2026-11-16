package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;

import java.util.List;
import java.util.Optional;

public interface SoldPlanStepRepository extends JpaRepository<SoldPlanStep, String> {

    /**
     * Les identifiants de paliers du catalogue engagés chez un client, hors
     * forfaits annulés. C'est ce que {@code MaintenancePlanService} consulte avant
     * de retirer ou renommer un palier : un forfait vendu pointe un palier par son
     * identifiant, et un palier qui change d'identifiant laisse le suivi du bus
     * pointer dans le vide.
     */
    @Query("select s.step.id from SoldPlanStep s join s.soldPlan p where p.status <> ?1")
    List<String> findEngagedStepIds(SoldPlanStatus excludedStatus);

    /**
     * Le suivi d'un palier dans un forfait donné. C'est la vérification qu'un
     * dossier de visite ne peut pas rattacher un palier d'un autre forfait — et
     * encore moins d'un autre bus : un palier n'existe que dans le forfait où il a
     * été vendu.
     */
    Optional<SoldPlanStep> findBySoldPlanIdAndStepId(String soldPlanId, String stepId);
}
