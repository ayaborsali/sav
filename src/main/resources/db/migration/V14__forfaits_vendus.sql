-- =============================================================================
-- V14__forfaits_vendus.sql — phase B du module « Forfaits de maintenance »
--
-- Le catalogue (V13) dit ce que l'atelier propose. Ces deux tables disent ce
-- qu'un client a ACHETÉ pour un bus précis :
--   sold_plans       : un plan vendu à un client pour un bus, avec des dates.
--   sold_plan_steps  : l'avancement, un palier du plan par ligne, à faire ou fait.
--
-- Pourquoi une copie d'avancement plutôt qu'un drapeau sur le palier du plan :
-- le même plan se vend à plusieurs clients. Le palier du plan reste la
-- description (« visite des 45 000 km »), la ligne de sold_plan_steps porte le
-- suivi de CE bus-là — date de passage, compteur relevé, document qui y est lié.
--
-- Trois choix pris du projet, les mêmes qu'en V13 :
--
-- 1. AUCUNE clé étrangère. La cohérence est vérifiée par SoldPlanService, qui
--    écrit le refus en français (« ce bus n'appartient pas à ce client »).
--
-- 2. LE PRIX EST NULLABLE. Les prix ne sont pas encore connus. Un forfait vendu
--    sans prix reste un forfait complet côté travaux : seul le chiffrage attend.
--
-- 3. ON N'EFFACE RIEN. Une annulation est un statut, pas un DELETE : les devis
--    et BL déjà liés au forfait, et les paliers déjà passés, doivent rester
--    lisibles. C'est la règle du catalogue (active=false), appliquée ici.
--
-- step_id n'a pas de colonne en double (libellé recopié) : le plan d'un forfait
-- vendu n'est jamais effacé, donc le palier reste retrouvé avec son libellé et
-- son kilométrage d'aujourd'hui. La conséquence — un palier du catalogue ne peut
-- pas changer d'identifiant sans casser les forfaits vendus — est verrouillée
-- dans MaintenancePlanService.
-- =============================================================================

CREATE TABLE IF NOT EXISTS sold_plans (
    id         character varying(50) NOT NULL,
    plan_id    character varying(50) NOT NULL,
    client_id  character varying(50) NOT NULL,
    -- Le bus, et un seul : un forfait se suit à l'odomètre d'un véhicule.
    vehicle_id character varying(50) NOT NULL,
    start_date date                  NOT NULL,
    -- Date de fin facultative : un forfait peut courir jusqu'à ce que l'atelier
    -- le clôture. Sans borne de fin, la règle de chevauchement la traite comme
    -- ouverte.
    end_date   date,
    price_ht   numeric(10,2),
    -- ACTIVE / COMPLETED / CANCELLED, en minuscules dans l'API JSON.
    status     character varying(20) NOT NULL,
    created_at timestamp without time zone NOT NULL,
    CONSTRAINT sold_plans_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_sold_plans_vehicle ON sold_plans (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_sold_plans_client ON sold_plans (client_id);
-- La règle « un seul forfait ACTIVE du même plan pour ce bus » se pose à chaque
-- vente : ces trois colonnes sont lues ensemble, pas séparément.
CREATE INDEX IF NOT EXISTS idx_sold_plans_vehicle_plan ON sold_plans (vehicle_id, plan_id, status);

CREATE TABLE IF NOT EXISTS sold_plan_steps (
    id                  character varying(50)  NOT NULL,
    sold_plan_id        character varying(50)  NOT NULL,
    step_id             character varying(50)  NOT NULL,
    -- TODO / DONE. Le passage à DONE se fait phase C, quand un document lié est
    -- livré ou terminé ; ici, tous les paliers naissent à TODO.
    status              character varying(20)  NOT NULL,
    done_at             timestamp without time zone,
    -- Le devis / BL / opération qui a fait le palier. Nullable : le palier peut
    -- être fait hors du portail, ou pas encore saisi.
    maintenance_item_id character varying(50),
    -- Compteur relevé au passage, en kilomètres : c'est la preuve que le palier
    -- correspond bien à ce que le document Higer demande à ce moment-là.
    mileage_at_service  bigint,
    CONSTRAINT sold_plan_steps_pkey PRIMARY KEY (id),
    -- Deux lignes de suivi pour le même palier du même forfait : impossible par
    -- construction. C'est la seule contrainte physique posée ici, parce qu'aucun
    -- service ne peut la rater — un doublon ferait compter deux fois un palier
    -- fait à l'écran.
    CONSTRAINT sold_plan_steps_plan_step_key UNIQUE (sold_plan_id, step_id)
);

CREATE INDEX IF NOT EXISTS idx_sold_plan_steps_plan ON sold_plan_steps (sold_plan_id);
CREATE INDEX IF NOT EXISTS idx_sold_plan_steps_item ON sold_plan_steps (maintenance_item_id);
