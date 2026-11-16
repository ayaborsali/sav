-- =============================================================================
-- V29__missions_porte_unique.sql — une seule porte vers une sortie, une seule
-- « visite périodique »
--
-- M1 a posé la mission mais n'a pas fermé les deux autres portes : la case
-- « un technicien doit aller sur place » du dossier et le bouton d'envoi du
-- rappel faisaient naître une sortie chacun leur tour. Trois portes pour un même
-- homme parti, c'est trois histoires de la même journée — exactement le défaut
-- que le module doit empêcher. Ce lot-ci les réunit : la mission est le seul
-- endroit où l'atelier répond.
--
-- Conséquence sur la table : une mission n'a plus besoin d'un dossier. Le rappel
-- d'un palier atteint (forfait vendu, contrat en cours) fait sortir un homme et
-- une voiture sans que personne n'ait appelé — il n'y a donc aucun dossier à
-- rattacher, et inventer un faux dossier SAV pour loger la demande serait mentir
-- sur l'origine. sav_case_id devient nullable, alert_id apparaît : la mission
-- répond soit à un client qui a parlé, soit à une date ou un compteur qui a
-- tourné. Jamais aux deux, jamais à rien — le serveur le vérifie.
--
-- Deuxième objet de ce lot : « visite périodique » désignait deux choses qui ne
-- se connaissaient pas, la famille VISITES d'un dossier et le palier d'un forfait
-- vendu. Les deux colonnes sur sav_cases les marient : un dossier de visite peut
-- dire quel palier il couvre, et le rappel peut dire qu'un dossier est déjà
-- ouvert. Aucun lien forcé, aucune clé étrangère (règle du portail) : le serveur
-- vérifie l'appartenance au moment d'écrire.
--
-- Toujours aucune colonne de montant ici. Une mission qui établirait un devis
-- (troisième réponse, celle de l'atelier payant) ne fait que pointer le papier :
-- le prix se saisit sur le devis, jamais dans la mission.
-- =============================================================================

ALTER TABLE missions ALTER COLUMN sav_case_id DROP NOT NULL;

ALTER TABLE missions ADD COLUMN IF NOT EXISTS alert_id character varying(50);

-- L'origine d'une mission se lit par l'un ou l'autre de ces deux pointeurs ; les
-- deux index servent chacun une question d'écran (« les demandes de ce dossier »,
-- « ce rappel a-t-il déjà une mission ? »).
CREATE INDEX IF NOT EXISTS idx_missions_alert ON missions (alert_id);

ALTER TABLE sav_cases ADD COLUMN IF NOT EXISTS sold_plan_id character varying(50);
ALTER TABLE sav_cases ADD COLUMN IF NOT EXISTS plan_step_id character varying(50);

CREATE INDEX IF NOT EXISTS idx_sav_cases_sold_plan ON sav_cases (sold_plan_id, plan_step_id);
