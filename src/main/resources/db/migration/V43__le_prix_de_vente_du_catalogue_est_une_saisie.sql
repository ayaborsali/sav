-- =============================================================================
-- V43__le_prix_de_vente_du_catalogue_est_une_saisie.sql — une case, pas un calcul
--
-- L'atelier reprécise sa demande du 05/11, et elle est nette : supprimer le
-- COEFFICIENT de vente était juste, supprimer le PRIX de vente allait trop loin.
-- « Ajouter prix de vente : il n'est calculé à partir d'aucun coefficient, c'est
-- un champ comme le prix Ex Works. »
--
-- La distinction est métier, et c'est elle qui tient les deux demandes ensemble :
--
--   • le prix de REVIENT ne se saisit pas : c'est un calcul
--     (prix Ex Works × coefficient de revient), donc une seule source, le serveur ;
--   • le prix de VENTE se saisit : personne ne le dérive de rien. Un taux de marge
--     posé sur la pièce serait un chiffre qu'aucun document Higer ni aucun contrat
--     ne fournit — ce que la règle du projet interdit. Le prix, lui, est connu de
--     l'atelier : il l'a toujours su, il ne pouvait plus l'écrire depuis hier.
--
-- Le catalogue reste donc à UN seul coefficient. Ce qui change est du côté de la
-- vente : une case, pas un taux.
--
-- nullable : une pièce peut fort bien entrer au catalogue avant que son prix de
-- vente soit décidé. Ce n'est PAS un prix à zéro — mettre 0 ferait facturer une
-- pièce gratuite, et un 0 par défaut se propage sur le premier devis qui la cite.
-- L'absence reste visible comme absence, comme partout dans ce portail.
--
-- En dinars, et non dans la devise de {@code devise} : cette colonne ne décrit que
-- le prix Ex Works, celui de la facture du fournisseur. Ce qui se revend ici se
-- revend en dinars tunisiens, comme le prix de revient à côté.
--
-- Rien ne recopie les six prix que V42 a effacés : c'étaient des produits d'un
-- coefficient de vente, et une valeur dont la source est un taux inventé n'est pas
-- une donnée à ressusciter. L'atelier les re-tape s'ils sont les siens.
--
-- Ce que la suite attend de cette colonne (validé avec l'atelier, pas encore codé) :
-- le jour où l'écran de devis choisira une pièce, la ligne copiera référence,
-- désignation et prix de vente AU JOUR DE LA COPIE — jamais un lien vivant vers le
-- catalogue, pour qu'un prix remanié six mois après ne réécrive pas un devis que le
-- client a déjà accepté. Une pièce sans prix de vente ne se placera pas sur un devis
-- sans qu'on le dise. Le catalogue, lui, n'est pas modifié par le devis.
-- =============================================================================

ALTER TABLE piece_rechanges
    ADD COLUMN IF NOT EXISTS prix_vente numeric(12,3);

ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_prix_vente_check;

ALTER TABLE piece_rechanges
    ADD CONSTRAINT piece_rechanges_prix_vente_check
        CHECK (prix_vente IS NULL OR prix_vente > 0);

COMMENT ON COLUMN piece_rechanges.prix_vente IS
    'Prix de vente tel que le saisit l''atelier, en dinars, au millime. Ne se calcule à partir d''aucun coefficient ; vide = pas encore de prix de vente, jamais 0.';
