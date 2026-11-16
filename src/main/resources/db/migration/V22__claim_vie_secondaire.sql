-- =============================================================================
-- V22__claim_vie_secondaire.sql — un dossier garantie ne meurt pas à la clôture
--
-- Le statut d'un dossier s'arrête quand le bus est rendu. Un appel en garantie,
-- lui, commence à ce moment-là : le papier part chez le constructeur, et sa vie
-- se termine des semaines plus tard, par de l'argent ou par un refus.
--
-- Jusqu'ici le portail n'avait qu'une file de statuts pour les deux. « Approuvé »
-- et « Rejeté » y existent déjà, mais c'est un humain de chez Saphir qui les pose
-- — et dans un claim ces deux mots-là sont prononcés par le constructeur. Même
-- mot, deux mains, deux responsabilités : confondre les deux écrit dans un
-- papier de remboursement une décision que personne n'a prise.
--
-- claim_state tient donc la seconde vie, à part du statut du dossier :
--   A_DEPOSER -> DEPOSE -> ACCORDE | REFUSE | CONTESTE -> REGLE
-- Les montants sont deux colonnes distinctes, et aucune n'est calculée par le
-- portail :
--   claim_amount_claimed : ce que Saphir demande ;
--   claim_amount_granted : ce que le constructeur accorde.
-- La différence entre les deux est ce que Saphir perd ou gagne sur ce travail.
-- Le calculer exigerait le barème de remboursement du constructeur — un chiffre
-- qui n'existe dans aucun document reçu à ce jour. Le serveur se contente donc
-- d'interdire l'absurde (négatif, un réglage sans montant accordé) et laisse les
-- deux cases vides plutôt que d'inventer un taux.
--
-- Ces colonnes ne concernent que les dossiers de la famille GARANTIES ; le
-- service le refuse aux autres, parce qu'un claim de visite n'a rien à réclamer.
-- =============================================================================

alter table sav_cases add column if not exists claim_state          character varying(20);
alter table sav_cases add column if not exists claim_decision       text;
alter table sav_cases add column if not exists claim_decision_at    date;
alter table sav_cases add column if not exists claim_amount_claimed double precision;
alter table sav_cases add column if not exists claim_amount_granted double precision;
