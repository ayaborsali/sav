-- =============================================================================
-- V41__devise_fermee_des_pieces.sql — la devise du prix Ex Works devient une liste
--
-- V40 laissait « devise » libre et facultative. Le gérant a tranché : quatre
-- devises connues (EUR, USD, TND, CNY) et EUR quand la case est vide — le
-- classeur Higer n'écrit pas toujours la colonne, et une pièce sans devise ne
-- doit pas rester sans étiquette.
--
-- Une devise hors liste est ramenée à EUR. Ce n'est pas une conversion : le
-- module vient d'être créé, aucune ligne réelle n'est censée porter une autre
-- devise. Le service refuse désormais toute nouvelle valeur hors liste, et
-- l'import refuse la ligne au lieu de la réétiqueter en silence.
-- =============================================================================

UPDATE piece_rechanges
   SET devise = 'EUR'
 WHERE devise IS NULL
    OR btrim(devise) = '';

-- « eur » et « EUR » sont la même devise ; on plie avant de vérifier la liste.
UPDATE piece_rechanges
   SET devise = upper(devise)
 WHERE devise <> upper(devise);

UPDATE piece_rechanges
   SET devise = 'EUR'
 WHERE devise NOT IN ('EUR', 'USD', 'TND', 'CNY');

ALTER TABLE piece_rechanges
    ALTER COLUMN devise SET DEFAULT 'EUR';

ALTER TABLE piece_rechanges
    ALTER COLUMN devise SET NOT NULL;

ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_devise_check;

ALTER TABLE piece_rechanges
    ADD CONSTRAINT piece_rechanges_devise_check
        CHECK (devise IN ('EUR', 'USD', 'TND', 'CNY'));
