-- =============================================================================
-- V27__papier_ag.sql — les cases du papier d'appel en garantie
--
-- Le dossier garantie savait déjà dire quatre choses (V20, V22) : l'état de la
-- demande, la décision écrite du constructeur, sa date, et les deux montants
-- demandés / accordés. Le papier AG que Higer attend en demande trente fois plus.
--
-- Ce fichier n'ajoute QUE ce qui n'avait pas de maison. Ce qui existe déjà sous
-- un autre nom reste à sa place, parce que deux cases pour la même information
-- finissent toujours par se contredire :
--   claim N°            -> case_number   (numéroté par le serveur)
--   Plainte             -> description   (le constat)
--   Correction          -> resolution
--   Décision constructeur -> claim_decision (V22)
--   Coût                -> claim_amount_claimed (V22)
--   Pièces remplacées / matériel inclus -> les lignes de pièces posées (V21)
--   Retour pièces       -> le sort de chaque ligne (V21)
--   Statut              -> status + claim_state
--   Date de clôture     -> closed_at (V25)
--   Date de rédaction AG -> declaration_date (V23, partagée avec l'onglet qualité)
--   Véhicule, modèle, VIN, gamme -> la fiche du bus, en lecture seule sur le papier
--
-- Les codes que seul le constructeur détient (importateur, dealer, unit, CAM,
-- code défaut, code d'opération) sont des cases vides en attente de sa réponse :
-- rien n'est inventé pour les remplir. Un code qui n'a pas de source écrite ne
-- vaut rien devant un contrôleur.
--
-- Les trois montants du bloc main-d'œuvre (temps alloué, coût à l'heure, net)
-- sont saisis par un humain et ne se calculent nulle part : le barème de
-- remboursement Higer n'a jamais été fourni, et un total fabriqué par le portail
-- serait un chiffre que personne n'a engagé.
-- =============================================================================

alter table sav_cases add column if not exists ag_writer_name               character varying(160);
alter table sav_cases add column if not exists ag_repair_order_number      character varying(60);
alter table sav_cases add column if not exists ag_repair_order_date        date;
alter table sav_cases add column if not exists ag_incident_date            date;
alter table sav_cases add column if not exists ag_importer                 character varying(160);
alter table sav_cases add column if not exists ag_dealer_number            character varying(60);
alter table sav_cases add column if not exists ag_unit_number              character varying(60);
alter table sav_cases add column if not exists ag_cam_number               character varying(60);
alter table sav_cases add column if not exists ag_km                       integer;
alter table sav_cases add column if not exists ag_security_impact          boolean;
alter table sav_cases add column if not exists ag_incident_type            character varying(20);
alter table sav_cases add column if not exists ag_concerned_part           character varying(200);
alter table sav_cases add column if not exists ag_part_new_or_reconditioned character varying(10);
alter table sav_cases add column if not exists ag_organ_km                 integer;
alter table sav_cases add column if not exists ag_previous_claim_number    character varying(60);
alter table sav_cases add column if not exists ag_customer_context         text;
alter table sav_cases add column if not exists ag_cause                    text;
alter table sav_cases add column if not exists ag_comments                 text;
alter table sav_cases add column if not exists ag_labor_operation_code     character varying(60);
alter table sav_cases add column if not exists ag_labor_description        text;
alter table sav_cases add column if not exists ag_allocated_time           double precision;
alter table sav_cases add column if not exists ag_hourly_cost              double precision;
alter table sav_cases add column if not exists ag_labor_net                double precision;
alter table sav_cases add column if not exists ag_causal_part_reference    character varying(120);
alter table sav_cases add column if not exists ag_causal_part_description  character varying(200);
alter table sav_cases add column if not exists ag_function_group           character varying(120);
alter table sav_cases add column if not exists ag_serial_number            character varying(80);
alter table sav_cases add column if not exists ag_defect_type              character varying(120);
alter table sav_cases add column if not exists ag_defect_code              character varying(40);
alter table sav_cases add column if not exists ag_other_costs              double precision;
