package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.InterventionChecklistItem;

import java.util.Collection;
import java.util.List;

public interface InterventionChecklistItemRepository extends JpaRepository<InterventionChecklistItem, String> {

    List<InterventionChecklistItem> findByInterventionIdOrderByPositionAsc(String interventionId);

    List<InterventionChecklistItem> findByInterventionIdIn(Collection<String> interventionIds);
}
