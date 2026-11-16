package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MissionGroupBus;

import java.util.List;

public interface MissionGroupBusRepository extends JpaRepository<MissionGroupBus, String> {

    List<MissionGroupBus> findByDossierIdOrderByPositionAsc(String dossierId);

    /** L'historique d'un bus, toutes marques d'écriture confondues. */
    List<MissionGroupBus> findByRegistrationKeyOrderByCreatedAtDesc(String registrationKey);
}
