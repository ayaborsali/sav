package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.InterventionPartRequest;

import java.util.List;

public interface InterventionPartRequestRepository extends JpaRepository<InterventionPartRequest, String> {

    /** Les pièces que cette sortie a demandées, dans l'ordre de la saisie. */
    List<InterventionPartRequest> findByInterventionIdOrderByCreatedAtAsc(String interventionId);

    /** Idem en lecture groupée : une liste de sorties ne se relit pas ligne par ligne. */
    List<InterventionPartRequest> findByInterventionIdInOrderByCreatedAtAsc(List<String> interventionIds);

    /** Les pièces d'une réclamation : le dossier doit montrer ce qui attend, pas seulement ce qui est écrit. */
    List<InterventionPartRequest> findBySavCaseIdOrderByCreatedAtAsc(String savCaseId);

    /** Les pièces demandées pour un bus, la plus récente en tête (fiche du bus). */
    List<InterventionPartRequest> findByVehicleIdOrderByCreatedAtDesc(String vehicleId);

    /**
     * Les pièces que cette mission est allée poser. Lecture de la clôture : c'est par
     * la mission de pose que la seconde sortie retrouve les lignes à marquer « Posée »,
     * sans deviner laquelle est montée.
     */
    List<InterventionPartRequest> findByFittingMissionId(String fittingMissionId);

    /** Idem en lecture groupée, pour rendre une liste de lignes sans N+1. */
    List<InterventionPartRequest> findByFittingMissionIdIn(List<String> fittingMissionIds);
}
