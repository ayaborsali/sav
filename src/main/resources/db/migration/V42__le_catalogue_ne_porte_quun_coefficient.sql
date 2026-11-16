-- =============================================================================
-- V42__le_catalogue_ne_porte_quun_coefficient.sql — une seule marge de calcul
--
-- Le catalogue ne connaît QU'UN coefficient : celui du prix de revient. La
-- correction vient de l'atelier, et elle est métier : « on a seulement un
-- coefficient pour le prix de revient, pas de coefficient pour un prix de
-- vente ». Le transport, le dédouanement et la conversion de devise se payent
-- dans le coefficient de revient ; la marge, elle, ne se dérive pas d'un taux
-- posé sur une pièce, elle se décide au moment de chiffrer, ligne à ligne, sur
-- le devis. Un coefficient de vente stocké sur la pièce aurait fait deux choses
-- fausses :
--
--   1. Il aurait laissé croire qu'un prix de vente est une propriété de la
--      pièce. Il ne l'est pas : la même plaquette de frein ne se revend pas au
--      même prix selon qu'elle est posée sous forfait vendu, sous garantie, ou
--      sur un devis hors contrat.
--   2. Il aurait engagé le portail sur un taux qu'aucun document Higer ni aucun
--      contrat (SNTRI) ne fournit, ce qui est exactement ce que la règle du
--      projet interdit : rien d'inventé.
--
-- prix_vente tombe avec lui : il n'était que le produit du coefficient de vente,
-- et une case vide dont la source a disparu n'est pas une donnée, c'est une
-- attente. Le prix facturera plus tard depuis la ligne de devis
-- (maintenance_details.unit_price), recopié au jour du devis — donc insensible
-- à un prix remanié six mois après, comme pour V40.
--
-- V40 et V41 étant déjà appliquées sur sav_db, leur checksum est en base : elles ne
-- se rectifient pas, elles se suivent. C'est la chaîne du portail — la table garde
-- son nom et ses colonnes d'origine, seuls les deux prix dérivés de la vente
-- s'effacent.
--
-- Les pièces déjà entrées gardent leur prix Ex Works, leur coefficient et leur
-- prix de revient : la suppression des colonnes ne touche aucun chiffre qui
-- reste.
-- =============================================================================

ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_coefficient_vente_check;

ALTER TABLE piece_rechanges
    DROP COLUMN IF EXISTS coefficient_vente;

ALTER TABLE piece_rechanges
    DROP COLUMN IF EXISTS prix_vente;
