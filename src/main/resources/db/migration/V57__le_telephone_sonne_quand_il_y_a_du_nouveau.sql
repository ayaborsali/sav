-- =============================================================================
-- V57__le_telephone_sonne_quand_il_y_a_du_nouveau.sql — la notification poussée
--
-- La cloche décrit ce qui attend dans l'application ; elle ne réveille personne.
-- Cette version donne au serveur de quoi frapper à la porte du téléphone : un
-- abonnement par appareil, une paire de clés VAPID, et le journal de ce qui a
-- déjà sonné pour ne pas sonner deux fois.
--
--  * push_subscriptions : un navigateur qui a accepté les notifications laisse
--    un point de livraison (endpoint) et deux clés publiques. Le point de
--    livraison est unique : si un autre compte se connecte sur le même
--    téléphone, l'abonnement change de propriétaire au lieu de s'ajouter.
--  * push_vapid_keys : la paire qui signe les envois. Elle naît au premier
--    démarrage et reste en base — un redéploiement ne la change pas, sinon
--    tous les abonnements existants cesseraient de fonctionner.
--  * push_sent : la trace d'un envoi déjà fait. La clé (nature, dossier,
--    référence) empêche le rappel de départ de sonner toutes les dix minutes.
--
-- Aucune clé privée ne quitte la base : le dépôt ne contient que le schéma.
-- =============================================================================

CREATE TABLE push_subscriptions (
    id           character varying(50)  NOT NULL PRIMARY KEY,
    user_id      character varying(64)  NOT NULL,
    endpoint     text                   NOT NULL,
    p256dh       character varying(255) NOT NULL,
    auth         character varying(255) NOT NULL,
    user_agent   character varying(255),
    created_at   timestamp without time zone NOT NULL,
    last_seen_at timestamp without time zone
);

-- Un téléphone = un abonnement. Le réenregistrer ne crée pas de doublon et
-- permet de le réattribuer au compte qui s'y connecte.
CREATE UNIQUE INDEX ux_push_subscriptions_endpoint ON push_subscriptions (endpoint);
CREATE INDEX ix_push_subscriptions_user ON push_subscriptions (user_id);

CREATE TABLE push_vapid_keys (
    id          character varying(20) NOT NULL PRIMARY KEY,
    public_key  text                  NOT NULL,
    private_key text                  NOT NULL,
    created_at  timestamp without time zone NOT NULL
);

CREATE TABLE push_sent (
    id         character varying(50) NOT NULL PRIMARY KEY,
    kind       character varying(40) NOT NULL,
    target_id  character varying(64) NOT NULL,
    reference  character varying(64) NOT NULL,
    sent_at    timestamp without time zone NOT NULL
);

CREATE UNIQUE INDEX ux_push_sent_reference ON push_sent (kind, target_id, reference);
