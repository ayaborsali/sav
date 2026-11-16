-- =============================================================================
-- V38__lordre_de_travail_se_cloture.sql -- deuxième lot des trois maquettes
-- (devis -> ordre de travail -> bon de livraison), côté ordre.
--
-- L'ordre était un papier comme les autres : des lignes, un prix, un état tapé au
-- clavier. Or c'est LE papier de l'atelier — celui qui dit que quelqu'un a touché
-- au bus. Rien ne disait lesquelles des opérations prévues avaient été faites,
-- combien d'heures elles ont vraiment pris, ni qui les a vues. Sans ces trois
-- choses, la prime se réclame sur une déclaration, et le palier du forfait se coche
-- sur un papier qui ne prouve rien.
--
-- Cinq colonnes, deux tables :
--
--   maintenance_details.done        -- l'opération a-t-elle été faite ? Le
--     catalogue prévoit, l'atelier coche. Une ligne non cochée n'est pas une ligne
--     ratée : c'est une ligne que l'ordre ne peut pas encore clôturer.
--   maintenance_details.real_hours  -- les heures réellement passées, par ligne.
--     C'est la seule donnée qui réponde à « le technicien a-t-il travaillé le temps
--     qu'il déclare ? » quand la sortie et le forfait sont contrôlés.
--   maintenance_items.closed_on     -- la date de clôture, posée par le serveur au
--     clic sur « Clôturer l'ordre ». La même leçon que V25 côté dossier : une date
--     de fin tapée à la main est une date qu'on rapproche du jour où ça arrange.
--   maintenance_items.anomalies     -- ce qui a été VU sur le bus pendant le
--     travail. Un correctif constaté hors contrat part d'ici, et devient la ligne
--     du devis suivant.
--   maintenance_items.closure_reason -- la raison ÉCRITE qui remplace la preuve
--     photo quand elle n'existe pas (bus rendu sans cliché, fiche d'atelier perdue).
--     Le même garde-fou que le waiver des rappels : on peut clôturer sans photo,
--     jamais sans l'écrire.
--
-- Trois choix de la maison, reconduits :
--
-- 1. AUCUNE CLÉ ÉTRANGÈRE et aucun rattrapage des lignes existantes. Les ordres
--    déjà posés gardent leurs cases vides : `done` NULL veut dire « non coché »,
--    pas « faux », et le portail ne devine pas ce qui a été fait sur un papier
--    écrit avant ce champ.
--
-- 2. TOUT EST NULLABLE EN BASE, OBLIGATOIRE AU SERVEUR AU BON MOMENT. Ni `done` ni
--    `real_hours` ne sont exigés à l'enregistrement : c'est la clôture qui les
--    réclame, et qui écrit en français ce qui manque. Un devis a des lignes sans
--    case « fait » — il ne travaille pas.
--
-- 3. AUCUN MOT NOUVEAU DANS `status`. L'ordre avance avec les mots déjà là
--    (DRAFT -> IN_PROGRESS -> COMPLETED), et le CHECK de V36 les connaît tous.
--    Rien à réécrire ici — la leçon de V30 (un CHECK plus vieux que l'enum) ne se
--    reproduit pas tant qu'aucun état neuf n'entre dans la colonne.
--
-- Pas de colonne « prix » nouvelle, et l'ordre cesse d'en porter : il décrit le
-- travail, il ne le chiffre pas. Les montants déjà enregistrés sur les ordres
-- existants ne sont ni effacés ni recalculés — ils restent lisibles tels qu'ils
-- ont été posés, et c'est le formulaire qui ne les remplit plus.
-- =============================================================================

ALTER TABLE maintenance_details ADD COLUMN IF NOT EXISTS done boolean;
ALTER TABLE maintenance_details ADD COLUMN IF NOT EXISTS real_hours double precision;

ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS closed_on      date;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS anomalies      text;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS closure_reason text;

-- Des heures négatives ne se passent pas : elles se tapent. La colonne reste
-- nullable (une ligne non faite n'a pas d'heures), mais une valeur écrite doit
-- être une durée.
ALTER TABLE maintenance_details DROP CONSTRAINT IF EXISTS maintenance_details_real_hours_check;
ALTER TABLE maintenance_details ADD CONSTRAINT maintenance_details_real_hours_check
    CHECK (real_hours IS NULL OR real_hours >= 0::double precision);
