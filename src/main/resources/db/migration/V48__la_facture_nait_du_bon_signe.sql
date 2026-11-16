-- =============================================================================
-- V48__la_facture_nait_du_bon_signe.sql -- l'argent qui entre (I01, I02).
--
-- Jusqu'ici le portail chiffre le travail sans jamais savoir si l'argent est venu.
-- Le devis accepté porte le montant, le bon de livraison rend le bus et se signe
-- (V39), mais rien ne transforme cette restitution en créance suivie : pas de
-- facture, pas d'échéance, pas d'encaissement. Le bus est livré, le travail est
-- fait, et la facture n'existe pas.
--
-- Trois tables neuves, et une seule règle de naissance :
--
--   factures        -- la créance. Elle naît d'un bon de livraison SIGNÉ, et reprend
--     le montant du devis accepté en amont (bl_id -> ordre -> devis). Un BL ne donne
--     qu'une facture : deux factures pour un même bus rendu, c'est un client qui paie
--     deux fois. `bl_id` est donc UNIQUE. L'état (émise / partielle / payée / impayée)
--     n'est PAS une colonne : il se calcule côté serveur à partir des encaissements,
--     du total TTC et de l'échéance — un état tapé au clavier serait un « payé » que
--     personne n'a encaissé.
--   facture_lines   -- les lignes de la facture, RECOPIÉES du devis accepté. Comme la
--     chaîne des papiers (V35, V46) : une trace, pas un lien vivant. Un devis ou un
--     catalogue qui change de prix six mois plus tard ne réécrit pas la facture que
--     le client a reçue. La nature (main-d'œuvre / pièce / déplacement) et le
--     fournisseur descendent avec la ligne, pour que la facture dise ce qu'elle chiffre.
--   encaissements   -- chaque argent rentré : montant, jour, mode, qui l'a écrit. Une
--     facture se paie en un ou plusieurs versements ; c'est la somme qui décide l'état.
--     La date est le jour du clic, posée par le serveur, jamais une case du formulaire
--     (la leçon de V25, V36, V39 : une date tapée à la main est une date qu'on recule).
--
-- Trois choix de la maison, reconduits sans exception :
--
-- 1. AUCUNE CLÉ ÉTRANGÈRE. `bl_id`, `devis_id`, `client_id`, `vehicle_id`,
--    `facture_id` sont des colonnes simples, comme `source_item_id` et `mission_id`.
--    Le portail ne casse pas une facture parce qu'un papier d'amont a été effacé ;
--    c'est le service qui refuse de facturer sans devis, pas la base qui contraint.
--
-- 2. TOUT EST NULLABLE EN BASE, OBLIGATOIRE AU SERVEUR AU BON MOMENT. Une facture
--    exige un BL signé et un devis accepté en amont ; un encaissement exige un montant
--    positif et borné au restant dû. La colonne, elle, n'exige rien — c'est le service
--    qui écrit le refus en français. Seules l'unicité de `number` et de `bl_id` et le
--    montant positif sont des CHECK, parce qu'aucun geste métier ne les légitime.
--
-- 3. LES MOTS DES ENUMS SONT DANS DES CHECK. `nature` et `supplied_by` reprennent les
--    mots de V36 (LABOR / PART / TRAVEL, SAPHIR / CLIENT) : ajouter un mot dans l'enum
--    Java sans réécrire le CHECK, c'est remettre la leçon de V30 — un 500 sur l'écran.
--
-- Aucun rattrapage des papiers existants : les BL déjà signés avant ce jour restent
-- signés sans facture, et c'est précisément la liste « à facturer » (I02) que le
-- portail rendra. Le portail ne devine pas une facture que personne n'a émise.
-- =============================================================================

CREATE TABLE IF NOT EXISTS factures (
    id          character varying(50)  NOT NULL,
    number      character varying(50)  NOT NULL,
    bl_id       character varying(50)  NOT NULL,
    devis_id    character varying(50),
    client_id   character varying(50),
    vehicle_id  character varying(50),
    issued_on   date,
    due_on      date,
    total_ht    double precision,
    total_tva   double precision,
    total_ttc   double precision,
    created_by  character varying(255),
    notes       text,
    CONSTRAINT factures_pkey PRIMARY KEY (id),
    CONSTRAINT factures_number_unique UNIQUE (number),
    CONSTRAINT factures_bl_id_unique UNIQUE (bl_id)
);

-- Les lignes de la facture, recopiées du devis accepté. `line_order` garde l'ordre
-- du papier, comme `maintenance_details` : une facture se relit dans l'ordre où elle
-- a été établie, pas dans l'ordre où la base a rangé ses lignes.
CREATE TABLE IF NOT EXISTS facture_lines (
    facture_id   character varying(50) NOT NULL,
    line_order   integer               NOT NULL,
    reference    character varying(120),
    label        character varying(500),
    quantity     double precision,
    unit_price   double precision,
    line_total   double precision,
    nature       character varying(20),
    supplied_by  character varying(20),
    CONSTRAINT facture_lines_pkey PRIMARY KEY (facture_id, line_order)
);

ALTER TABLE facture_lines DROP CONSTRAINT IF EXISTS facture_lines_nature_check;
ALTER TABLE facture_lines ADD CONSTRAINT facture_lines_nature_check
    CHECK ((nature IS NULL) OR ((nature)::text = ANY (ARRAY[
        'LABOR'::character varying,
        'PART'::character varying,
        'TRAVEL'::character varying]::text[])));

ALTER TABLE facture_lines DROP CONSTRAINT IF EXISTS facture_lines_supplied_by_check;
ALTER TABLE facture_lines ADD CONSTRAINT facture_lines_supplied_by_check
    CHECK ((supplied_by IS NULL) OR ((supplied_by)::text = ANY (ARRAY[
        'SAPHIR'::character varying,
        'CLIENT'::character varying]::text[])));

-- Chaque argent rentré. Pas de clé étrangère vers factures : le service vérifie que
-- la facture existe et que le montant ne dépasse pas ce qui reste dû. Le CHECK ne
-- porte que ce qu'aucun geste ne légitime : un encaissement nul ou négatif.
CREATE TABLE IF NOT EXISTS encaissements (
    id           character varying(50)  NOT NULL,
    facture_id   character varying(50)  NOT NULL,
    amount       double precision       NOT NULL,
    paid_on      date,
    mode         character varying(120),
    recorded_by  character varying(255),
    note         text,
    CONSTRAINT encaissements_pkey PRIMARY KEY (id),
    CONSTRAINT encaissements_amount_check CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS encaissements_facture_id_idx ON encaissements (facture_id);

COMMENT ON TABLE factures IS
    'La créance née d un bon de livraison signé (I01). Reprend le montant du devis accepté en amont. Une seule facture par BL (bl_id unique). L état émise/partielle/payée/impayée se calcule côté serveur depuis les encaissements, le total TTC et l échéance ; il n est pas stocké.';
COMMENT ON TABLE facture_lines IS
    'Lignes de la facture, recopiées du devis accepté : une trace, pas un lien vivant. Un prix qui change en amont ne réécrit pas la facture reçue par le client.';
COMMENT ON TABLE encaissements IS
    'Chaque argent rentré sur une facture : montant, jour du clic, mode, qui l a écrit. La somme décide l état. Guichet seul (ADMIN, GESTIONNAIRE_SAV) par la règle générique de SecurityConfig.';