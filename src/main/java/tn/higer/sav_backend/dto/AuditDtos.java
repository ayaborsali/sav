package tn.higer.sav_backend.dto;

import tn.higer.sav_backend.model.AuditLog;

import java.time.LocalDateTime;

public final class AuditDtos {

    private AuditDtos() {}

    /** Représentation exposée par GET /api/admin/audit (réservé ADMIN). */
    public record AuditLogResponse(String id, String actorUserId, String actorEmail,
                                   String action, String entityType, String entityId,
                                   String detail, LocalDateTime timestamp) {

        public static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(
                    a.getId() != null ? a.getId().toString() : null,
                    a.getActorUserId(), a.getActorEmail(), a.getAction(), a.getEntityType(),
                    a.getEntityId(), a.getDetail(), a.getTimestamp());
        }
    }
}
