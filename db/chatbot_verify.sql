-- =============================================================================
-- chatbot_verify.sql — contrôler que le rôle chatbot_ro est bien ce qu'il doit
-- être : assez pour répondre, pas assez pour écrire ni pour voir la paie,
-- les mots de passe ou les images de plaques.
--
-- À LANCER CONNECTÉ EN chatbot_ro, pas en sav_user (une ligne, depuis le dossier
-- du backend ; psql n'est pas dans le chemin du système) :
--   "C:/Program Files/PostgreSQL/18/bin/psql.exe" -h localhost -p 5432 -U chatbot_ro -d sav_db -f db/chatbot_verify.sql
-- PostgreSQL demandera le mot de passe donné au moment du setup.
--
-- COMMENT LIRE LE RÉSULTAT :
--   section A, B  : tout doit RÉUSSIR ;
--   section C     : tout doit ÉCHOUER, et c'est ce qu'on veut. Une ligne de la
--                   section C qui réussit est un trou à me signaler.
--
-- ON_ERROR_STOP off : on laisse psql continuer après une erreur, sinon la
-- première erreur attendue arrêterait le fichier et les contrôles suivants ne
-- se feraient pas. C'est le comportement par défaut de psql, écrit ici pour que
-- ce soit explicite.
-- =============================================================================
\set ON_ERROR_STOP off

-- Voir la note de chatbot_setup.sql : psql sous Windows part de la page de codes
-- du console, votre base est en UTF-8. Cette ligne évite l'erreur de séquence.
\encoding UTF8

\echo ''
\echo '===== A. QUI PARLE, ET JUSQU OÙ ====='

-- attendu : chatbot_ro
SELECT current_user AS compte_connecte;

-- attendu : 5s  (la limite de durée d une requête posée par le setup)
SHOW statement_timeout;

-- attendu : false | false | false  (ni super-utilisateur, ni droit de créer une
-- base, ni droit de créer un rôle : le compte ne peut pas agrandir son propre
-- périmètre)
SELECT rolsuper AS super_utilisateur,
       rolcreatedb AS peut_creer_une_base,
       rolcreaterole AS peut_creer_un_role
  FROM pg_roles
 WHERE rolname = current_user;


\echo ''
\echo '===== B. CE QUI DOIT FONCTIONNER ====='

-- attendu : un nombre, sans erreur. C'est la fiche des bus, sans la photo de plaque.
SELECT count(*) AS bus_visibles FROM v_vehicles;

-- attendu : un nombre, sans erreur.
SELECT count(*) AS techniciens_visibles FROM v_technicians;

-- attendu : un nombre, sans erreur, pour les 10 autres objets accordés.
SELECT count(*) AS clients        FROM clients;
SELECT count(*) AS contrats       FROM contracts;
SELECT count(*) AS dossiers_sav   FROM sav_cases;
SELECT count(*) AS sorties        FROM interventions;
SELECT count(*) AS etapes_sorties FROM intervention_events;
SELECT count(*) AS photos         FROM intervention_photos;
SELECT count(*) AS alertes        FROM intervention_alerts;
SELECT count(*) AS devis_bl       FROM maintenance_items;
SELECT count(*) AS lignes_de_vis  FROM maintenance_details;

-- attendu : strictement supérieur à 0 si la base de départ contient des dossiers
-- ou des sorties remplis. Zéro veut dire que le remplissage n'a rien trouvé.
SELECT count(*) AS passages_disponibles FROM chatbot_passages;

-- attendu : une ligne par origine (clients, interventions, maintenance_items,
-- sav_cases, vehicles), avec le nombre de passages de chacun.
SELECT source_table AS origine, count(*) AS passages
  FROM chatbot_passages
 GROUP BY source_table
 ORDER BY source_table;

-- attendu : NORMAL À CE STADE — les deux nombres du bas doivent être égaux,
-- puisque les embeddings ne sont calculés qu'à l'étape 5.
SELECT count(*) AS total_passages,
       count(*) FILTER (WHERE embedding IS NULL) AS sans_embedding,
       count(*) FILTER (WHERE embedding IS NOT NULL) AS avec_embedding
  FROM chatbot_passages;


\echo ''
\echo '===== C. CE QUI DOIT REFUSER  (les erreurs sont le bon résultat) ====='

-- attendu : ERREUR — column "plate_image" does not exist.
-- La photo de la plaque n'est pas dans la vue : le chatbot ne peut pas la lire.
SELECT plate_image FROM v_vehicles LIMIT 1;

-- attendu : ERREUR — column "hourly_rate" does not exist.
-- Ce que Saphir paie au technicien reste dans la table brute, interdite.
SELECT hourly_rate, dispatch_allowance FROM v_technicians LIMIT 1;

-- attendu : ERREUR — permission denied for table users (comptes et hachages).
SELECT count(*) FROM users;

-- attendu : ERREUR — permission denied for table user_sessions (badges de
-- connexion en cours).
SELECT count(*) FROM user_sessions;

-- attendu : ERREUR — permission denied for table documents (fichiers du portail).
SELECT count(*) FROM documents;

-- attendu : ERREUR — permission denied for table intervention_primes (la paie).
SELECT count(*) FROM intervention_primes;

-- attendu : ERREUR — permission denied for table audit_logs (les corrections).
SELECT count(*) FROM audit_logs;

-- attendu : ERREUR — permission denied for table vehicles. Seule la vue est
-- ouverte, jamais la table brute avec sa colonne d'image.
SELECT count(*) FROM vehicles;

-- attendu : ERREUR — permission denied for table technicians.
SELECT count(*) FROM technicians;

-- attendu : ERREUR — permission denied for table service_vehicles (la flotte
-- n'est pas dans la liste accordée).
SELECT count(*) FROM service_vehicles;

-- attendu : ERREUR — permission denied for table clients. Le WHERE impossible
-- est une seconde ceinture : si ce fichier était lancé par erreur en sav_user,
-- il ne supprimerait rien.
DELETE FROM clients WHERE id = '__test_chatbot_inexistant__';

-- attendu : ERREUR — permission denied for table chatbot_passages. Le WHERE id = 0
-- ne désigne aucune ligne, là aussi au cas où le fichier part en sav_user.
UPDATE chatbot_passages SET texte = texte WHERE id = 0;

-- attendu : ERREUR — permission denied for view v_vehicles (une vue ne peut pas
-- être effacée par quelqu'un qui ne la possède pas).
DROP VIEW v_vehicles;


\echo ''
\echo '===== FIN ====='
\echo 'Section B : aucune erreur attendue. Section C : une erreur par ligne attendue.'
\echo 'Si plate_image, hourly_rate, users ou intervention_primes ont répondu sans'
\echo 'erreur, arrêtez et signalez-le avant de passer à la suite.'
