-- =============================================================================
-- V59__le_catalogue_dit_sa_reference_fournisseur_et_son_competiteur.sql
--
-- La demande de l'atelier, écrite sur le papier de l'onglet « Gestion PR » :
-- « REF HIGER, REF FOURNISSEUR, NOM FOURNISSEUR, Prix Compétiteur ».
--
-- 1. « Réf. fournisseur » est le numéro que le fournisseur imprime sur sa boîte,
--    souvent différent de celui que Higer frappe sur la pièce. Il se garde à part,
--    et surtout PAS dans la colonne `reference` : celle-là est la référence Higer,
--    c'est elle qu'on redemande au constructeur et son unicité décide si une ligne
--    s'ajoute ou remplace. Deux numéros sous la même colonne feraient entrer la
--    même pièce deux fois au catalogue.
--
-- 2. « Prix compétiteur » est le prix qu'un autre atelier annonce pour la même
--    pièce, en dinars. Il ne remplace aucun des deux prix calculés par le serveur
--    (revient = Ex Works × coefficient de revient, vente = revient × coefficient
--    de vente) : c'est un renseignement de marché, et l'écran le signale en rouge
--    quand il passe sous notre prix de vente.
--
-- Les deux colonnes sont facultatives : les pièces déjà entrées n'en ont pas, et
-- « vide » veut dire « pas encore renseigné », jamais « zéro dinar » — un
-- compétiteur qui vend à zéro dinar n'existe pas, et un zéro écrit là se lirait
-- comme le prix le plus bas du marché.
-- =============================================================================

ALTER TABLE piece_rechanges
    ADD COLUMN IF NOT EXISTS reference_fournisseur character varying(160),
    ADD COLUMN IF NOT EXISTS prix_competiteur      numeric(12, 3);

ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_prix_competiteur_check;

ALTER TABLE piece_rechanges
    ADD CONSTRAINT piece_rechanges_prix_competiteur_check
        CHECK (prix_competiteur IS NULL OR prix_competiteur > 0);

COMMENT ON COLUMN piece_rechanges.reference_fournisseur IS
    'Numéro du fournisseur, distinct de la référence Higer. Facultatif : vide veut dire pas encore renseigné.';

COMMENT ON COLUMN piece_rechanges.prix_competiteur IS
    'Prix annoncé par un atelier concurrent, en dinars. Facultatif, jamais un zéro : il ne remplace aucun des prix calculés.';
