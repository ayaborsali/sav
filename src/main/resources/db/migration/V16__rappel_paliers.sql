-- =============================================================================
-- V16__rappel_paliers.sql — module Forfaits : le compteur du bus rend un palier dû
--
-- Un forfait vendu porte une ligne de suivi par palier, et chaque palier du
-- catalogue porte une borne en kilomètres et/ou en jours. Jusqu'ici, ces bornes
-- étaient écrites mais jamais lues : l'atelier devait ouvrir la fiche du forfait
-- et comparer l'odomètre à la main. Cette table garde la trace de la détection
-- faite par le serveur.
--
-- La règle, annoncée dans application.properties et récité à l'écran avec chaque
-- liste : un palier TODO d'un forfait ACTIF est dû quand le compteur connu du bus
-- atteint son kilométrage, OU quand ses jours sont écoulés depuis le début du
-- forfait — le premier des deux atteint décide. Un bus qui dort à l'atelier a donc
-- quand même sa visite des 30 jours.
--
-- Le compteur connu est le plus élevé des chiffres que le portail possède pour ce
-- bus : la fiche du bus (saisie à la main) et les compteurs relevés par le
-- technicien en sortie d'intervention. Un compteur ne redescend pas, donc la
-- valeur la plus haute est la plus récente ; c'est aussi pour ça qu'aucun champ
-- « kilométrage du forfait » n'a été ajouté : il aurait fallu le ressaisir.
--
-- Deux fins, comme pour les alertes de dépannage, volontairement séparées :
--   - résolue  : la situation s'est réglée seule — le palier a été fait par un
--                document livré, le forfait a été clôturé ou annulé, ou un
--                compteur corrigé à la baisse rend la borne de nouveau non atteinte.
--                Personne n'a eu à décider ;
--   - levée    : un compte qui écrit dans les forfaits (admin, gestionnaire SAV)
--                consigne par écrit pourquoi il considère le palier comme non dû
--                quand même (le bus est vendu, la visite a été faite hors portail).
--                Sa raison, son adresse et l'heure restent sur la ligne.
-- Une alerte levée sans écrit n'existe pas : la raison est exigée par le service.
--
-- detected_at : l'heure du premier déclenchement. Elle ne bouge plus, pour qu'un
-- rappel qui traîne depuis trois semaines ne paraisse pas frais.
--
-- Une seule alerte ouverte par palier suivi : le scan tourne périodiquement et
-- doit reposer la même question, pas empiler trente lignes identiques. L'index
-- partiel ne contraint que les alertes encore ouvertes, pour qu'un palier puisse
-- être signalé de nouveau après une levée ou une remise à faire.
--
-- Le suivi d'un palier qui a déjà rendu son alerte garde sa ligne : pas de clé
-- étrangère, comme partout dans ce module, pour que la correction d'un forfait ou
-- d'un palier ne se heurte pas à l'historique des signaux.
-- =============================================================================

CREATE TABLE IF NOT EXISTS sold_plan_alerts (
    id                character varying(50)  NOT NULL,
    sold_plan_id      character varying(50)  NOT NULL,
    sold_plan_step_id character varying(50)  NOT NULL,
    -- KM_ATTEINT / DELAI_ECHU
    kind              character varying(40)  NOT NULL,
    -- La règle telle qu'elle a été évaluée, en français, avec ses chiffres : ce que
    -- l'écran affiche est ce qui a été calculé, pas une traduction faite après coup.
    message           character varying(500) NOT NULL,
    detected_at       timestamp without time zone NOT NULL,
    resolved          boolean                NOT NULL DEFAULT false,
    resolved_at       timestamp without time zone,
    -- false = résolue parce que la situation s'est réglée ;
    -- true  = levée par une décision écrite.
    waived            boolean                NOT NULL DEFAULT false,
    waiver_reason     character varying(500),
    waived_by_email   character varying(255),
    waived_at         timestamp without time zone,
    CONSTRAINT sold_plan_alerts_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_sold_plan_alerts_plan ON sold_plan_alerts (sold_plan_id);
CREATE INDEX IF NOT EXISTS idx_sold_plan_alerts_step ON sold_plan_alerts (sold_plan_step_id);
CREATE INDEX IF NOT EXISTS idx_sold_plan_alerts_open ON sold_plan_alerts (resolved, detected_at);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sold_plan_alerts_open
    ON sold_plan_alerts (sold_plan_step_id)
    WHERE resolved = false;
