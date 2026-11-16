-- =============================================================================
-- V15__documents_forfaits_et_pieces_jointes.sql — phase C du module
-- « Forfaits de maintenance »
--
-- Le catalogue (V13) dit ce que l'atelier propose, les forfaits vendus (V14)
-- disent ce qu'un client a acheté pour un bus. Cette phase rattache le PAPIER à
-- l'engagement : un devis, un ordre de réparation ou un BL peut dire « je fais le
-- palier 45 000 km de ce forfait », et le palier se met à jour tout seul quand le
-- document est livré.
--
-- Trois colonnes et une table :
--   maintenance_items.sold_plan_id / plan_step_id : le rattachement, tous deux
--     facultatifs. Un BL sans forfait reste un BL — c'est la règle de la phase.
--   maintenance_details.quantity_text : la quantité telle que le plan Higer
--     l'écrit (« 23 L », « 6.5 kg »). La colonne quantity reste un nombre pour
--     calculer le total ; sans ce texte, l'unité du document de référence
--     disparaîtrait du bon de travail, et l'atelier n'aurait plus qu'à la
--     retrouver dans le classeur.
--   maintenance_attachments : les pièces jointes d'un document (BL signé scanné,
--     liste de prix, plan). Le fichier est sur le DISQUE, la base ne garde que le
--     chemin, l'empreinte et la taille — comme les photos de dépannage (V7).
--
-- Les mêmes trois choix que V13 et V14, sans exception :
--
-- 1. AUCUNE clé étrangère. La cohérence (le bus du document est celui du forfait,
--    le palier appartient à ce forfait) est vérifiée dans SoldPlanService, qui
--    écrit le refus en français. Une clé étrangère n'empêche pas de se tromper de
--    bus : elle empêcherait seulement de lire un document orphelin.
--
-- 2. TOUT EST NULLABLE SAUF LE SUIVI ÉLÉMENTAIRE. Un document sans forfait, une
--    ligne sans prix, un palier sans compteur : le module doit tourner avec.
--
-- 3. ON N'EFFACE RIEN SANS LE DIRE. Les pièces jointes supprimées le sont du
--    disque ET de la base par le service, avec une ligne d'audit ; aucune colonne
--    de ce fichier n'est supprimée, aucune donnée existante n'est touchée.
-- =============================================================================

-- Le document de maintenance sait à quel forfait il se rattache.
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS sold_plan_id character varying(50);
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS plan_step_id  character varying(50);

-- « Quels documents sont liés à ce forfait ? » se pose à chaque ouverture d'un
-- forfait vendu, et c'est la liste des paliers faits qui se construit dessus.
CREATE INDEX IF NOT EXISTS idx_maintenance_items_sold_plan ON maintenance_items (sold_plan_id);

-- La quantité du plan est un texte avec son unité ; le nombre reste ce que calcule
-- le total. Les deux coexistent, aucun des deux n'est obligatoire.
ALTER TABLE maintenance_details ADD COLUMN IF NOT EXISTS quantity_text character varying(60);

CREATE TABLE IF NOT EXISTS maintenance_attachments (
    id                  character varying(50)   NOT NULL,
    -- Le document de maintenance (devis, BL, opération) qui porte la pièce.
    maintenance_item_id character varying(50)   NOT NULL,
    -- Le nom tel que l'expéditeur l'a écrit, nettoyé : c'est ce que l'écran affiche.
    original_name       character varying(255)  NOT NULL,
    -- image/jpeg, image/png, application/pdf — vérifié sur l'entête du fichier.
    content_type        character varying(100)  NOT NULL,
    size_bytes          bigint                  NOT NULL,
    -- Chemin RELATIF au dossier de stockage, écrit par le serveur. Jamais par le
    -- navigateur : c'est ce qui rend le « ../../ » inoffensif à la relecture.
    storage_path        character varying(500)  NOT NULL,
    -- Empreinte des octets tels que reçus : elle prouve que le fichier n'a pas été
    -- modifié après versement, et elle refuse le même fichier versé deux fois.
    sha256              character varying(64)   NOT NULL,
    uploaded_by         character varying(255),
    uploaded_at         timestamp without time zone NOT NULL,
    CONSTRAINT maintenance_attachments_pkey PRIMARY KEY (id)
);

-- La liste des pièces d'un document s'affiche à chaque ouverture de fiche.
CREATE INDEX IF NOT EXISTS idx_maintenance_attachments_item ON maintenance_attachments (maintenance_item_id);
