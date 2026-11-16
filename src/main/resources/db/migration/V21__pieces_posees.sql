-- =============================================================================
-- V21__pieces_posees.sql — une pièce posée devient un objet qu'on peut nommer
--
-- Le dossier SAV prétendait savoir quelle pièce est tombée : il avait une case
-- « Référence pièce » et une case « Quantité ». Une seule référence pour un
-- travail qui en pose souvent trois. Et à côté, le document de maintenance porte
-- déjà des lignes (référence, libellé, quantité, prix) — donc deux endroits pour
-- la même chose, et aucun des deux ne peut être pointé par quelqu'un d'autre.
--
-- Ces lignes-là n'ont pas d'identité : ce sont des lignes d'un document, portées
-- par une collection sans clé. Rien ne peut dire « CETTE pièce » — ni un claim
-- qui doit la réclamer, ni un retour à tracer, ni un stock à débiter.
--
-- item_parts pose ce qui manquait, et seulement ça :
--   - une clé propre, pour qu'un papier de remboursement puisse la nommer ;
--   - vehicle_id : dans quel bus elle est montée. Repris du document, jamais
--     retapé — un service qui demande l'immatriculation une deuxième fois est un
--     service qui aura deux versions de la réponse ;
--   - returned / returned_at / return_reason : la pièce cassée est-elle repartie
--     chez le constructeur. C'est LA case qui fait perdre des remboursements :
--     un constructeur qui demande la pièce et ne la reçoit pas annule le claim,
--     et personne, dans le portail, ne pouvait s'en souvenir ;
--   - sav_case_id : le dossier garantie qui la réclame, une fois ce dossier né.
--
-- Le type « pièce » n'est pas ajouté aux lignes de prix du document : une ligne
-- de devis peut être une heure de main-d'œuvre, et le distinguer demande un
-- barème que Saphir n'a pas encore reçu. Ici on ne range que des pièces, posées
-- sur un bus, avec leur sort écrit.
--
-- maintenance_items garde ses lignes de prix telles quelles (elles portent le
-- montant du document). Les colonnes part_reference et quantity du dossier SAV
-- restent en base, lisibles pour les dossiers d'avant, mais le formulaire ne les
-- remplit plus : la pièce d'un dossier se lit désormais dans ses lignes posées.
-- =============================================================================

CREATE TABLE IF NOT EXISTS item_parts (
    id                  character varying(50)  NOT NULL,
    -- Le papier de maintenance pendant lequel la pièce est montée.
    maintenance_item_id character varying(50)  NOT NULL,
    -- Le bus : repris du document, jamais saisi une seconde fois.
    vehicle_id          character varying(255) NOT NULL,
    -- Le dossier garantie qui la réclame au constructeur. Nullable : une pièce
    -- posée sur un bus hors garantie n'a rien à réclamer.
    sav_case_id         character varying(50),
    reference           character varying(255),
    designation         character varying(255),
    quantity            double precision,
    -- La date du montage, laissée libre : c'est le document qui la connaît, et un
    -- portail qui la déduirait inventerait un fait.
    fitted_on           date,
    returned            boolean                NOT NULL DEFAULT false,
    returned_at         date,
    return_reason       text,
    CONSTRAINT item_parts_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_item_parts_item ON item_parts (maintenance_item_id);
CREATE INDEX IF NOT EXISTS idx_item_parts_vehicle ON item_parts (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_item_parts_sav_case ON item_parts (sav_case_id);
