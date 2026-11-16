-- =============================================================================
-- V18__sortie_entretien_forfait.sql — une sortie peut naître d'un rappel de palier
--
-- Jusqu'ici le tronc était coupé en deux : la panne envoyait un homme et une
-- voiture de déplacement (module Dépannage), la visite due d'un forfait se traitait
-- sur un papier saisi à la main (module Maintenance). Un bus dont le compteur
-- réclamait les 40 000 km partait quand même à l'atelier avec un technicien, une
-- voiture et une prime, et rien dans le portail ne le racontait : la sortie
-- n'existait que pour la panne, le palier se cochant sans que le travail fait
-- dehors y soit écrit.
--
-- Ces colonnes raccordent les deux :
--  - purpose : PANNE (le bus est en panne) ou ENTRETIEN (le bus doit une visite de
--    son forfait). La valeur est un varchar : elle dit à l'écran et au panneau des
--    primes pourquoi l'homme est sorti. Le barème, lui, reste le même pour les deux
--    motifs — une visite due d'un forfait se paie comme une panne, heures et sortie
--    comprises ; rien n'est distingué dans le calcul tant que le chef d'atelier n'a
--    pas confirmé.
--  - sold_plan_id / plan_step_id : l'engagement que la sortie exécute. Le palier
--    est désigné par son identifiant de catalogue, comme sur les documents de
--    maintenance — c'est le seul identifiant que l'écran et l'import Excel
--    connaissent tous les deux ;
--  - maintenance_item_id : le papier que la clôture de la sortie a créé. Écrit après
--    coup et jamais à l'envers : un relevé qui n'a rien coché doit rester muet.
--
-- intervention_id sur les rappels : la sortie déjà envoyée pour CE palier. Deux clics
-- sur « envoyer » ne doivent pas faire sortir deux hommes pour la même visite, et le
-- bandeau doit pouvoir dire « prise en charge par la sortie DP-2026-… » au lieu de
-- laisser croire que rien n'a été fait. Le rappel reste ouvert tant que le palier
-- n'est pas fait : une sortie envoyée n'est pas une visite accomplie.
--
-- Les sorties déjà enregistrées sont toutes des pannes : le défaut 'PANNE' les
-- reprend sans écrire de ligne, et aucun rappel passé n'a été « envoyé » faute de
-- porte pour le dire — leur colonne reste vide.
-- =============================================================================

alter table interventions add column if not exists purpose            character varying(20) not null default 'PANNE';
alter table interventions add column if not exists sold_plan_id       character varying(50);
alter table interventions add column if not exists plan_step_id       character varying(50);
alter table interventions add column if not exists maintenance_item_id character varying(50);

alter table sold_plan_alerts add column if not exists intervention_id character varying(50);
