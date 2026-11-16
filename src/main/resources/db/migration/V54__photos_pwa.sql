-- =============================================================================
-- Les photos de la PWA, gardées hors ligne puis versées au retour du réseau.
--
-- L'identifiant est fourni par le téléphone (UUID) : la clé primaire fait
-- l'anti-doublon, un renvoi ne crée pas une seconde ligne. L'image est en base
-- (`bytea`) pour que le serveur déployé n'ait aucun disque à monter.
-- =============================================================================

CREATE TABLE images (
    id                varchar(50)  PRIMARY KEY,
    file_name         varchar(255) NOT NULL,
    content_type      varchar(100) NOT NULL,
    size_bytes        bigint       NOT NULL,
    client_created_at timestamp without time zone,
    uploaded_at       timestamp without time zone NOT NULL,
    uploaded_by       varchar(255),
    data              bytea        NOT NULL
);

CREATE INDEX idx_images_uploaded_at ON images (uploaded_at DESC);
