package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.PushVapidKey;

public interface PushVapidKeyRepository extends JpaRepository<PushVapidKey, String> {
}
