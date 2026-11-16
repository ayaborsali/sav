-- =============================================================================
-- V49__mission_groupe.sql — le registre des déplacements de l'atelier
--
-- Un dossier de mission groupe porte le voyage : un client, une date de départ,
-- un kilométrage de départ, puis le retour (date et kilométrage). Il porte aussi
-- deux preuves photo : celle prise avant de quitter l'atelier, celle prise en y
-- revenant. L'état « rentré » n'est pas une colonne : il se lit sur returned_at.
--
-- À l'intérieur, une ou plusieurs lignes de bus. Chaque ligne est la mission
-- elle-même : son immatriculation, le problème constaté et le diagnostic. Un
-- dossier peut donc couvrir un seul bus ou plusieurs, mais chaque mission reste
-- attachée à son bus — c'est la règle du papier.
--
-- Le numéro est proposé par le serveur (1740, 1741…) et reste modifiable à la
-- saisie pour recopier un numéro déjà porté par le registre papier.
-- =============================================================================

CREATE TABLE IF NOT EXISTS mission_group_dossiers (
    id                 character varying(50)  NOT NULL,
    number             character varying(40)  NOT NULL,
    client_name        character varying(255) NOT NULL,
    departed_at        timestamp without time zone NOT NULL,
    departure_mileage  bigint                 NOT NULL,
    returned_at        timestamp without time zone,
    return_mileage     bigint,
    created_by         character varying(50),
    created_at         timestamp without time zone NOT NULL,
    updated_at         timestamp without time zone,
    CONSTRAINT mission_group_dossiers_pkey PRIMARY KEY (id),
    CONSTRAINT mission_group_dossiers_number_key UNIQUE (number)
);

CREATE INDEX IF NOT EXISTS idx_mission_group_dossiers_departed_at
    ON mission_group_dossiers (departed_at);

CREATE TABLE IF NOT EXISTS mission_group_buses (
    id                character varying(50)  NOT NULL,
    dossier_id        character varying(50)  NOT NULL,
    line_order        integer                NOT NULL,
    registration      character varying(120) NOT NULL,
    registration_key  character varying(120) NOT NULL,
    description       text,
    diagnosis         text,
    created_at        timestamp without time zone NOT NULL,
    updated_at        timestamp without time zone,
    CONSTRAINT mission_group_buses_pkey PRIMARY KEY (id),
    CONSTRAINT mission_group_buses_dossier_fkey
        FOREIGN KEY (dossier_id) REFERENCES mission_group_dossiers (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mission_group_buses_dossier
    ON mission_group_buses (dossier_id);
-- L'historique se lit par immatriculation : la clé est normalisée (majuscules,
-- espaces réduits) pour que « 230 TU 3342 » et « 230TU3342 » soient le même bus.
CREATE INDEX IF NOT EXISTS idx_mission_group_buses_registration
    ON mission_group_buses (registration_key);

CREATE TABLE IF NOT EXISTS mission_group_photos (
    id               character varying(50)  NOT NULL,
    dossier_id       character varying(50)  NOT NULL,
    angle            character varying(20)  NOT NULL,
    file_path        character varying(400) NOT NULL,
    original_name    character varying(255),
    content_type     character varying(60),
    size_bytes       bigint,
    sha256           character varying(64)  NOT NULL,
    taken_at         timestamp without time zone,
    taken_at_source  character varying(20),
    uploaded_at      timestamp without time zone NOT NULL,
    actor_email      character varying(255),
    note             character varying(500),
    CONSTRAINT mission_group_photos_pkey PRIMARY KEY (id),
    CONSTRAINT mission_group_photos_dossier_fkey
        FOREIGN KEY (dossier_id) REFERENCES mission_group_dossiers (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mission_group_photos_dossier
    ON mission_group_photos (dossier_id);
