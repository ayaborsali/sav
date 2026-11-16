-- =============================================================================
-- V7__intervention_photos.sql — D2 : la preuve photo de la sortie
--
-- Pourquoi cette table existe : une sortie racontée par le seul technicien
-- n'est pas contrôlable. Trois clichés répondent à trois questions différentes :
--   BEFORE    : le bus était bien dans cet état quand l'équipe est arrivée ;
--   AFTER     : ce qui a été fait sur place ;
--   MILEAGE   : le compteur, seule garantie du kilométrage déclaré au départ
--               et au retour (donc de la distance, donc du temps de route).
--
-- Ce qui est stocké où :
--   - l'image elle-même est sur le disque (app.depannage.photos-dir), jamais en
--     base : un JPEG de 4 Mo dans une colonne alourdirait chaque lecture de liste
--     et chaque sauvegarde ;
--   - la base garde le chemin, l'empreinte SHA-256, l'heure EXIF et l'heure du
--     serveur. Le SHA-256 sert à repérer le même cliché versé deux fois (le
--     dossier photo du téléphone revu le lendemain).
--
-- taken_at vient du fichier (EXIF « DateTimeOriginal », lu par le script Python) :
-- c'est l'heure de l'appareil photo, pas celle du poste. Elle est nulle quand le
-- fichier n'a pas d'EXIF — auquel seul uploaded_at parle, et l'écran le dit.
-- uploaded_at est horodaté par le serveur au moment du versement : un humain ne
-- peut pas l'écrire.
--
-- Après clôture, la fiche devient immuable (seul ADMIN peut encore verser, et
-- l'audit le garde) : effacer une preuve après coup effacerait le contrôle.
-- =============================================================================

CREATE TABLE IF NOT EXISTS intervention_photos (
    id               character varying(50)  NOT NULL,
    intervention_id  character varying(50)  NOT NULL,
    -- BEFORE / AFTER / MILEAGE / OTHER
    angle            character varying(20)  NOT NULL,
    -- Chemin relatif au dossier de stockage configuré côté serveur.
    file_path        character varying(400) NOT NULL,
    original_name    character varying(255),
    content_type     character varying(60),
    size_bytes       bigint,
    -- Empreinte du contenu : deux versers du même cliché se voient.
    sha256           character varying(64)  NOT NULL,
    -- Heure de l'appareil photo (EXIF), nulle si le fichier n'en porte pas.
    taken_at         timestamp without time zone,
    taken_at_source  character varying(20),
    -- Heure du serveur au versement : la seule certitude.
    uploaded_at      timestamp without time zone NOT NULL,
    actor_email      character varying(255),
    note             character varying(500),
    CONSTRAINT intervention_photos_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_intervention_photos_intervention ON intervention_photos (intervention_id);
CREATE INDEX IF NOT EXISTS idx_intervention_photos_sha ON intervention_photos (sha256);
