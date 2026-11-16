-- =============================================================================
-- V23__visites_et_qualite_champs_de_reference.sql — donner un contenu aux deux
-- familles vides
--
-- Dans l'application de référence (`Application APV BUS HIGER`, `app.js:441-533`),
-- chaque onglet SAV a ses propres cases. Le portail, lui, n'en avait posé que sur
-- trois familles : Garanties (accord usine, appel en garantie), Pièces (la pièce
-- posée, V21) et Réclamations (la trace de l'appel, V20). « Visites » et « Qualité »
-- n'étaient que des noms d'onglet — et comme une famille vide sert de tiroir, les
-- dossiers sans étiquette y tombaient.
--
-- Les colonnes ci-dessous reprennent LES MOTS DU PAPIER de référence, sans en
-- inventer un seul :
--   visite : date planifiée, date de visite, statut de visite, compte rendu,
--            actions à suivre ;
--   qualité : date de déclaration, anomalie, classification, criticité, actions
--             correctives, actions préventives.
-- Trois d'entre elles existent déjà sous un autre nom et ne sont PAS dupliquées :
--   « PV / compte rendu »  = resolution   (écrite à la clôture du dossier) ;
--   « Anomalie »           = description ;
--   « Statut Ouvert/En cours/Clos » = status — la chaîne atelier du dossier.
--
-- visit_state se tient À PART de status, comme claim_state depuis V22 : le statut
-- du dossier suit la sortie de dépannage et les décisions de Saphir, alors qu'une
-- visite se planifie avant tout cela (« Reportée », « Annulée » n'ont rien à faire
-- dans la chaîne atelier). Un dossier qui change de famille perd son état de
-- visite : le serveur le refuse à l'écriture plutôt que de laisser une case
-- héritée d'une autre vie.
--
-- severity et classification restent du varchar : la grille de criticité vient
-- d'un document qui n'a jamais été fourni par le constructeur, ajouter un niveau
-- ne doit pas demander une migration.
-- =============================================================================

alter table sav_cases add column if not exists planned_date       date;
alter table sav_cases add column if not exists visit_date         date;
alter table sav_cases add column if not exists visit_state        character varying(20);
alter table sav_cases add column if not exists next_actions       text;

alter table sav_cases add column if not exists declaration_date   date;
alter table sav_cases add column if not exists classification     character varying(120);
alter table sav_cases add column if not exists severity           character varying(20);
alter table sav_cases add column if not exists corrective_actions text;
alter table sav_cases add column if not exists preventive_actions text;
