-- =============================================================================
-- V37__le_fournisseur_de_la_piece_s_ecrit_saphir.sql -- corrige l'orthographe
-- d'un mot neuf, pendant qu'il n'y a encore rien à traduire.
--
-- V36 a posé `maintenance_details.supplied_by` avec la valeur « SAPPHIR », deux
-- P. Le porteur du portail s'appelle Saphir — un P — et ce mot n'est pas une
-- note interne : il sort dans le JSON du devis, il se recopie dans le sélecteur
-- de l'écran, et il se relira sur la facture. Un mot mal écrit dans une colonne
-- d'argent se propage plus vite qu'il ne se corrige.
--
-- V36 est appliquée chez lui depuis le 03/11 (09:34), donc on ne la touche pas :
-- ce fichier la suit et reprend le CHECK. Trois cases valident le risque :
--
-- — la colonne a été créée par V36 elle-même, aucune ligne hors portail ne peut
--   avoir écrit « SAPPHIR » entre-temps ;
-- — le UPDATE qui précède le CHECK ne rencontre aujourd'hui que des cases vides
--   (vérifié en base : 0 ligne avec une nature ou un fournisseur) ; il est là
--   pour que la contrainte neuve ne casse pas une base qui aurait déjà des mots
--   anciens ;
-- — `ddl-auto=validate` ne regarde pas les CHECK, seulement les colonnes : le
--   redémarrage du backend ne change rien de ce côté.
--
-- Le mot du vocabulaire reste porté par l'enum `PartSupplier` : la base connaît
-- les mots, l'écran lit leur `label()`, et « fourni par » ne se facture que sur
-- une pièce.
-- =============================================================================

UPDATE maintenance_details SET supplied_by = 'SAPHIR' WHERE supplied_by = 'SAPPHIR';

ALTER TABLE maintenance_details DROP CONSTRAINT IF EXISTS maintenance_details_supplied_by_check;
ALTER TABLE maintenance_details ADD CONSTRAINT maintenance_details_supplied_by_check
    CHECK ((supplied_by IS NULL) OR ((supplied_by)::text = ANY (ARRAY[
        'SAPHIR'::character varying,
        'CLIENT'::character varying]::text[])));
