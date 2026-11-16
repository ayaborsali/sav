package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.PhotoAngle;

import java.util.List;

public interface InterventionPhotoRepository extends JpaRepository<InterventionPhoto, String> {

    List<InterventionPhoto> findByInterventionIdOrderByUploadedAtAsc(String interventionId);

    List<InterventionPhoto> findByInterventionIdInOrderByUploadedAtAsc(List<String> interventionIds);

    /** Une preuve existe-t-elle pour cet angle ? C'est la question de la clôture. */
    boolean existsByInterventionIdAndAngle(String interventionId, PhotoAngle angle);

    /** Le même fichier versé deux fois (sur deux sorties ou sur la même). */
    boolean existsBySha256(String sha256);

    long countByInterventionId(String interventionId);
}
