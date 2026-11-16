package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.MissionGroupAttachment;

import java.util.List;

public interface MissionGroupAttachmentRepository extends JpaRepository<MissionGroupAttachment, String> {

    List<MissionGroupAttachment> findByDossierIdOrderByUploadedAtAsc(String dossierId);

    boolean existsByDossierIdAndSha256(String dossierId, String sha256);
}
