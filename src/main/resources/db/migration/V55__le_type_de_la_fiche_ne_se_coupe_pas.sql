-- =============================================================================
-- La fiche atelier peut être un classeur Excel. Son type MIME
-- « application/vnd.openxmlformats-officedocument.spreadsheetml.sheet » fait
-- 65 caractères, la colonne en prévoyait 60 : PostgreSQL refusait l'insertion
-- et le serveur répondait 500 alors que le dossier, lui, était bien enregistré.
-- La largeur est alignée sur celle que déclare l'entité (255).
-- =============================================================================

ALTER TABLE mission_group_attachments
    ALTER COLUMN content_type TYPE character varying(255);
