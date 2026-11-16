-- =============================================================================
-- chatbot_setup.sql — préparer PostgreSQL pour le chatbot du SAV (ÉTAPE 2)
--
-- CE QUE CE SCRIPT FAIT : il pose, à côté de la base du portail et sans rien
-- toucher d'existant, (1) l'extension pgvector, (2) deux vues de lecture qui
-- enlèvent les colonnes que le chatbot n'a pas à voir, (3) un rôle de
-- consultation « chatbot_ro » privé de toute écriture, (4) une table de passages
-- « chatbot_passages » avec son index de recherche vectorielle, (5) le
-- remplissage de cette table avec le texte déjà présent dans la base.
--
-- CE QU'IL NE FAIT PAS : aucun INSERT/UPDATE/DELETE sur une table du portail,
-- aucun ALTER TABLE, aucun renommage, aucune suppression. Rien de ce qui existe
-- n'est modifié. Tout ce qui est créé se retire avec chatbot_rollback.sql.
--
-- IDEMPOTENT : chaque ordre est rejouable (« IF NOT EXISTS », « CREATE OR
-- REPLACE VIEW », bloc DO pour le rôle, « ON CONFLICT DO NOTHING »). Vous pouvez
-- relancer ce script trois fois de suite : la troisième ne change rien.
--
-- HORS FLYWAY : ce fichier ne va PAS dans src/main/resources/db/migration, il
-- reste dans db/. Le schéma du portail gardé par Flyway n'est pas perturbé, et
-- Hibernate (ddl-auto=validate) ignore les tables qui ne correspondent à aucune
-- entité : vos vues et chatbot_passages ne casseront donc pas le démarrage de
-- l'application.
--
-- LANCER (une seule ligne, depuis le dossier du backend ; psql n'est pas dans le
-- chemin de votre système, il est dans le dossier de PostgreSQL 18) :
--   "C:/Program Files/PostgreSQL/18/bin/psql.exe" -h localhost -p 5432 -U sav_user -d sav_db -v chatbot_password=LE_MOT_DE_PASSE -f db/chatbot_setup.sql
-- Le mot de passe est passé par la ligne de commande, jamais écrit ici.
-- À FAIRE D'ABORD SUR UNE BASE DE DÉVELOPPEMENT, PAS SUR LA BASE DE L'ATELIER.
-- =============================================================================

-- ON_ERROR_STOP on : à la première erreur, le script s'arrête au lieu de
-- continuer à moitié. C'est ce qu'on veut ici : si l'extension pgvector manque,
-- il vaut mieux ne rien avoir créé du tout plutôt qu'un rôle sans table et des
-- droits posés sur une vue qui n'existe pas.
\set ON_ERROR_STOP on

-- \encoding UTF8 : sous Windows, psql part de la page de codes du console
-- (latin-1), alors que votre base est en UTF-8. Sans cette ligne, les accents de
-- ce fichier déclenchent « invalid byte sequence for encoding "UTF8" » avant
-- même la première instruction. À garder.
\encoding UTF8


-- ---------------------------------------------------------------------------
-- Garde-fou : sans la variable chatbot_password, on s'arrête avant de rien créer
-- (un rôle sans mot de passe est un rôle qui ne peut pas se connecter : mieux
-- vaut échouer tout de suite qu'une installation à moitié faite).
-- \if :{?variable} est la syntaxe de psql pour « cette variable a-t-elle été
-- passée ? ». Elle demande psql 10 ou plus récent (le vôtre : PostgreSQL 18).
-- ---------------------------------------------------------------------------
\if :{?chatbot_password}
\else
  \echo MOT_DE_PASSE_MANQUANT --- relancez en ajoutant  -v chatbot_password=votre_mot_de_passe  devant  -f db/chatbot_setup.sql
  \quit
\endif


-- ---------------------------------------------------------------------------
-- 1) L'extension pgvector, qui ajoute le type de colonne « vector » capable de
--    stocker un embedding (la signature numérique d'un texte, 1024 nombres ici)
--    et de calculer les distances entre deux signatures.
--
--    BESOIN : que le serveur ait les fichiers pgvector installés, et un compte
--    autorisé à créer l'extension. Si ça échoue, le message est
--    « could not open extension control file ... » : c'est pgvector qui n'est
--    pas installé sur le serveur, pas une faute dans ce script.
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;


-- ---------------------------------------------------------------------------
-- 2) v_vehicles : la fiche du bus, sans la photographie de la plaque.
--
--    vehicles.plate_image est une colonne « text » qui contient l'image entière
--    encodée en base64 : plusieurs centaines de milliers de caractères. Le
--    chatbot n'a rien à en faire, et il ne doit pas pouvoir la lire.
--
--    Une vue n'est pas une copie : elle lit la vraie table à chaque fois. Les
--    données restent donc exactes, et rien n'est à ressaisir quand un bus
--    change de compteur.
--
--    Les colonnes gardées sont exactement votre liste. Il existe aussi, sur
--    vehicles, mileage_origin / mileage_recorded_at / mileage_recorded_by /
--    mileage_correction_reason (ajoutées par V17) : elles ne sont pas dans votre
--    liste, donc elles ne sont pas dans la vue.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_vehicles AS
SELECT id,
       client_id,
       registration,
       vin,
       model,
       motorization,
       year,
       commissioning_date,
       warranty_end_date,
       mileage,
       status,
       contract_id,
       unit_price_ht,
       notes
  FROM vehicles;


