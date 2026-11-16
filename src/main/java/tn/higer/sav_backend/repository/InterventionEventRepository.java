package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.InterventionEvent;

import java.util.List;
import java.util.UUID;

/**
 * Le journal ne se lit et ne s'écrit qu'en ajout : aucune méthode de mise à jour
 * ni de suppression n'existe ici, et le service n'en appelle pas. Une ligne
 * erronée se corrige par une ligne CORRECTED, pas en réécrivant l'historique.
 */
public interface InterventionEventRepository extends JpaRepository<InterventionEvent, UUID> {

    List<InterventionEvent> findByInterventionIdOrderByRecordedAtAsc(String interventionId);

    List<InterventionEvent> findByInterventionIdInOrderByRecordedAtAsc(List<String> interventionIds);
}
