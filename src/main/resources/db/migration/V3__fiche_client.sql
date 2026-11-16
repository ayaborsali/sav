-- =============================================================================
-- V3__fiche_client.sql — la fiche client reprend les colonnes du fichier
-- « Modele_Fichier_Client_Transport.xlsx » utilisé par le service commercial.
--
-- Huit colonnes ajoutées, toutes nullables : les six clients déjà en base
-- restent valides sans ressaisie. annual_revenue et payment_terms sont
-- confidentiels : ils ne sortent jamais vers le rôle consultation
-- (masquage appliqué dans ClientController, pas seulement dans l'interface).
-- =============================================================================

ALTER TABLE clients ADD COLUMN IF NOT EXISTS contact_role     character varying(120);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS country          character varying(80);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS last_contact_at  date;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS sales_rep        character varying(120);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS commercial_status character varying(30);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS products         character varying(500);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS annual_revenue   numeric(14,2);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS payment_terms    character varying(120);

CREATE INDEX IF NOT EXISTS idx_clients_commercial_status ON clients (commercial_status);
