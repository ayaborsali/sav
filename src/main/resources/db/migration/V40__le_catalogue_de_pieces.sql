-- =============================================================================
-- V40__le_catalogue_de_pieces.sql — sous-écran « Gestion PR » de Maintenance
--
-- Une table : le catalogue des pièces de rechange, c'est-à-dire ce que chaque
-- pièce coûte et à qui elle se reprend. C'est le référentiel qui servira de
-- source au module Devis : une ligne de devis y copiera la référence, la
-- désignation et le prix de vente AU JOUR DU DEVIS, pour qu'un prix remanié six
-- mois plus tard ne réécrive pas un devis déjà accepté par le client.
--
-- Ce n'est PAS la table des pièces posées (item_parts, V21) : celle-là dit ce
-- qui a été monté sur un bus et ce qui part chez le constructeur. Celle-ci dit
-- ce que le catalogue propose. Les deux coexistent, et un ordre peut poser une
-- pièce qui n'est plus au catalogue.
--
-- Trois choix assumés, pris du projet et non de la fiche de conception :
--
-- 1. LES PRIX DÉRIVÉS SONT STOCKÉS. prix_revient et prix_vente sont des colonnes
--    comme les autres, mais personne ne les écrit : le service les recalcule à
--    chaque enregistrement (prix_ex_works × coefficient_revient, puis ×
--    coefficient_vente). Les stocker coûte un recalcul à l'import et rend une
--    liste triable et relisible six mois après ; ne pas les stocker obligerait
--    chaque écran — et chaque devis — à refaire la multiplication, donc à avoir
--    raison deux fois.
--
-- 2. TROIS DÉCIMALES, PAS DEUX. Le papier Higer et la facture d'import se
--    lisent au millime ; un prix de revient arrondi au centime seul ferait
--    glisser le prix de vente d'un devis de plusieurs dinars sur une grosse
--    ligne. C'est la raison pour laquelle ces colonnes sont en numeric(12,3)
--    alors que le reste du portail est en numeric(10,2) : le garde-fou du
--    catalogue des forfaits, qui refuse plus de deux décimales, n'est donc PAS
--    copié ici.
--
-- 3. LE COEFFICIENT N'EST PAS UNE CONSTANTE. coefficient_revient est saisi par
--    l'utilisateur, pièce par pièce, et c'est voulu : c'est lui qui porte à la
--    fois le transport, le dédouanement et la conversion de devise quand le prix
--    Ex Works est annoncé en euros. Le portail n'invente aucun taux de change et
--    ne calcule rien entre devises — devise décrit la devise D'ORIGINE du prix
--    Ex Works, tandis que prix_revient et prix_vente sont en dinars.
--
-- coefficient_vente peut rester vide : une pièce entrée au catalogue avant que
-- le gérant n'ait fixé sa marge existe. prix_vente reste alors vide, la ligne
-- s'affiche « — », et le futur devis la refusera tant qu'elle n'a pas de prix de
-- vente. Un devis ne se chiffre pas au hasard.
--
-- AUCUNE clé étrangère, comme les trente-neuf migrations précédentes : la
-- cohérence se refuse au service, en français, avec la référence en cause.
-- =============================================================================

CREATE TABLE IF NOT EXISTS piece_rechanges (
    id                  character varying(50)   NOT NULL,
    -- La référence Higer telle qu'elle est frappée sur la pièce et telle qu'on
    -- la redemande. Unique, et plié (sans accent, sans casse, sans espaces
    -- superflus) par le service : « 2115U-001 » et « 2115u-001 » sont la même
    -- pièce, et un doublon de référence dans le catalogue se paie en devis
    -- contradictoires chez le client.
    reference           character varying(160)  NOT NULL,
    designation_fr      character varying(300)  NOT NULL,
    -- La désignation anglaise peut manquer : le document Higer arrive parfois
    -- sans colonne anglaise, et rien ne doit être inventé à la place.
    designation_en      character varying(300),
    prix_ex_works       numeric(12,3)           NOT NULL,
    coefficient_revient numeric(10,4)           NOT NULL,
    prix_revient        numeric(12,3)           NOT NULL,
    coefficient_vente   numeric(10,4),
    prix_vente          numeric(12,3),
    fournisseur         character varying(160),
    -- Trois caractères, texte libre : EUR, TND, CNY… Le portail ne connaît pas
    -- la liste des devises de ses fournisseurs et n'en choisira pas une à la
    -- place de l'atelier.
    devise              character varying(3),
    -- Une pièce retirée reste en base : les lignes des devis et des ordres d'avant
    -- portent sa référence, et l'effacer ferait pointer un papier signé dans le
    -- vide. Le drapeau évite seulement qu'on la propose à une nouvelle ligne.
    active              boolean                 NOT NULL DEFAULT true,
    created_at          timestamp without time zone NOT NULL,
    updated_at          timestamp without time zone NOT NULL,
    CONSTRAINT piece_rechanges_pkey PRIMARY KEY (id),
    CONSTRAINT piece_rechanges_reference_key UNIQUE (reference),
    -- Un prix ou un coefficient nul ou négatif n'est pas une pièce chère, c'est
    -- une erreur de saisie qui se propage dans tous les devis d'après.
    CONSTRAINT piece_rechanges_prix_ex_works_check CHECK (prix_ex_works > 0),
    CONSTRAINT piece_rechanges_coefficient_revient_check CHECK (coefficient_revient > 0),
    CONSTRAINT piece_rechanges_coefficient_vente_check CHECK (coefficient_vente IS NULL OR coefficient_vente > 0)
);

-- La recherche du catalogue et l'autocomplétion du futur devis tapent la
-- référence ; la liste, elle, se lit par référence croissante (l'atelier
-- retrouve une pièce par son numéro, jamais par sa date d'entrée).
CREATE INDEX IF NOT EXISTS idx_piece_rechanges_reference ON piece_rechanges (reference);
CREATE INDEX IF NOT EXISTS idx_piece_rechanges_active ON piece_rechanges (active);
CREATE INDEX IF NOT EXISTS idx_piece_rechanges_designation_fr ON piece_rechanges (designation_fr);
