-- =============================================================================
-- V51__le_technicien_photographie_et_coche.sql — la preuve est la photo
--
-- Le technicien ne tape plus le compteur : il le photographie. Le départ, c'est
-- deux clichés — le compteur et la voiture — puis « Enregistrer ». Le retour,
-- les mêmes deux clichés. Le chiffre reste facultatif : s'il le lit et le tape,
-- le serveur calcule les kilomètres parcourus ; sinon le dossier montre la photo
-- et ne calcule rien. Il ne retape pas ce que la photo prouve déjà.
--
-- Sur place, le constat d'un bus porte ses propres clichés (avant / après), le
-- code d'erreur lu, la pièce en cause et les observations du technicien.
--
-- La fiche atelier est une liste écrite par l'atelier — pour un dossier de
-- déplacement comme pour une sortie — et cochée par l'homme qui la fait. Une
-- ligne non cochée ne bloque pas le retour : elle reste rouge dans le dossier,
-- et c'est l'atelier qui la voit.
-- =============================================================================

-- Les angles photo grandissent : le compteur et la voiture de chaque côté du
-- voyage, puis le bus avant et après le travail. La colonne est libre, elle ne
-- contraint rien ; c'est le serveur qui trie les angles utiles.
ALTER TABLE mission_group_photos
    ADD COLUMN IF NOT EXISTS bus_id character varying(50);

CREATE INDEX IF NOT EXISTS idx_mission_group_photos_bus
    ON mission_group_photos (bus_id);

-- Le constat d'un bus : ce que l'homme a lu et vu, en plus de son diagnostic.
ALTER TABLE mission_group_buses
    ADD COLUMN IF NOT EXISTS defect_code  character varying(80),
    ADD COLUMN IF NOT EXISTS causing_part character varying(255),
    ADD COLUMN IF NOT EXISTS observations text;

-- La fiche atelier d'un dossier de déplacement.
CREATE TABLE IF NOT EXISTS mission_group_checklist_items (
    id          character varying(50)  NOT NULL,
    dossier_id  character varying(50)  NOT NULL,
    line_order  integer                NOT NULL,
    label       character varying(300) NOT NULL,
    done        boolean                NOT NULL DEFAULT false,
    done_at     timestamp without time zone,
    done_by     character varying(255),
    CONSTRAINT mission_group_checklist_items_pkey PRIMARY KEY (id),
    CONSTRAINT mission_group_checklist_items_dossier_fkey
        FOREIGN KEY (dossier_id) REFERENCES mission_group_dossiers (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mission_group_checklist_dossier
    ON mission_group_checklist_items (dossier_id);

-- La même fiche, pour une sortie de dépannage.
CREATE TABLE IF NOT EXISTS intervention_checklist_items (
    id              character varying(50)  NOT NULL,
    intervention_id character varying(50)  NOT NULL,
    line_order      integer                NOT NULL,
    label           character varying(300) NOT NULL,
    done            boolean                NOT NULL DEFAULT false,
    done_at         timestamp without time zone,
    done_by         character varying(255),
    CONSTRAINT intervention_checklist_items_pkey PRIMARY KEY (id),
    CONSTRAINT intervention_checklist_items_intervention_fkey
        FOREIGN KEY (intervention_id) REFERENCES interventions (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_intervention_checklist_intervention
    ON intervention_checklist_items (intervention_id);
