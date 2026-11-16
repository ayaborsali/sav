-- =============================================================================
-- V31__piece_demandee_sur_la_sortie.sql — la piece que l'homme dehors reclame
--
-- Ce qui manquait reellement dans le flux, verifie en le jouant dans l'app le
-- 28/10 : le technicien arrive sur place, voit la piece morte, et le portail n'a
-- aucun objet pour le dire. Il ecrivait « piece en cause » dans une case de texte
-- de la sortie (interventions.causing_part, V6) et rien d'autre. Resultat :
-- personne au guichet ne pouvait commander, parce qu'une phrase ne porte ni une
-- quantite ni un motif, et la panne restait « identifiee » sans lendemain.
--
-- Une piece DEMANDEE n'est pas une piece POSEE. item_parts (V21) range des pieces
-- montees pendant un papier d'entretien : elles ont une quantite posee, un bus, un
-- sort chez le constructeur. Ici c'est l'autre bout de la chaine : un homme qui
-- dit « ce bus ne repartira pas sans ca ». La table est donc separee, et rien ne
-- se recopie de l'une a l'autre.
--
-- Le bloc vit sur la SORTIE, pas sur le dossier : c'est dehors, devant le bus, que
-- la piece se voit. Quatre pointeurs sont ecrits par le SERVEUR au moment de la
-- demande, jamais retapes depuis un ecran :
--   - intervention_id : la sortie ou la piece est nee (c'est le lien principal) ;
--   - mission_id : la demande d'atelier qui a fait partir cette voiture, relue sur
--     la table des missions, qui est seule a tenir ce lien (V28/V29) ;
--   - sav_case_id et vehicle_id : la reclamation et le bus, repris de la sortie.
-- Sans eux, la commande ne serait rattachable ni au dossier ni au bus, et c'est
-- exactement ce que la feuille de scenario reclamait : « la commande doit rester
-- liee a la sortie ET a la mission d'origine, pas seulement a un commentaire ».
--
-- Deux quantites, deux dates, deux personnes, comme V26 l'a fait pour le papier :
--   quantity_ordered + state                     -> ce que l'homme reclame, et ou
--                                                   on en est
--   ordered_at + ordered_by                      -> quand le guichet passe la commande
--   quantity_received + received_at + received_by -> ce que le magasin a recu
--   fitted_at + fitted_intervention_id           -> le second passage qui la monte
-- Toutes ces cases naissent VIDES : la migration porte la structure du dossier,
-- pas son avancement. Les ecritures se font par les gestes de l'ecran, jamais par
-- un PUT qui remplit tout.
--
-- `state` est la seule colonne contrainte en base, et c'est la lecon de V30 : une
-- contrainte CHECK plus ancienne que Flyway ignorait deux des huit statuts du
-- dossier, et « j'arrive » partait en 500 chez le technicien pendant que 546 tests
-- restaient verts. La liste ci-dessous est donc celle de PartRequestState, ni plus
-- ni moins, et ContraintesDeStatutsEnBaseTest la relit a chaque verification.
--
-- La piece n'a pas de prix ici : Saphir n'a pas le bareme de remboursement Higer,
-- et une case d'argent que personne ne peut justifier finit par recevoir un
-- chiffre invente. Les montants restent sur les papiers (devis, AG).
-- =============================================================================

CREATE TABLE IF NOT EXISTS intervention_part_requests (
    id                       character varying(50)  NOT NULL,
    -- La sortie dehors ou la piece a ete vue : c'est le lien qui porte tout.
    intervention_id          character varying(50)  NOT NULL,
    -- La demande d'atelier qui a fait partir cette voiture, relue par le serveur.
    mission_id               character varying(50),
    -- La reclamation du client, reprise de la sortie.
    sav_case_id              character varying(50),
    -- Le bus qui attend la piece, repris de la sortie.
    vehicle_id               character varying(50)  NOT NULL,
    -- Le code constructeur. Il vient de Higer ou la case reste vide : rien d'invente.
    reference                character varying(120),
    designation              text,
    -- Pourquoi cette piece est reclamee, dans les mots de l'homme sur place.
    reason                   text,
    quantity_ordered         integer                NOT NULL,
    state                    character varying(20)  NOT NULL DEFAULT 'A_COMMANDER',
    ordered_at               timestamp without time zone,
    ordered_by               character varying(120),
    quantity_received        integer,
    received_at              timestamp without time zone,
    received_by              character varying(120),
    fitted_at                timestamp without time zone,
    -- La seconde sortie qui a monte la piece, quand elle a lieu.
    fitted_intervention_id   character varying(50),
    -- Qui a demande : l'homme dehors, ou le chef qui saisit pour lui.
    demanded_by              character varying(120),
    created_at               timestamp without time zone NOT NULL,
    updated_at               timestamp without time zone,
    CONSTRAINT intervention_part_requests_pkey PRIMARY KEY (id),
    CONSTRAINT intervention_part_requests_state_check
        CHECK (state IN ('A_COMMANDER', 'COMMANDEE', 'PARTIELLE', 'LIVREE', 'POSEE')),
    -- Une quantite demandee a zero ne se commande pas : la demande n'aurait pas d'objet.
    CONSTRAINT intervention_part_requests_quantity_check CHECK (quantity_ordered > 0)
);

CREATE INDEX IF NOT EXISTS idx_ipr_intervention ON intervention_part_requests (intervention_id);
CREATE INDEX IF NOT EXISTS idx_ipr_mission ON intervention_part_requests (mission_id);
CREATE INDEX IF NOT EXISTS idx_ipr_sav_case ON intervention_part_requests (sav_case_id);
CREATE INDEX IF NOT EXISTS idx_ipr_vehicle ON intervention_part_requests (vehicle_id);
CREATE INDEX IF NOT EXISTS idx_ipr_state ON intervention_part_requests (state);
