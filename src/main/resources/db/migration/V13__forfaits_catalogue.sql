-- =============================================================================
-- V13__forfaits_catalogue.sql — phase A du module « Forfaits de maintenance »
--
-- Quatre tables, toutes du côté CATALOGUE : ce que l'atelier vend, pas ce
-- qu'un client a acheté.
--   maintenance_plans            : un plan de maintenance par modèle de bus
--                                  (« BUS HIGER 26 WBE – URBAIN »).
--   maintenance_plan_steps       : les paliers du plan (visite des 45 000 km…).
--                                  Un palier se définit par un kilométrage et/ou
--                                  un nombre de jours : c'est ce qui le rend
--                                  atteignable, sinon il ne se déclenche jamais.
--   maintenance_plan_operations  : les lignes du plan (organe, article,
--                                  référence, quantité, action, fréquence).
--   maintenance_plan_step_operations : les croix de la grille, entre une
--                                  opération et un palier.
--
-- Deux choix assumés, pris du projet et non de la fiche de conception :
--
-- 1. AUCUNE clé étrangère. Les douze migrations précédentes n'en posent pas :
--    la cohérence est vérifiée par le service, qui écrit le message de refus en
--    français. Une clé ici ferait échouer une suppression propre plutôt que de
--    la refuser nommément.
--
-- 2. TOUS LES PRIX SONT NULLABLES. Les prix ne sont pas encore connus (le
--    gérant les prépare). Le catalogue doit être rempli AVANT eux : un plan sans
--    prix est un plan valide, et l'écran signale « prix à définir » ligne par
--    ligne. Une colonne NOT NULL forcerait à inventer un chiffre pour pouvoir
--    saisir le plan.
--
-- quantity_text est un texte (« 23 L », « 53 L », « 2 ») et non un nombre : le
-- document Higer porte l'unité dans la même case, et un nombre perdrait
-- l'information qu'on doit recopier sur le bon de travail.
-- =============================================================================

CREATE TABLE IF NOT EXISTS maintenance_plans (
    id                        character varying(50)  NOT NULL,
    name                      character varying(160) NOT NULL,
    -- Le modèle de bus auquel le plan s'applique (« HIGER 26 WBE »). Texte libre :
    -- la liste des modèles vient des contrats, pas d'une énumération figée ici.
    bus_model                 character varying(120),
    annual_km                 bigint,
    oil_change_interval_km    integer,
    oil_change_interval_days  integer,
    duration_years            integer,
    active                    boolean                NOT NULL DEFAULT true,
    created_at                timestamp without time zone NOT NULL,
    CONSTRAINT maintenance_plans_pkey PRIMARY KEY (id),
    CONSTRAINT maintenance_plans_name_key UNIQUE (name)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_plans_active ON maintenance_plans (active);
CREATE INDEX IF NOT EXISTS idx_maintenance_plans_model ON maintenance_plans (bus_model);

CREATE TABLE IF NOT EXISTS maintenance_plan_steps (
    id         character varying(50)  NOT NULL,
    plan_id    character varying(50)  NOT NULL,
    -- Rang dans le plan : c'est l'ordre de lecture de la grille, et l'ordre dans
    -- lequel un forfait vendu avance. Il est posé par le serveur.
    step_order integer                NOT NULL,
    km         bigint,
    days       integer,
    label      character varying(160) NOT NULL,
    price_ht   numeric(10,2),
    CONSTRAINT maintenance_plan_steps_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_plan_steps_plan ON maintenance_plan_steps (plan_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_plan_steps_order ON maintenance_plan_steps (plan_id, step_order);

CREATE TABLE IF NOT EXISTS maintenance_plan_operations (
    id             character varying(50)  NOT NULL,
    plan_id        character varying(50)  NOT NULL,
    -- REPLACE / INSPECT / LUBRICATE / CLEAN / ADJUST, rendu à l'écran par son
    -- libellé français (« remplacer », « contrôler »…).
    operation_type character varying(20)  NOT NULL,
    organ          character varying(80),
    article        character varying(300),
    reference      character varying(120),
    quantity_text  character varying(60),
    frequency_km   integer,
    unit_price_ht  numeric(10,2),
    CONSTRAINT maintenance_plan_operations_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_plan_operations_plan ON maintenance_plan_operations (plan_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_plan_operations_organ ON maintenance_plan_operations (organ);

-- Les croix de la grille. Une ligne = une opération qui s'applique à un palier.
-- La paire est la clé : deux croix sur la même case sont impossibles par
-- construction, et lever une croix est un DELETE, pas un drapeau à false.
CREATE TABLE IF NOT EXISTS maintenance_plan_step_operations (
    step_id      character varying(50) NOT NULL,
    operation_id character varying(50) NOT NULL,
    CONSTRAINT maintenance_plan_step_operations_pkey PRIMARY KEY (step_id, operation_id)
);

CREATE INDEX IF NOT EXISTS idx_mpsop_operation ON maintenance_plan_step_operations (operation_id);
