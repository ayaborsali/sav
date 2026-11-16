-- =============================================================================
-- V4__contrat.sql — contrat de vente véhicule
--
-- Une entité « contracts » pour l'en-tête du contrat (numéro, date, client,
-- conditions de paiement, adresse de livraison, remarques). Pas de table de
-- lignes séparée : les véhicules du contrat sont les véhicules qui pointent sur
-- lui via vehicles.contract_id, chacun portant son prix unitaire HT.
--
-- Les totaux (HT, TVA, TTC) ne sont PAS stockés : ils sont recalculés à chaque
-- lecture par le serveur à partir des prix des lignes. Un contrat ne peut donc
-- jamais afficher un total incohérent avec ses lignes.
--
-- Le numéro est généré par le serveur (CT-HIG-AAAA-NNN) : jamais saisi à la main.
-- Colonnes en snake_case, dimensions identiques aux annotations @Column.
-- =============================================================================

CREATE TABLE IF NOT EXISTS contracts (
    id               character varying(50)  NOT NULL,
    number           character varying(40)  NOT NULL,
    contract_date    date                   NOT NULL,
    client_id        character varying(255) NOT NULL,
    payment_terms    character varying(120),
    delivery_address character varying(500),
    notes            character varying(1000),
    CONSTRAINT contracts_pkey PRIMARY KEY (id),
    CONSTRAINT contracts_number_key UNIQUE (number)
);

CREATE INDEX IF NOT EXISTS idx_contracts_client_id ON contracts (client_id);
CREATE INDEX IF NOT EXISTS idx_contracts_contract_date ON contracts (contract_date);

ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS contract_id    character varying(50);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS unit_price_ht  numeric(14,2);

CREATE INDEX IF NOT EXISTS idx_vehicles_contract_id ON vehicles (contract_id);