-- ---------------------------------------------------------------------------
-- 3) v_technicians : qui est technicien et s'il est actif. Rien de plus.
--
--    La table technicians porte hourly_rate et dispatch_allowance (ce que Saphir
--    paie), phone et user_id. Ces colonnes ne sortent pas : le chatbot répond
--    sur le travail, pas sur la paie ni sur les liens de connexion.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_technicians AS
SELECT id,
       name,
       active
  FROM technicians;


-- ---------------------------------------------------------------------------
-- 4) chatbot_passages : la table que le chatbot cherchera par similarité.
--
--    Une ligne = un morceau de texte déjà écrit par l'atelier (un dossier, une
--    sortie, un devis, une remarque), rattaché à sa table d'origine et à sa clé,
--    pour que la réponse puisse dire « source : sav_cases · HGR-SAV-... ».
--
--    - id bigserial : numéro automatique, à jour.
--    - embedding vector(1024) : la taille 1024 est celle de bge-m3, le modèle
--      d'embeddings prévu à l'étape 5. Elle reste NULL jusqu'à ce jour-là.
--    - UNIQUE (source_table, source_id) : un objet de la base ne peut pas
--      produire deux passages ; c'est aussi la clé que regarde l'INSERT du
--      point 7 pour ne rien dupliquer s'il repasse.
--    - l'index HNSW est l'accélérateur de la recherche « textes les plus
--      proches ». Sans lui, chaque question relirait toute la table ; il faut
--      pgvector 0.5 ou plus récent. Les lignes dont embedding est NULL ne sont
--      pas indexées, donc cet ordre fonctionne dès maintenant.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chatbot_passages (
    id           bigserial    NOT NULL,
    source_table text         NOT NULL,
    source_id    text         NOT NULL,
    texte        text         NOT NULL,
    embedding    vector(1024),
    CONSTRAINT chatbot_passages_pkey PRIMARY KEY (id),
    CONSTRAINT chatbot_passages_source_key UNIQUE (source_table, source_id)
);

CREATE INDEX IF NOT EXISTS chatbot_passages_embedding_hnsw
    ON chatbot_passages USING hnsw (embedding vector_cosine_ops);


-- ---------------------------------------------------------------------------
-- 5) Le rôle chatbot_ro : le compte avec lequel le backend du chatbot se
--    connectera. Il n'a aucune table en propriété.
--
--    Le bloc DO sert à ne pas échouer si le rôle existe déjà (c'est la
--    troisième relance du script, par exemple). Contrairement aux tables, une
--    rôle ne connaît pas « IF NOT EXISTS ».
--
--    Le mot de passe arrive par la variable psql :'chatbot_password' ; psql
--    échappe lui-même le contenu, ce qui marche même s'il contient une
--    apostrophe. Il n'est écrit dans aucun fichier.
-- ---------------------------------------------------------------------------
DO $bloc$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatbot_ro') THEN
        CREATE ROLE chatbot_ro;
    END IF;
END
$bloc$;

-- LOGIN : le rôle peut ouvrir une session (un simple « role » ne le peut pas).
ALTER ROLE chatbot_ro LOGIN PASSWORD :'chatbot_password';

-- Une question qui partirait dans une erreur de jointure ne doit pas bloquer la
-- base pendant des minutes : 5 secondes, puis PostgreSQL coupe.
ALTER ROLE chatbot_ro SET statement_timeout = '5s';


