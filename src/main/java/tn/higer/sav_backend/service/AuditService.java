package tn.higer.sav_backend.service;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import tn.higer.sav_backend.model.AuditLog;
import tn.higer.sav_backend.repository.AuditLogRepository;

import java.time.LocalDateTime;

/**
 * Écrit une ligne de piste d'audit pour chaque opération qui modifie l'état.
 * L'auteur est lu dans le jeton exactement comme le fait SessionJwtConverter :
 * subject = identifiant utilisateur, claim « email » = adresse de l'utilisateur connecté.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogs;

    public AuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    public void record(String action, String entityType, String entityId, String detail, Jwt jwt) {
        AuditLog log = new AuditLog();
        if (jwt != null) {
            log.setActorUserId(jwt.getSubject());
            log.setActorEmail(jwt.getClaimAsString("email"));
        }
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetail(detail);
        log.setTimestamp(LocalDateTime.now());
        auditLogs.save(log);
    }
}
