-- =============================================================================
-- V6__depannage.sql — module Dépannage (niveau 1 : le registre des sorties)
--
-- Trois tables et un journal :
--   technicians       : les fiches techniciens (ressources), reliées à un compte
--                       users quand le technicien se connecte (user_id).
--   service_vehicles  : la flotte qui part en intervention (dépanneuse, camion
--                       atelier). Volontairement séparée de « vehicles » : cette
--                       dernière exige un client et un VIN, ce qu'un véhicule
--                       d'atelier n'a pas.
--   interventions     : le dossier de sortie, avec une colonne d'horodatage par
--                       étape. Ces heures sont écrites par le SERVEUR quand un
--                       bouton est pressé, jamais saisies au clavier.
--   intervention_events : le journal immuable des étapes (qui, quoi, quand,
--                       depuis quelle source). C'est ce qui permet de corriger
--                       une heure sans jamais effacer la trace de la correction.
--
-- Aucune colonne de montant n'est lisible par un rôle autre qu'ADMIN/CHEF_ATELIER :
-- le masquage se fait à la sortie du serveur (InterventionDtos), pas à l'écran.
--
-- Le kilométrage du véhicule de service est stocké sur l'intervention (départ et
-- retour) et non comme historique de la flotte : l'historique viendra avec la
-- télémétrie (gps_device_id est prévu vide ici).
--
-- Rôles : « role » est un varchar sans contrainte CHECK (V1), donc l'ajout de
-- CHEF_ATELIER et TECHNICIEN ne demande aucune modification de schéma.
-- Colonnes en snake_case, varchar dimensionnés comme les annotations @Column.
-- =============================================================================

CREATE TABLE IF NOT EXISTS technicians (
    id                  character varying(50)  NOT NULL,
    name                character varying(120) NOT NULL,
    phone               character varying(30),
    user_id             uuid,
    -- Ce que Saphir PAYE au technicien. À ne jamais confondre avec le coût horaire
    -- atelier (ce qui est facturé au client ou réclamé à Higer) : un seul champ
    -- finirait par servir aux deux.
    hourly_rate         numeric(8,3),
    dispatch_allowance  numeric(10,2),
    active              boolean                NOT NULL DEFAULT true,
    notes               character varying(500),
    CONSTRAINT technicians_pkey PRIMARY KEY (id),
    CONSTRAINT technicians_user_id_key UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_technicians_active ON technicians (active);

CREATE TABLE IF NOT EXISTS service_vehicles (
    id               character varying(50)  NOT NULL,
    registration     character varying(40)  NOT NULL,
    label            character varying(120) NOT NULL,
    kind             character varying(30)  NOT NULL,
    -- AVAILABLE / DISPATCHED / AT_WORKSHOP / OUT_OF_ORDER
    status           character varying(30)  NOT NULL DEFAULT 'AVAILABLE',
    current_mileage  bigint,
    -- Vides aujourd'hui : la télémétrie se branchera dessus sans changer le schéma.
    gps_device_id    character varying(60),
    gps_provider     character varying(60),
    active           boolean                NOT NULL DEFAULT true,
    notes            character varying(500),
    CONSTRAINT service_vehicles_pkey PRIMARY KEY (id),
    CONSTRAINT service_vehicles_registration_key UNIQUE (registration)
);

CREATE INDEX IF NOT EXISTS idx_service_vehicles_status ON service_vehicles (status);

CREATE TABLE IF NOT EXISTS interventions (
    id                  character varying(50)   NOT NULL,
    number              character varying(40)   NOT NULL,
    -- REPORTED / DISPATCHED / ON_SITE / IN_PROGRESS / REPAIRED / TOWED / RETURNED / CLOSED
    status              character varying(30)   NOT NULL,
    vehicle_id          character varying(50)   NOT NULL,
    client_id           character varying(255)  NOT NULL,
    technician_id       character varying(50)   NOT NULL,
    service_vehicle_id  character varying(50),
    created_by          character varying(50),

    -- Étape 1 : l'appel. reported_at est la seule heure que l'humain déclare
    -- (elle précède la sortie) ; tout le reste est horodaté par le serveur.
    reported_at         timestamp without time zone NOT NULL,
    requested_by        character varying(120),
    location_address    character varying(500),
    location_city       character varying(120),
    description         text,
    -- LOW / MEDIUM / HIGH / URGENT — mêmes valeurs que SavPriority, même vocabulaire.
    priority            character varying(20),

    -- Les heures d'étape, toutes écrites par le serveur.
    dispatched_at       timestamp without time zone,
    departed_at         timestamp without time zone,
    arrived_at          timestamp without time zone,
    finished_at         timestamp without time zone,
    left_site_at        timestamp without time zone,
    returned_at         timestamp without time zone,
    closed_at           timestamp without time zone,

    -- Kilomètres : déclarés au départ et au retour par le technicien (photo du
    -- compteur à l'appui), le calcul de la distance parcourue est serveur.
    start_mileage       bigint,
    end_mileage         bigint,
    bus_mileage         bigint,

    -- Ce qui a été fait.
    defect_code         character varying(40),
    diagnosis           text,
    causing_part        character varying(120),
    -- Le résultat ne se saisit pas : c'est le statut REPAIRED ou TOWED qui le porte.
    result_note         character varying(1000),
    received_by         character varying(120),
    closure_note        character varying(1000),

    -- Rattachement aux autres dossiers : le lien SAV/garantie reste optionnel.
    sav_case_id         character varying(50),
    created_at          timestamp without time zone NOT NULL,
    updated_at          timestamp without time zone,
    CONSTRAINT interventions_pkey PRIMARY KEY (id),
    CONSTRAINT interventions_number_key UNIQUE (number)
);

CREATE INDEX IF NOT EXISTS idx_interventions_status ON interventions (status);
CREATE INDEX IF NOT EXISTS idx_interventions_technician ON interventions (technician_id);
CREATE INDEX IF NOT EXISTS idx_interventions_vehicle ON interventions (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_interventions_client ON interventions (client_id);
CREATE INDEX IF NOT EXISTS idx_interventions_reported_at ON interventions (reported_at);

CREATE TABLE IF NOT EXISTS intervention_events (
    id               uuid                       NOT NULL,
    intervention_id  character varying(50)      NOT NULL,
    -- REPORTED / DISPATCHED / DEPARTED / ARRIVED / DIAGNOSED / REPAIRED
    -- / TOWED / LEFT_SITE / RETURNED / CLOSED / CORRECTED / NOTE
    event_type       character varying(40)      NOT NULL,
    -- SERVER = horodaté par le serveur ; MANAGED = posé par un compte interne ;
    -- TELEMETRY = réservé au capteur GPS du véhicule de service (vide aujourd'hui).
    source           character varying(20)      NOT NULL DEFAULT 'SERVER',
    actor_user_id    character varying(50),
    actor_email      character varying(255),
    recorded_at      timestamp without time zone NOT NULL,
    note             character varying(500),
    CONSTRAINT intervention_events_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_intervention_events_intervention ON intervention_events (intervention_id);
CREATE INDEX IF NOT EXISTS idx_intervention_events_recorded_at ON intervention_events (recorded_at);
