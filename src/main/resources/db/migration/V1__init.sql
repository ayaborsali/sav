-- =============================================================================
-- V1__init.sql — schéma initial du portail SAV Higer Bus Tunisie
--
-- Généré d'après les colonnes réellement présentes dans la base de développement
-- (information_schema + pg_indexes relevés le 2026-10-15) et les annotations JPA
-- des entités : tables et colonnes en snake_case, énumérations stockées en
-- character varying (@Enumerated(EnumType.STRING)).
--
-- Tout est écrit « IF NOT EXISTS » : le script est inoffensif sur une base vide
-- (il crée le schéma) comme sur une base déjà peuplée.
-- Clé étrangère : aucune, les références entre entités sont des colonnes simples
-- (vehicle_id, client_id, ...) côté Hibernate — on reste strictement identique.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Comptes du portail (ADMIN, GESTIONNAIRE_SAV, CONSULTATION)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                       uuid                      NOT NULL,
    name                     character varying(255)    NOT NULL,
    email                    character varying(255)    NOT NULL,
    password_hash            character varying(255)    NOT NULL,
    role                     character varying(255)    NOT NULL,
    active                   boolean     DEFAULT true  NOT NULL,
    last_login               timestamp without time zone,
    reset_token_expires_at   timestamp without time zone,
    reset_token_hash         character varying(64),
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_email_key UNIQUE (email)
);

-- ---------------------------------------------------------------------------
-- Sessions émises à la connexion (un « badge » = une ligne)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_sessions (
    id            uuid                       NOT NULL,
    user_id       uuid                       NOT NULL,
    remember_me   boolean                    NOT NULL,
    created_at    timestamp without time zone NOT NULL,
    expires_at    timestamp without time zone NOT NULL,
    last_seen_at  timestamp without time zone,
    revoked_at    timestamp without time zone,
    user_agent    character varying(300),
    CONSTRAINT user_sessions_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at ON user_sessions (expires_at);

-- ---------------------------------------------------------------------------
-- Clients (garages, transporteurs, écoles, offices touristiques)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clients (
    id              character varying(50)  NOT NULL,
    name            character varying(255) NOT NULL,
    code            character varying(255) NOT NULL,
    type            character varying(30)  NOT NULL,
    contact_person  character varying(255),
    email           character varying(255),
    phone           character varying(255),
    address         character varying(255),
    city            character varying(255),
    created_at      date,
    notes           character varying(1000),
    CONSTRAINT clients_pkey PRIMARY KEY (id),
    CONSTRAINT clients_code_key UNIQUE (code)
);

CREATE INDEX IF NOT EXISTS idx_clients_type ON clients (type);

-- ---------------------------------------------------------------------------
-- Bus Higer suivis par le SAV
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vehicles (
    id                 character varying(50)  NOT NULL,
    vin                character varying(255) NOT NULL,
    registration       character varying(255) NOT NULL,
    model              character varying(255),
    year               integer,
    client_id          character varying(255) NOT NULL,
    mileage            bigint,
    commissioning_date date,
    warranty_end_date  date,
    motorization       character varying(255),
    status             character varying(30),
    notes              character varying(1000),
    plate_image        text,
    CONSTRAINT vehicles_pkey PRIMARY KEY (id),
    CONSTRAINT vehicles_vin_key UNIQUE (vin)
);

CREATE INDEX IF NOT EXISTS idx_vehicles_client_id ON vehicles (client_id);
CREATE INDEX IF NOT EXISTS idx_vehicles_status ON vehicles (status);

-- ---------------------------------------------------------------------------
-- Dossiers SAV (visites, garanties, qualité, pièces, réclamations)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sav_cases (
    id                       character varying(50)  NOT NULL,
    case_number              character varying(255) NOT NULL,
    type                     character varying(30)  NOT NULL,
    title                    character varying(255) NOT NULL,
    vehicle_id               character varying(255) NOT NULL,
    client_id                character varying(255),
    status                   character varying(30),
    priority                 character varying(20),
    created_at               date,
    updated_at               date,
    description              text,
    resolution               text,
    technician               character varying(255),
    warranty_approval_number character varying(255),
    part_reference           character varying(255),
    quantity                 integer,
    cost_estimate            double precision,
    CONSTRAINT sav_cases_pkey PRIMARY KEY (id),
    CONSTRAINT sav_cases_case_number_key UNIQUE (case_number)
);

CREATE INDEX IF NOT EXISTS idx_sav_cases_vehicle_id ON sav_cases (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_sav_cases_client_id ON sav_cases (client_id);
CREATE INDEX IF NOT EXISTS idx_sav_cases_status ON sav_cases (status);

-- ---------------------------------------------------------------------------
-- Devis, bons de livraison et bons de travaux
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS maintenance_items (
    id          character varying(50)  NOT NULL,
    type        character varying(20)  NOT NULL,
    number      character varying(255) NOT NULL,
    vehicle_id  character varying(255) NOT NULL,
    client_id   character varying(255),
    date        date,
    status      character varying(20),
    totalht     double precision,
    totalttc    double precision,
    technician  character varying(255),
    description text,
    CONSTRAINT maintenance_items_pkey PRIMARY KEY (id),
    CONSTRAINT maintenance_items_number_key UNIQUE (number)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_items_vehicle_id ON maintenance_items (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_items_client_id ON maintenance_items (client_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_items_type ON maintenance_items (type);

-- Lignes d'un devis / BL : collection @ElementCollection portée par la table mère.
CREATE TABLE IF NOT EXISTS maintenance_details (
    maintenance_id  character varying(50) NOT NULL,
    reference       character varying(255),
    label           character varying(255),
    quantity        double precision,
    unit_price      double precision,
    line_order      integer               NOT NULL,
    CONSTRAINT maintenance_details_pkey PRIMARY KEY (maintenance_id, line_order)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_details_maintenance_id ON maintenance_details (maintenance_id);

-- ---------------------------------------------------------------------------
-- Documents rattachés (garantie, carte grise, manuel, rapport, facture, schéma)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS documents (
    id           character varying(50)  NOT NULL,
    title        character varying(255) NOT NULL,
    category     character varying(30),
    file_type    character varying(255),
    file_size    character varying(255),
    vehicle_id   character varying(255),
    client_id    character varying(255),
    uploaded_at  date,
    uploaded_by  character varying(255),
    file_data    text,
    CONSTRAINT documents_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_documents_vehicle_id ON documents (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_documents_client_id ON documents (client_id);
CREATE INDEX IF NOT EXISTS idx_documents_category ON documents (category);
