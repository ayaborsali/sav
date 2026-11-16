-- =============================================================================
-- V8__intervention_primes.sql — D3 : la prime d'intervention
--
-- Le but du module est là : personne ne touche une prime pour une sortie qui
-- n'a pas eu lieu. La prime est donc calculée par le serveur à partir des
-- heures qu'un humain n'a pas écrites (les horodatages d'étape), puis validée
-- par le chef d'atelier, qui doit justifier toute anomalie.
--
-- Une ligne = une prime VALIDÉE. Le brouillon n'est pas stocké : il se recalcule
-- à la demande, à partir des mêmes heures. Garder un brouillon en base revient à
-- laisser courir un montant qui n'a jamais été décidé, et à le retrouver le mois
-- suivant comme s'il engageait quelqu'un.
--
-- Pourquoi les montants sont recopiés ici (hourly_rate, dispatch_allowance) :
--   le taux horaire du technicien change un jour, et la paie d'une sortie de
--   janvier ne doit pas se recalculer en mars. Une ligne dit exactement ce qui a
--   été payé, sur la base de quels taux, à quelle date.
--
-- anomalies / justification : la liste des signaux trouvés au calcul (photos
-- manquantes, durée plafonnée, heure rectifiée, sortie non clôturée). S'il y en
-- a, la validation exige une justification écrite — la règle annoncée à
-- l'avance : l'application signale, un humain décide.
--
-- intervention_id est unique : valider deux fois la même sortie serait un
-- double paiement, et la base doit le refuser même si un écran laisse passer le
-- clic.
-- =============================================================================

CREATE TABLE IF NOT EXISTS intervention_primes (
    id                  character varying(50)  NOT NULL,
    intervention_id     character varying(50)  NOT NULL,
    -- Durée retenue pour le calcul, en minutes brutes (durée dehors, départ -> retour).
    paid_minutes        bigint                 NOT NULL,
    -- Minutes arrondies au quart d'heure, exprimées en heures.
    billable_hours      numeric(6,2)           NOT NULL,
    -- Taux et forfait recopiés à la validation : ils ne doivent plus bouger.
    hourly_rate         numeric(10,3),
    dispatch_allowance  numeric(10,3),
    amount              numeric(12,3),
    -- Signaux trouvés au calcul, séparés par « ; ». Vide = rien à justifier.
    anomalies           character varying(500),
    justification       character varying(500),
    validated_by_email  character varying(255),
    validated_at        timestamp without time zone,
    CONSTRAINT intervention_primes_pkey PRIMARY KEY (id),
    CONSTRAINT uk_intervention_primes_sortie UNIQUE (intervention_id)
);

CREATE INDEX IF NOT EXISTS idx_intervention_primes_validated ON intervention_primes (validated_at);
