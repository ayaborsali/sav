-- =============================================================================
-- V9__intervention_alertes.sql — D4 : le suivi dans le temps
--
-- Les trois niveaux du contrôle sont maintenant en base :
--   niveau 1  interventions            : la sortie, étape par étape ;
--   niveau 2  intervention_primes      : ce que le calcul de la prime a signalé ;
--   niveau 3  intervention_alerts      : ce que LE TEMPS a signalé (cette table).
--
-- Une alerte n'est pas une faute : c'est une règle annoncée à l'avance qui s'est
-- déclenchée parce qu'une sortie attend depuis trop longtemps, qu'un cliché
-- manque, ou qu'un bus n'est pas rentré. L'application signale, un humain décide —
-- et sa décision s'écrit.
--
-- resolved / waived : deux fins différentes, et c'est pour cela qu'elles sont
-- séparées.
--   - résolue  : la situation s'est réglée seule (le cliché est arrivé, le bus
--                est rentré, la prime a été validée). Personne n'a eu à décider ;
--   - levée    : un chef d'atelier ou un admin écrit pourquoi il considère la
--                sortie correcte quand même. Sa raison reste attachée à la ligne.
-- Une alerte levée sans écrit n'existe pas : la raison est exigée par le service
-- qui lève l'alerte, et l'horodatage de la décision est conservé ici.
--
-- detected_at : l'heure du serveur au premier déclenchement. Elle ne bouge plus,
-- sinon une alerte qui traîne depuis trois semaines paraîtrait fraîche.
--
-- Une seule alerte ouverte par (sortie, règle) : le scan tourne toutes les
-- quelques minutes et doit reposer la même question, pas empiler trente lignes
-- identiques. L'index partiel ne contraint que les alertes encore ouvertes, pour
-- qu'une même règle puisse se redéclencher plus tard sur la même sortie.
--
-- kind et event_type restent des varchar : ajouter une règle ne demandera pas de
-- migration, comme pour les types d'événement de la table des journalisations.
-- =============================================================================

CREATE TABLE IF NOT EXISTS intervention_alerts (
    id               character varying(50)  NOT NULL,
    intervention_id  character varying(50)  NOT NULL,
    -- MISSING_BEFORE_PHOTO / MISSING_AFTER_PHOTO / STILL_OUT / NOT_RETURNED / NOT_DISPATCHED
    kind             character varying(40)  NOT NULL,
    -- La règle, écrite en français au moment du déclenchement : ce que l'écran
    -- affiche est ce qui a été évalué, pas une traduction faite après coup.
    message          character varying(500) NOT NULL,
    detected_at      timestamp without time zone NOT NULL,
    resolved         boolean                NOT NULL DEFAULT false,
    resolved_at      timestamp without time zone,
    -- false = résolue parce que la situation s'est arregée ;
    -- true  = levée par une décision écrite.
    waived           boolean                NOT NULL DEFAULT false,
    waiver_reason    character varying(500),
    waived_by_email  character varying(255),
    waived_at        timestamp without time zone,
    CONSTRAINT intervention_alerts_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_intervention_alerts_intervention ON intervention_alerts (intervention_id);
CREATE INDEX IF NOT EXISTS idx_intervention_alerts_open ON intervention_alerts (resolved, detected_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_intervention_alerts_open
    ON intervention_alerts (intervention_id, kind)
    WHERE resolved = false;
