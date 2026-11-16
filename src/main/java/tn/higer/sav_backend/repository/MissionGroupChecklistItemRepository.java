package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MissionGroupChecklistItem;

import java.util.List;

public interface MissionGroupChecklistItemRepository extends JpaRepository<MissionGroupChecklistItem, String> {

    List<MissionGroupChecklistItem> findByDossierIdOrderByPositionAsc(String dossierId);
}
