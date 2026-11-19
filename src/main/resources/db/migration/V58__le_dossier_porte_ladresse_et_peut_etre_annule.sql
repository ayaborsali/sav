-- =============================================================================
-- V58 — le dossier porte l'adresse du jour, et il peut être annulé.
--
-- 1. L'adresse, la ville et le téléphone du client sont recopiés sur le dossier
--    au moment où le papier se prépare, comme le nom l'est déjà. Un client qui
--    déménage ou change de numéro ne réécrit pas l'histoire d'un déplacement
--    déjà fait : l'homme part avec l'adresse du jour du papier.
--    Les dossiers déjà écrits reprennent l'adresse du portefeuille d'aujourd'hui
--    — c'est la seule qui existe encore pour eux, et elle vaut mieux que rien.
--
-- 2. Un dossier préparé qui ne se fera pas s'annule, avec un motif écrit. Il
--    garde sa ligne et son numéro — rien ne s'efface — mais il ne tient plus
--    l'homme ni la voiture dans les disponibilités, et il ne réveille plus le
--    téléphone. Sans cela, une visite reportée bloquait le planning pour
--    toujours, puisque seule la rentrée libère une mission.
-- =============================================================================

ALTER TABLE mission_group_dossiers
    ADD COLUMN IF NOT EXISTS client_address character varying(255),
    ADD COLUMN IF NOT EXISTS client_city    character varying(255),
    ADD COLUMN IF NOT EXISTS client_phone   character varying(255),
    ADD COLUMN IF NOT EXISTS cancelled_at   timestamp without time zone,
    ADD COLUMN IF NOT EXISTS cancel_reason  character varying(500),
    ADD COLUMN IF NOT EXISTS cancelled_by   character varying(255);

UPDATE mission_group_dossiers d
   SET client_address = c.address,
       client_city    = c.city,
       client_phone   = c.phone
  FROM clients c
 WHERE c.id = d.client_id
   AND d.client_address IS NULL;
