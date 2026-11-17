-- =============================================================================
-- Une photo du terrain peut nommer le dossier de déplacement qu'elle documente.
--
-- La colonne reste facultative : une photo versée sans dossier (onglet Photos
-- libre, photo prise avant le choix) se conserve comme avant. Le lien est posé
-- en « SET NULL » pour qu'effacer un dossier n'efface jamais une preuve
-- photographique — la photo reste, elle perd seulement son rattachement.
-- =============================================================================

ALTER TABLE images
    ADD COLUMN dossier_id character varying(50);

ALTER TABLE images
    ADD CONSTRAINT images_dossier_fkey
        FOREIGN KEY (dossier_id) REFERENCES mission_group_dossiers (id)
        ON DELETE SET NULL;

CREATE INDEX idx_images_dossier ON images (dossier_id, uploaded_at DESC);
