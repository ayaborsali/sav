package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.PushSent;

public interface PushSentRepository extends JpaRepository<PushSent, String> {

    /** La même annonce, sur le même dossier, pour la même référence : déjà partie. */
    boolean existsByKindAndTargetIdAndReference(String kind, String targetId, String reference);
}
