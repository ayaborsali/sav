package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.ImageEntity;

import java.util.List;

public interface ImageRepository extends JpaRepository<ImageEntity, String> {

    List<ImageEntity> findAllByOrderByUploadedAtDesc();

    List<ImageEntity> findByDossierIdOrderByUploadedAtDesc(String dossierId);
}
