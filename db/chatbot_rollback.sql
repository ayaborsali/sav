-- =============================================================================
-- chatbot_rollback.sql — enlever tout ce que chatbot_setup.sql a posé
--
-- Une ligne, depuis le dossier du backend :
--   "C:/Program Files/PostgreSQL/18/bin/psql.exe" -h localhost -p 5432 -U sav_user -d sav_db -f db/chatbot_rollback.sql
--
-- Ordre important : on ferme les droits, puis on retire les objets, puis le rôle.
-- Un rôle qui possède encore quelque chose ne se supprime pas ; ici le rôle ne
-- possède rien (tout a été créé par sav_user), mais on garde cet ordre quand
-- même parce qu'il est sans risque.
--
-- CE QUE CE FICHIER NE TOUCHE PAS : aucune table, vue, colonne ou index du
-- portail. Tout est en « IF EXISTS », donc le fichier ne dit rien s'il est lancé
-- deux fois, et il ne peut pas casser une base où le setup n'a jamais été fait.
--
-- L'extension pgvector reste. Elle n'appartient pas au chatbot (elle est au
-- serveur), et la retirer pourrait casser quelqu'un d'autre qui l'utiliserait.
-- Si vous voulez vraiment l'enlever, la ligne est au bas de ce fichier, en
-- commentaire.
-- =============================================================================

\set ON_ERROR_STOP on
\encoding UTF8

-- 1) Tous les droits du rôle tombent d'un coup : inutile de lister table par table.
--    Le bloc DO vérifie que le rôle existe avant de retirer ses droits, sinon la
--    deuxième relance de ce fichier échouerait sur « role does not exist » alors
--    que tout est déjà nettoyé. (Un REVOKE ne connaît pas « IF EXISTS ».)
DO $bloc$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chatbot_ro') THEN
        EXECUTE 'REVOKE ALL ON SCHEMA public FROM chatbot_ro';
        EXECUTE 'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM chatbot_ro';
    END IF;
END
$bloc$;

-- 2) Les deux vues de lecture (v_vehicles, v_technicians). Les vraies tables
--    vehicles et technicians ne sont pas touchées : une vue est une fenêtre, pas
--    une copie.
DROP VIEW IF EXISTS v_vehicles;
DROP VIEW IF EXISTS v_technicians;

-- 3) La table des passages. Son index HNSW et sa séquence bigserial partent avec
--    elle automatiquement : rien de séparé à supprimer.
DROP TABLE IF EXISTS chatbot_passages;

-- 4) Le rôle. IF EXISTS évite l'erreur s'il n'a jamais été créé.
--    Attention : le rôle ne meurt pas s'il a des droits sur des objets qui ne
--    sont pas dans le schéma public ; le point 1 ci-dessus règle ce cas.
DROP ROLE IF EXISTS chatbot_ro;

-- 5) Contrôle : trois fois « 0 » veut dire que plus rien ne reste.
SELECT count(*) AS vues_restantes
  FROM pg_views WHERE schemaname = 'public' AND viewname IN ('v_vehicles', 'v_technicians');

SELECT count(*) AS table_restante
  FROM pg_tables WHERE schemaname = 'public' AND tablename = 'chatbot_passages';

SELECT count(*) AS role_restant
  FROM pg_roles WHERE rolname = 'chatbot_ro';

-- Pour enlever aussi l'extension (à ne faire que si rien d'autre sur ce serveur
-- ne l'utilise) :
-- DROP EXTENSION IF EXISTS vector;
