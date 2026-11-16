-- =============================================================================
-- V36__le_devis_a_ses_etats_et_ses_lignes_naturees.sql -- premier lot des trois
-- maquettes (devis -> ordre de travail -> bon de livraison), côté devis.
--
-- Le devis jouait avec les états de l'ordre et du bon de livraison. Une seule
-- colonne `status` pour les trois papiers : « un devis accepté » se disait
-- VALIDATED, le même mot qu'un devis « validé » par l'atelier, et rien dans la
-- base ne racontait qu'il avait été ENVOYÉ au client, ACCEPTÉ un mardi, REFUSÉ
-- parce que trop cher. Or c'est là que l'argent se décide : personne ne pose une
-- ligne de commande sur un papier que le client n'a pas signé, et un refus sans
-- motif écrit laisse le palier dû sans que quiconque sache pourquoi.
--
-- Deux mots neufs, et une date pour chacun :
--
--   SENT (« Envoyé »)      -- le papier est parti chez le client. C'est ce geste
--     qui ferme la modification : ce qui a été envoyé est une parole donnée, et
--     modifier le montant après coup ne s'appelle pas corriger, mais mentir sur
--     le papier signé.
--   VALIDATED (« Accepté »)-- le client a dit oui, preuve jointe. Le mot change
--     de sens à l'écran, la valeur en base reste : les papiers déjà posés
--     gardent leur histoire.
--   REFUSED (« Refusé »)   -- le client a dit non, et le motif est obligatoire
--     (refusal_reason). Le palier du forfait reste dû : un refus ne décompte
--     rien, il n'a rien fait.
--
-- Deux colonnes par ligne (`maintenance_details`), parce que le devis est le
-- seul papier où l'argent se lit :
--
--   nature      -- LABOR / PART / TRAVEL : main-d'œuvre, pièce, déplacement. Le
--     contrat SNTRI ne couvre que la main-d'œuvre ; tout le reste demande un
--     devis. Sans ce mot sur la ligne, l'atelier ne peut pas dire ce qu'il
--     chiffre, et Higer pas plus.
--   supplied_by -- SAPPHIR / CLIENT, rempli seulement pour une pièce. Le contrat
--     oblige le client à fournir certaines pièces : « fourni par le client » est
--     une ligne à 0 sur notre papier, et « fourni par Saphir » est une ligne qui
--     se facture. C'est la seule façon de savoir qui a payé quoi.
--
-- Trois choix de la maison, reconduits sans exception :
--
-- 1. AUCUNE CLÉ ÉTRANGÈRE, aucun rattrapage sur les lignes existantes. Les
--    papiers déjà posés gardent leurs cases vides : le portail ne devine pas la
--    nature d'une ligne écrite sans ce champ, il l'écrit à partir du formulaire.
--    Une valeur inventée par migration serait une valeur inventée.
--
-- 2. TOUT EST NULLABLE EN BASE, OBLIGATOIRE AU SERVEUR. La contrainte ne porte
--    pas la règle métier : un ordre né d'un catalogue de forfaits a des lignes
--    sans nature, et ce n'est pas un défaut. C'est le service qui refuse une
--    ligne de devis sans nature, et qui écrit le refus en français.
--
-- 3. LE CHECK DE `status` EST REPRIS ENTIÈREMENT. Leçon de V30 : une contrainte
--    plus vieille que Flyway ne connaissait que six des huit états du dossier,
--    et « j'arrive » partait en 500 sur l'écran du technicien. Ajouter un état à
--    un enum varchar sans réécrire le CHECK, c'est remettre ça.
--
-- Pas de colonne « signé par » ni « réserves » ici : elles appartiennent au bon
-- de livraison, et ce sera le troisième lot.
-- =============================================================================

ALTER TABLE maintenance_items DROP CONSTRAINT IF EXISTS maintenance_items_status_check;
ALTER TABLE maintenance_items ADD CONSTRAINT maintenance_items_status_check
    CHECK (((status)::text = ANY (ARRAY[
        'DRAFT'::character varying,
        'SENT'::character varying,
        'VALIDATED'::character varying,
        'REFUSED'::character varying,
        'IN_PROGRESS'::character varying,
        'DELIVERED'::character varying,
        'COMPLETED'::character varying,
        'CANCELLED'::character varying]::text[])));

-- Les trois dates de l'accord et le motif du refus. Des `date` et pas des
-- horodatages : le client accepte un papier, pas une seconde d'horloge, et le
-- papier qu'il signe porte un jour.
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS sent_on        date;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS accepted_on    date;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS refused_on     date;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS refusal_reason text;

-- La nature de la ligne et qui fournit la pièce. Vingt caractères, comme les
-- autres colonnes d'état de cette table.
ALTER TABLE maintenance_details ADD COLUMN IF NOT EXISTS nature      character varying(20);
ALTER TABLE maintenance_details ADD COLUMN IF NOT EXISTS supplied_by character varying(20);

ALTER TABLE maintenance_details DROP CONSTRAINT IF EXISTS maintenance_details_nature_check;
ALTER TABLE maintenance_details ADD CONSTRAINT maintenance_details_nature_check
    CHECK ((nature IS NULL) OR ((nature)::text = ANY (ARRAY[
        'LABOR'::character varying,
        'PART'::character varying,
        'TRAVEL'::character varying]::text[])));

ALTER TABLE maintenance_details DROP CONSTRAINT IF EXISTS maintenance_details_supplied_by_check;
ALTER TABLE maintenance_details ADD CONSTRAINT maintenance_details_supplied_by_check
    CHECK ((supplied_by IS NULL) OR ((supplied_by)::text = ANY (ARRAY[
        'SAPPHIR'::character varying,
        'CLIENT'::character varying]::text[])));
