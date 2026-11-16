-- =============================================================================
-- V28__missions.sql — module Missions (M1 : la décision qui répond à un dossier)
--
-- Une mission n'est pas une sortie et n'est pas un dossier. C'est le nœud où
-- l'atelier répond à ce que le client a demandé : « un homme y va » (DP-…) ou
-- « le bus passe à l'atelier » (OR-…). Le dessin du 26/10 le dit dans cet ordre :
--   Réclamation (qui, canal, confirmée le) → Mission (homme ou atelier) → DP- / OR-.
--
-- Rien n'est recopié ici. La demande du client — qui a parlé, par quel canal, à
-- quelle heure, ce qu'il attend — reste sur le dossier SAV (V20) : deux copies de
-- la même histoire finiraient par diverger, et c'est précisément ce que le module
-- doit empêcher. Cette table ne porte que la décision et les deux pointeurs vers
-- les papiers qu'elle a faits naître.
--
-- Aucune colonne de montant : la mission n'est pas une facture. Elle affiche des
-- numéros (DP-, OR-), les montants vivent sur ces papiers.
--
-- L'état « Soldée » n'est pas stocké : il se lit derrière le papier (une sortie
-- clôturée, un ordre terminé). Écrire un état ici voudrait dire le maintenir à
-- jour depuis deux autres services — la règle du portail est une seule écriture.
-- =============================================================================

CREATE TABLE IF NOT EXISTS missions (
    id                    character varying(50)   NOT NULL,
    -- MT-AAAA-NNN, donné par le serveur (NumberSequence), comme DP-, OR- et CT-.
    number                character varying(40)   NOT NULL,
    -- Le dossier qui porte la demande du client. Une mission sans dossier n'a
    -- personne à qui répondre.
    sav_case_id           character varying(50)   NOT NULL,
    -- HOMME (un technicien et une voiture de déplacement sortent) | ATELIER (le bus
    -- vient à l'atelier). Vide tant que personne n'a décidé.
    issue                 character varying(20),
    -- A_TRAITER / EN_COURS / REFUSEE / REPORTEE. « Soldée » se lit sur le papier.
    state                 character varying(20)   NOT NULL DEFAULT 'A_TRAITER',
    -- Les deux papiers que la décision a faits naître. Nulls tant que la décision
    -- n'est pas prise ; une seule des deux colonnes est remplie à la fois.
    intervention_id       character varying(50),
    maintenance_item_id   character varying(50),
    -- Motif écrit. Obligatoire pour REFUSEE et REPORTEE : « on ne fera rien » et
    -- « on fera plus tard » sans raison ne se relisent pas six mois après.
    reason                character varying(1000),
    note                  character varying(1000),
    decided_by            character varying(50),
    decided_at            timestamp without time zone,
    created_by            character varying(50),
    created_at            timestamp without time zone NOT NULL,
    updated_at            timestamp without time zone,
    CONSTRAINT missions_pkey PRIMARY KEY (id),
    CONSTRAINT missions_number_key UNIQUE (number)
);

CREATE INDEX IF NOT EXISTS idx_missions_state ON missions (state);
CREATE INDEX IF NOT EXISTS idx_missions_sav_case ON missions (sav_case_id);
CREATE INDEX IF NOT EXISTS idx_missions_created_at ON missions (created_at);
