-- =============================================================================
-- V50__mission_groupe_affectation.sql — le dossier préparé, puis pointé
--
-- Le dossier ne se remplit plus d'un seul geste : l'atelier le prépare, le
-- technicien le vit.
--
--  * l'atelier choisit le client dans le portefeuille, puis ses bus, écrit le
--    problème de chaque bus, affecte l'homme et la voiture de déplacement, et
--    fixe le départ prévu (demain 8 h, par exemple) ;
--  * le technicien affecté pointe le départ réel, le kilométrage de la voiture
--    et la photo avant ; plus tard il pointe le retour, le kilométrage et la
--    photo après, avec le diagnostic de chaque bus.
--
-- Departure_mileage et departed_at deviennent donc facultatifs : un dossier
-- préparé n'a pas encore de départ réel. L'état se lit toujours sur les dates —
-- « prévu », « en déplacement », « rentré » — et ne s'écrit nulle part.
--
-- Le client, l'homme et la voiture sont gardés par identifiant, avec le nom et
-- l'immatriculation recopiés au moment du papier : un compte renommé ou une
-- voiture réformée ne réécrit pas l'histoire d'un dossier déjà parti.
-- =============================================================================

ALTER TABLE mission_group_dossiers
    ALTER COLUMN departed_at DROP NOT NULL,
    ALTER COLUMN departure_mileage DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS client_id                    character varying(64),
    ADD COLUMN IF NOT EXISTS planned_departure_at         timestamp without time zone,
    ADD COLUMN IF NOT EXISTS technician_user_id           character varying(64),
    ADD COLUMN IF NOT EXISTS technician_name              character varying(255),
    ADD COLUMN IF NOT EXISTS service_vehicle_id           character varying(64),
    ADD COLUMN IF NOT EXISTS service_vehicle_registration character varying(120);

-- Un dossier déjà écrit avant cette version garde sa date de départ comme départ
-- prévu : il n'a pas d'autre heure à montrer.
UPDATE mission_group_dossiers
   SET planned_departure_at = departed_at
 WHERE planned_departure_at IS NULL
   AND departed_at IS NOT NULL;

ALTER TABLE mission_group_buses
    ADD COLUMN IF NOT EXISTS vehicle_id character varying(64);

CREATE INDEX IF NOT EXISTS idx_mission_group_dossiers_client
    ON mission_group_dossiers (client_id);

-- Le technicien ne lit que ses dossiers : la liste se filtre sur cette colonne.
CREATE INDEX IF NOT EXISTS idx_mission_group_dossiers_technician
    ON mission_group_dossiers (technician_user_id);

CREATE INDEX IF NOT EXISTS idx_mission_group_dossiers_planned
    ON mission_group_dossiers (planned_departure_at);
