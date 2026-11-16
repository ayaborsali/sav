-- =============================================================================
-- V26__commande_de_pieces.sql — l'onglet « Pièces de rechange » du papier
--
-- Ce que le portail savait faire d'une pièce : elle est posée (ligne de papier,
-- V21) et elle est retournée au constructeur. Ce qu'il ne savait pas dire : la
-- pièce a été COMMANDÉE un jour, et LIVRÉ un autre — ou livrée seulement en
-- partie, ou annulée. Or c'est exactement ce qui bloque un bus : « la pièce est
-- commandée depuis trois semaines ».
--
-- Le bloc est posé sur le dossier de la famille « pièces », comme V23 a posé
-- ceux de visites et qualité : la liste de l'onglet est déjà ce dossier, une
-- table à part aurait doublé l'écran pour la même ligne.
--
-- Deux quantités, pas une : « commandée » et « reçue ». Une commande partielle
-- n'existe que par leur écart, et un seul chiffre effacerait la demande du
-- constructeur.
--
-- `part_reference` et `quantity` (les colonnes d'avant V21) ne bougent pas :
-- elles portent les dossiers écrits à l'époque. Les nouvelles cases ont leur nom
-- à elles, et rien ne se recopie de l'une à l'autre.
-- =============================================================================

alter table sav_cases add column if not exists order_date        date;
alter table sav_cases add column if not exists order_reference   character varying(120);
alter table sav_cases add column if not exists order_designation text;
alter table sav_cases add column if not exists quantity_ordered  integer;
alter table sav_cases add column if not exists quantity_received integer;
alter table sav_cases add column if not exists order_state       character varying(20);
alter table sav_cases add column if not exists delivery_date     date;
alter table sav_cases add column if not exists order_comments    text;
