-- =============================================================================
-- V2__audit_log.sql — table de la piste d'audit
--
-- Ajoutée après V1 : l'entité AuditLog (tn.higer.sav_backend.model.AuditLog)
-- enregistre une ligne par écriture (CREATE/UPDATE/DELETE/VALIDATE/REJECT).
-- Colonnes en snake_case, varchar dimensionnés comme les annotations @Column.
-- =============================================================================

CREATE TABLE IF NOT EXISTS audit_logs (
    id             uuid                       NOT NULL,
    actor_user_id  character varying(255),
    actor_email    character varying(255),
    action         character varying(30)      NOT NULL,
    entity_type    character varying(40)      NOT NULL,
    entity_id      character varying(50),
    detail         character varying(1000),
    timestamp      timestamp without time zone NOT NULL,
    CONSTRAINT audit_logs_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
