-- =============================================================================
-- V44__la_famille_obligatoire_du_catalogue.sql — une pièce, un rayon
--
-- La demande de l'atelier : « ajouter type PR - famille (mecanique / suspension /
-- electricite / freinage / boite de vitesse / pont arriere / divers) », et sa
-- réponse à la question qui suivait : « Non, obligatoire. »
--
-- Obligatoire veut dire deux choses, et la base tient les deux :
--
--   1. la colonne ne peut pas être vide — SET NOT NULL ;
--   2. elle ne peut pas porter n'importe quel mot — une CHECK fermée sur les sept.
--
-- Le service refuse déjà la même chose en français, mais un `sql` tapé à la main
-- ou un autre module qui écrirait la table contournerait le service. C'est la
-- leçon de V30 à l'envers : là où la contrainte de statut était trop courte par
-- rapport au Java, ici la base connaît la colonne ET ses sept mots. La colonne
-- porte le nom que le Java écrit (l'enum, en majuscules), pas le libellé accentué
-- que l'écran montre.
--
-- « Divers » existe dans la liste, et pourtant rien n'y est rangé ici par défaut.
-- Un rangeur silencieux ferait disparaître une pièce du rayon où on la cherche ;
-- le repli n'existe pas, une famille hors liste est refusée et nomme les sept mots.
--
-- Les dix-neuf lignes déjà au catalogue sont classées d'après leur propre
-- désignation française — « Plaquette de frein AV » va au Freinage, « Amortisseur »
-- à la Suspension, « Filtre » et « Joint de culasse » à la Mécanique. C'est une
-- lecture de SES données, pas un référentiel Higer : les sept mots viennent de lui,
-- le rang de chaque pièce se corrige à l'écran si l'atelier le voit autrement.
-- Boîte de vitesse et Pont arrière restent vides, faute de pièce qui les nomme —
-- une case vide vaut mieux qu'une classification inventée.
--
-- La pièce inactive (10107-11) est classée elle aussi : elle reste relisible par
-- les papiers d'avant, et une ligne reprise un jour ne doit pas se retrouver sans
-- rayon.
-- =============================================================================

ALTER TABLE piece_rechanges
    ADD COLUMN IF NOT EXISTS famille character varying(32);

-- Le rang de chaque référence, écrit explicitement plutôt que déduit par motif :
-- un « FIL110 Filtre à air » rangé par une règle à mots-clés se classerait
-- peut-être un jour ailleurs, et une migration doit dire ce qu'elle fait.
UPDATE piece_rechanges
   SET famille = classement.famille
  FROM (VALUES
        ('10107-11',    'MECANIQUE'),     -- Joint de culasse
        ('9.8001.045',  'MECANIQUE'),     -- Filtre à gasoil
        ('COU400',      'MECANIQUE'),     -- Courroie d'alternateur
        ('FIL100',      'MECANIQUE'),     -- Filtre à huile
        ('FIL110',      'MECANIQUE'),     -- Filtre à air
        ('FIL120',      'MECANIQUE'),     -- Filtre à gasoil
        ('JNT700',      'MECANIQUE'),     -- Joint de culasse
        ('11B-04010',   'SUSPENSION'),    -- Amortisseur AV
        ('5210-114',    'SUSPENSION'),    -- Barre stabilisatrice AV
        ('2115U-001',   'FREINAGE'),      -- Plaquette de frein AV
        ('PLQ300',      'FREINAGE'),      -- Plaquettes de frein avant
        ('PLQ310',      'FREINAGE'),      -- Plaquettes de frein arrière
        ('2165J-002',   'ELECTRICITE'),   -- Capteur de pression
        ('3510-010',    'ELECTRICITE'),   -- Alternateur 28V
        ('AMP500',      'ELECTRICITE'),   -- Ampoule de phare
        ('BAT600',      'ELECTRICITE'),   -- Batterie 12V 200Ah
        ('CAP200',      'ELECTRICITE'),   -- Capteur
        ('capgps-011',  'ELECTRICITE'),   -- capteur gps
        ('8110-002',    'DIVERS')         -- Rétroviseur extérieur droit
       ) AS classement(reference, famille)
 WHERE piece_rechanges.reference = classement.reference;

-- Ce que la liste n'a pas rangé reste visible ici plutôt que parti en silence.
UPDATE piece_rechanges
   SET famille = 'DIVERS'
 WHERE famille IS NULL;

ALTER TABLE piece_rechanges
    ALTER COLUMN famille SET NOT NULL;

ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_famille_check;

ALTER TABLE piece_rechanges
    ADD CONSTRAINT piece_rechanges_famille_check
        CHECK (famille IN ('MECANIQUE', 'SUSPENSION', 'ELECTRICITE', 'FREINAGE',
                           'BOITE_VITESSE', 'PONT_ARRIERE', 'DIVERS'));

-- Le filtre par famille sert à préparer une commande : « tout le Freinage », sans
-- s'occuper de la pagination. La liste des références reste la porte principale.
CREATE INDEX IF NOT EXISTS idx_piece_rechanges_famille ON piece_rechanges (famille);

COMMENT ON COLUMN piece_rechanges.famille IS
    'Rayon du catalogue, parmi les sept mots de l atelier. Obligatoire, jamais laissé vide ni rempli par un repli silencieux.';
