-- =============================================================================
-- V53__la_fiche_atelier_se_joint_au_dossier.sql — la fiche papier suit le dossier
--
-- La fiche atelier existe en deux formes : la liste tapée ligne par ligne, que
-- l'homme coche sur place, et le papier (ou le PDF) d'origine, que l'atelier
-- joint au dossier. La liste se lit et se coche ; la pièce jointe se relit,
-- pour l'atelier comme pour le technicien affecté.
--
-- Comme les photos : le fichier part sur le disque, la base ne garde que le
-- chemin, le nom d'origine, le type et l'empreinte SHA-256 — c'est elle qui
-- prouve que la fiche versée n'a pas changé.
-- =============================================================================

CREATE TABLE IF NOT EXISTS mission_group_attachments (
    id            character varying(50)  NOT NULL,
    dossier_id    character varying(50)  NOT NULL,
    file_path     character varying(400) NOT NULL,
    original_name character varying(255),
    content_type  character varying(60),
    size_bytes    bigint,
    sha256        character varying(64)  NOT NULL,
    uploaded_at   timestamp without time zone NOT NULL,
    actor_email   character varying(255),
    CONSTRAINT mission_group_attachments_pkey PRIMARY KEY (id),
    CONSTRAINT mission_group_attachments_dossier_fkey
        FOREIGN KEY (dossier_id) REFERENCES mission_group_dossiers (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mission_group_attachments_dossier
    ON mission_group_attachments (dossier_id);
