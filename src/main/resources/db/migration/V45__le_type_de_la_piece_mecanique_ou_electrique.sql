-- ============================================================================
-- V45 — le « type » de la pièce : mécanique ou électrique, en plus de la famille
-- ============================================================================
-- L'atelier range déjà ses pièces dans une des sept familles (V44). Il demande en
-- plus UN deuxième classement, à deux mots seulement : « type: mecanique ou
-- electrique ». Ce n'est pas un doublon de la famille : la famille dit où chercher
-- (Freinage, Suspension, Boîte de vitesse...), le type dit de quelle nature est la
-- pièce pour la commande et pour le devis, où l'atelier ne distingue que deux
-- choses — une pièce qui se monte, une pièce qui se branche.
--
-- La case reste LIBRE, contrairement à la famille :
--
--   - la famille est obligatoire parce que sans range la pièce se retrouve plus ;
--   - le type se lit « à classer » quand il est vide. Il n'est NOT NULL que pour les
--     lignes d'aujourd'hui, que ce script range une bonne fois ; une pièce importée
--     demain sans colonne « Type » arrivera vide et se complétera à l'écran. Aucun
--     repli silencieux : ici, le seul repli qui existe est écrit dans ce fichier, en
--     clair, pour les dix-neuf lignes que l'atelier avait déjà.
--
-- Le classement de départ suit SA famille : ce qui était « Électricité » devient
-- « Électrique », tout le reste « Mécanique ». C'est une reprise de ce que l'atelier
-- a lui-même posé le 05/11, pas une déduction du portail ; il a la main sur chaque
-- ligne depuis l'écran.
-- ============================================================================

ALTER TABLE piece_rechanges
    ADD COLUMN IF NOT EXISTS type_piece character varying(16);

COMMENT ON COLUMN piece_rechanges.type_piece IS
    'Nature de la pièce pour la commande et le devis : Mecanique ou Electrique, les deux mots de l atelier. Vide veut dire a classer, jamais mecanique par defaut.';

-- Une seule écriture, lisible : les pièces du rayon Électricité, et rien d'autre.
UPDATE piece_rechanges
   SET type_piece = 'ELECTRIQUE'
 WHERE famille = 'ELECTRICITE';

UPDATE piece_rechanges
   SET type_piece = 'MECANIQUE'
 WHERE type_piece IS NULL;

-- La liste est fermée, comme pour la famille. Un troisième mot (« hybride »,
-- « pneumatique ») se discutera en atelier et passera par une migration, avec le
-- test de parité qui relit pg_constraint pour le redire.
ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_type_piece_check;

ALTER TABLE piece_rechanges
    ADD CONSTRAINT piece_rechanges_type_piece_check
        CHECK (type_piece IS NULL OR type_piece IN ('MECANIQUE', 'ELECTRIQUE'));

CREATE INDEX IF NOT EXISTS idx_piece_rechanges_type_piece ON piece_rechanges (type_piece);
