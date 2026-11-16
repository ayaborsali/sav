package tn.higer.sav_backend.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.higer.sav_backend.dto.AuditDtos.AuditLogResponse;
import tn.higer.sav_backend.repository.AuditLogRepository;

/**
 * Consultation de la piste d'audit (lecture seule, paginée).
 * Fichier dédié : n'apporte aucune modification à AdminUserController / AdminSessionController.
 * L'accès est déjà restreint à ADMIN par SecurityConfig (règle /api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private final AuditLogRepository auditLogs;

    public AdminAuditController(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @GetMapping
    public Page<AuditLogResponse> list(
            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable) {
        return auditLogs.findAll(pageable).map(AuditLogResponse::from);
    }
}
