package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.higer.sav_backend.model.PushSubscription;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, String> {

    List<PushSubscription> findByUserId(String userId);

    /** L'adresse est unique : c'est la clé qui rattache un téléphone à un seul compte. */
    Optional<PushSubscription> findByEndpoint(String endpoint);
}
