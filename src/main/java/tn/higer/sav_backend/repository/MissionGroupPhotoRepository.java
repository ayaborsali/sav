package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MissionGroupPhoto;
import tn.higer.sav_backend.model.MissionGroupPhotoAngle;

import java.util.List;

public interface MissionGroupPhotoRepository extends JpaRepository<MissionGroupPhoto, String> {

    List<MissionGroupPhoto> findByDossierIdOrderByUploadedAtAsc(String dossierId);

    boolean existsByDossierIdAndSha256(String dossierId, String sha256);

    boolean existsByDossierIdAndAngle(String dossierId, MissionGroupPhotoAngle angle);

    boolean existsByDossierIdAndBusIdAndAngle(String dossierId, String busId, MissionGroupPhotoAngle angle);
}
