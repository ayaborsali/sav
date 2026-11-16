-- =============================================================================
-- V46__la_ligne_du_devis_cite_la_piece_du_catalogue.sql — d'où vient la pièce
--
-- La demande de l'atelier : « maintenant on va procéder avec devis et la liaison
-- entre devis et gestion PR ». Jusqu'ici une ligne de devis ne savait pas d'où
-- venait sa pièce : la référence était du texte libre et le prix se tapait à la
-- main. Le catalogue existait à côté, sans lien avec le papier.
--
-- Ce que la colonne ajoute, et ce qu'elle n'est PAS :
--
--   1. Ce n'est PAS une clé étrangère vivante. Une pièce retirée du catalogue reste
--      en base (active = false, jamais effacée), donc un devis d'avant garde sa
--      trace. Une référence supprimée ne doit pas emporter la ligne d'un papier que
--      le client a signé : la copie du devis est figée au jour où elle a été faite.
--
--   2. C'est une TRACE, pas un recalcul. Le prix et les mots restent ceux du devis
--      (maintenance_details.reference / label / unit_price) : si le catalogue
--      change de prix six mois plus tard, le devis accepté ne bouge pas. La ligne
--      est une copie, l'identifiant dit seulement de quelle pièce elle est la copie.
--
--   3. La colonne est NULLABLE : les lignes tapées à la main — main-d'œuvre,
--      déplacement, pièce apportée par le client, et les deux vieux devis — restent
--      valides. Vide veut dire « ligne saisie sans catalogue », pas « pièce
--      inconnue ».
--
--   4. L'identifiant descend la chaîne : l'ordre né du devis et le bon de livraison
--      né de l'ordre reprennent piece_id, pour que le BL puisse dire quelle pièce
--      du catalogue est partie avec le bus. La copie le fait déjà pour les autres
--      cases de la ligne.
--
-- Aucun index n'est posé : la seule lecture est « la ligne porte-t-elle un
-- identifiant ? », jamais « quels devis citent cette pièce ? ». Un index se paie à
-- chaque écriture de ligne ; il attendra la question qui le justifie.
-- =============================================================================

ALTER TABLE maintenance_details
    ADD COLUMN IF NOT EXISTS piece_id character varying(50);

COMMENT ON COLUMN maintenance_details.piece_id IS
    'Piece du catalogue dont cette ligne de devis est la copie figee. NULL = ligne saisie sans catalogue. Aucune cle etrangere : une piece retiree reste relisible par les papiers d avant.';
