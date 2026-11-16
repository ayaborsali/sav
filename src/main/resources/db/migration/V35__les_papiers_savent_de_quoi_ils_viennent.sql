-- =============================================================================
-- V35__les_papiers_savent_de_quoi_ils_viennent.sql -- chantier C de la liaison
-- des quatre onglets (devis -> ordre -> bon de livraison).
--
-- Le document de maintenance ne savait rien de sa propre histoire. Table
-- `maintenance_items` coupée par `type` (DEVIS / BL / OPERATION), elle portait
-- bien le forfait et le palier qu'elle exécute (V15), mais pas le papier dont
-- elle est née, ni la demande qui l'a fait ouvrir. Résultat : un ordre
-- réparait sans qu'on sache quel devis le client avait accepté, et le numéro
-- MT- n'existait que dans une phrase de description -- un texte qu'on peut
-- écraser à la main, pas un lien.
--
-- Deux colonnes, ni plus ni moins :
--
--   maintenance_items.mission_id : la demande (missions.id) à l'origine du
--     papier. C'est elle qui permet de rendre « les papiers de ce travail »
--     sur la mission : un devis puis l'ordre qui en sort, au lieu d'un seul
--     numéro que la case unique du 27/10 faisait disparaître.
--
--   maintenance_items.source_item_id : le papier d'avant, dans la même table
--     (self reference). Devis -> ordre, ordre -> bon de livraison. Une seule
--     colonne tient les deux maillons : un ordre ne naît pas à la fois d'un
--     devis et d'un ordre.
--
-- Les trois mêmes choix que V13 à V34, sans exception :
--
-- 1. AUCUNE CLÉ ÉTRANGÈRE. Le lien est un character varying(50) tenu par le
--    service, qui écrit le refus en français. Une clé étrangère n'empêcherait
--    pas de se tromper de papier : elle empêcherait seulement d'imprimer un
--    papier orphelin -- et un papier s'imprime, même quand sa source a été
--    rangée.
--
-- 2. TOUT EST NULLABLE. La très grande majorité des documents restent hors
--    chaîne : un BL rendu à la main, un ordre né d'une sortie d'entretien, un
--    devis griffonné sans demande. NULL dit « ce papier est un point de
--     départ », et c'est une phrase vraie, pas une donnée manquante.
--
-- 3. ON NE TOUCHE À AUCUNE LIGNE EXISTANTE. Aucun rattrapage : les papiers
--    déjà posés gardent leurs deux cases vides. Le portail ne devine pas de
--    quoi un ordre est né -- il l'écrit à partir du clic qui crée le suivant.
--
-- Pas de CHECK sur ces colonnes : elles ne portent aucun état. Le CHECK de
-- `status` connaît déjà les six statuts de MaintenanceStatus, « Annulé »
-- compris -- c'est l'écran qui ne le proposait pas, pas la base.
-- =============================================================================

ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS mission_id     character varying(50);
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS source_item_id character varying(50);

-- Les deux lectures qui comptent : « les papiers de cette demande » sur l'écran
-- Missions, et « ce papier en a-t-il déjà fait naître un autre » avant de le
-- supprimer ou de le refaire. Sans index, la seconde devient un scan complet
-- de la table dès qu'elle grossit.
CREATE INDEX IF NOT EXISTS idx_maintenance_items_mission_id
    ON maintenance_items (mission_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_items_source_item_id
    ON maintenance_items (source_item_id);