-- ---------------------------------------------------------------------------
-- 6) Les droits : SELECT sur une liste fermée, rien d'autre.
--
--    USAGE sur le schéma public = l'autorisation d'entrer dans le schéma ; sans
--    elle, même une table accordée reste injoignable.
--
--    La liste accordée est la vôtre, avec les deux corrections de nom trouvées
--    dans les migrations : « maintenances » s'appelle maintenance_items et
--    « maintenance_lines » s'appelle maintenance_details. Les véhicules et les
--    techniciens ne sont donnés que par leurs vues, jamais par les tables brutes.
--
--    Le REVOKE qui suit est une ceinture : un rôle tout juste créé n'a rien,
--    mais si chatbot_ro a existé par le passé avec des droits de travers, cette
--    ligne les ferme. Il ne retire rien au compte du portail (sav_user) ni aux
--    autres rôles.
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO chatbot_ro;

GRANT SELECT ON
    clients,
    contracts,
    sav_cases,
    interventions,
    intervention_events,
    intervention_photos,
    intervention_alerts,
    maintenance_items,
    maintenance_details,
    v_vehicles,
    v_technicians,
    chatbot_passages
TO chatbot_ro;

REVOKE ALL ON
    users,
    user_sessions,
    documents,
    intervention_primes,
    audit_logs,
    vehicles,
    technicians,
    service_vehicles
FROM chatbot_ro;


-- ---------------------------------------------------------------------------
-- 7) Remplissage de chatbot_passages avec le texte déjà présent dans la base.
--
--    concat_ws(' | ', ...) colle les champs avec une barre comme séparateur et
--    SAUTE les champs vides : un dossier sans résolution ne fabrique pas
--    « title | description | ». Si tous les champs sont vides, le résultat est
--    une chaîne vide, écartée par le WHERE : on n'engorge pas la table avec des
--    lignes sans texte.
--
--    ON CONFLICT DO NOTHING : si le passage d'un objet est déjà là, on ne le
--    duplique pas et on ne l'écrase pas. Conséquence à connaître : si un dossier
--    est modifié dans le portail après cette étape, son passage garde l'ancien
--    texte jusqu'à ce qu'on le rafraîchisse (à l'étape 5, quand l'ingestion sera
--    écrite avec les embeddings).
--
--    La colonne embedding reste NULL volontairement : c'est le modèle bge-m3,
--    côté backend à l'étape 5, qui la calculera.
-- ---------------------------------------------------------------------------
INSERT INTO chatbot_passages (source_table, source_id, texte)
WITH sources AS (

    -- Les dossiers SAV : le récit de la visite, du problème et de la fin.
    SELECT 'sav_cases'::text AS source_table,
           c.id::text        AS source_id,
           trim(concat_ws(' | ', c.title, c.description, c.resolution)) AS texte
      FROM sav_cases c

    UNION ALL

    -- Les sorties de dépannage : ce qui était signalé, le diagnostic, la pièce,
    -- le résultat et la note de clôture.
    SELECT 'interventions'::text,
           i.id::text,
           trim(concat_ws(' | ', i.description, i.diagnosis, i.causing_part,
                          i.result_note, i.closure_note))
      FROM interventions i

    UNION ALL

    -- Devis, bons de livraison et bons de travaux (table maintenance_items).
    SELECT 'maintenance_items'::text,
           m.id::text,
           trim(coalesce(m.description, ''))
      FROM maintenance_items m

    UNION ALL

    -- Les bus : seulement la remarque de la fiche.
    SELECT 'vehicles'::text,
           v.id::text,
           trim(coalesce(v.notes, ''))
      FROM vehicles v

    UNION ALL

    -- Les clients : seulement la remarque de la fiche.
    SELECT 'clients'::text,
           cl.id::text,
           trim(coalesce(cl.notes, ''))
      FROM clients cl

)
SELECT source_table, source_id, texte
  FROM sources
 WHERE texte <> ''
ON CONFLICT (source_table, source_id) DO NOTHING;


-- ---------------------------------------------------------------------------
-- Trace : ce que la table vient de recevoir, par origine. Le total doit être
-- supérieur à zéro si la base de départ contient des dossiers ou des sorties.
-- ---------------------------------------------------------------------------
SELECT source_table AS origine,
       count(*)     AS passages
  FROM chatbot_passages
 GROUP BY source_table
 ORDER BY source_table;
