-- =============================================================================
-- V32__la_piece_livree_ouvre_sa_mission_de_pose.sql -- le lendemain d'une piece
--
-- P1 et P2 ont pose la demande, la commande et la reception. Il restait le trou
-- le plus visible du scenario joue le 28/10 : la piece arrive au magasin, et le
-- portail ne dit a personne qu'il faut maintenant la monter. Le bus attend dehors,
-- la ligne est « Livree », et rien ne bouge tant que le chef ne pense pas a ouvrir
-- une demande. Ce lot ferme ce trou : la livraison d'une piece ouvre elle-meme la
-- mission qui va la poser.
--
-- Une colonne de lien, pas une table de plus. La mission de pose est une mission
-- comme les autres (V28) : elle repond a la meme reclamation, elle se decide a
-- l'ecran Missions, elle fait sortir un homme ou entrer le bus. Ce qu'il manquait
-- etait le sens de la fleche : la ligne de piece doit savoir quelle mission est
-- nee d'elle, pour deux raisons.
--   1. au second passage, la sortie qui se cloture doit retrouver les lignes a
--      marquer « Posee ». Sans ce lien, le serveur devrait deviner quelle piece
--      cette sortie est venue monter, et un etat dedit « calcule par le serveur »
--      n'est plus calcule : il est suppose ;
--   2. la mission de pose ne s'ouvre qu'une fois. Le geste de reception peut etre
--      rejoue (double clic, ecran qui renvoie la requete) ; le lien ecrit sur la
--      ligne est ce qui prouve qu'elle est deja ouverte.
--
-- fitted_by, ecrite avec les deux cases nees en V31 : « Posee » se lit avec un nom
-- et une date comme « Commandee » et « Livree ». Une date sans nom ne se discute
-- pas en litige, et la prime du second passage se joue sur ce nom.
--
-- Aucun CHECK nouveau : PartRequestState compte cinq etats et V31 les connait tous
-- les cinq. La lecon de V30 (une CHECK plus ancienne que Flyway qui ignorait deux
-- statuts du dossier) n'a pas a se repeter ici, et ContraintesDeStatutsEnBaseTest
-- relit la liste de toute facon a chaque verification.
--
-- La colonne est laissee sans cle etrangere, comme les quatre pointeurs de V31 :
-- le portail relit ces liens par le service, un bus ou une mission supprime se voit
-- a l'ecran, et une cle ici ferait une migration par suppression de referentiel.
-- =============================================================================

ALTER TABLE intervention_part_requests
    ADD COLUMN IF NOT EXISTS fitting_mission_id character varying(50);

ALTER TABLE intervention_part_requests
    ADD COLUMN IF NOT EXISTS fitted_by character varying(120);

COMMENT ON COLUMN intervention_part_requests.fitting_mission_id IS
    'La mission « changer la piece » ouverte par le serveur quand la reception a rendu la ligne Livree.';
COMMENT ON COLUMN intervention_part_requests.fitted_by IS
    'Le compte qui a cloture la sortie de pose : le nom qui ecrit « Posee ».';

CREATE INDEX IF NOT EXISTS idx_ipr_fitting_mission ON intervention_part_requests (fitting_mission_id);
