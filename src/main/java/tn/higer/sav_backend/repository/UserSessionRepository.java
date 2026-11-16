package tn.higer.sav_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import tn.higer.sav_backend.model.UserSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /** Sessions encore valables (non terminées, non expirées), les plus récentes d'abord. */
    List<UserSession> findByRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(LocalDateTime now);

    /** Termine toutes les sessions encore ouvertes d'un utilisateur. Renvoie leur nombre. */
    @Modifying
    @Transactional
    @Query("update UserSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllByUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}