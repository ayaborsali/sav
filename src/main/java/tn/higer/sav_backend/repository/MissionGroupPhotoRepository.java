package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MissionGroupPhoto;
import tn.higer.sav_backend.model.MissionGroupPhotoAngle;

import java.util.List;

public interface MissionGroupPhotoRepository extends JpaRepository<MissionGroupPhoto, String> {

    List<MissionGroupPhoto> findByDossierIdOrderByUploadedAtAsc(String dossierId);

    boolean existsByDossierIdAndSha256(String dossierId, String sha256);

    boolean existsByDossierIdAndAngle(String dossierId, MissionGroupPhotoAngle angle);

    /** Une preuve accrochée à une ligne de bus : la correction recrée ces lignes, la photo resterait sans support. */
    boolean existsByDossierIdAndBusIdIsNotNull(String dossierId);

    boolean existsByDossierIdAndBusIdAndAngle(String dossierId, String busId, MissionGroupPhotoAngle angle);
}
