-- =============================================================================
-- V33__ce_qu_on_efface_ne_laisse_rien_de_casse.sql -- le menage d'apres la garde
--
-- La garde est cote service desormais : DELETE /api/sav-cases/{id} refuse tant
-- qu'une sortie ou une demande repond encore au dossier. Cette migration ne fait
-- pas la garde, elle repare ce qu'elle aurait empeche.
--
-- Le portail n'a jamais eu de cle etrangere vers sav_cases : le lien sortue ->
-- dossier est tenu par le service, en character varying(50). Donc supprimer un
-- dossier ne detachait rien : la ligne restait, l'identifiant avec elle, et la
-- page du dossier parti. Ce menage a ete constate en relisant la base du guichet
-- le 28/10 : douze sorties et neuf missions portaient un sav_case_id qui ne
-- designait plus personne.
--
-- C'est un defaut silencieux, et c'est pour ca qu'il compte. Rien ne casse : le
-- numero rendu a l'ecran passe a un tiret, le nom du client disparait de la fiche,
-- la liste des sorties affiche une colonne vide. Depuis V32, c'est plus grave
-- qu'un probleme d'affichage : la reception d'une piece lit sav_case_id de la
-- sortie pour ouvrir la mission « changer la piece » dessus. Un lien casse ne
-- bloque pas la reception, il la fait passer sans ouvrir la mission, et le journal
-- doit l'ecrire (InterventionPartService.ouvrirLaMissionDePose) parce que le
-- silence, ici, veut dire « le bus attend sans que personne le sache ».
--
-- On met NULL, on ne supprime pas la ligne. Une sortie est une preuve de travail :
-- elle porte des heures, un homme, une prime attachee. Une mission est la decision
-- de quelqu'un, datee et signee. Effacer le dossier ne efface pas retroactivement
-- ce qui a ete fait devant le bus. NULL dit la verite : cette sortie a eu lieu, son
-- dossier n'existe plus.
--
-- Les cinq colonnes visees sont celles qui pointent sav_cases. Les autres tables
-- gardees par un dossier (item_parts : le bloc « commande de pieces » tape sur la
-- fiche) sont incluses au cas ou, elles sont vides aujourd'hui mais la requete ne
-- coute rien et elle serait fausse demain si on l'oubliait.
--
-- Il n'y a pas de contre-partie : aucun chemin de lecture ne suppose un
-- sav_case_id non NULL. Les rendus passent par une lookup nulle-et-sure depuis la
-- correction du 26/10 (Map.of().get(null)), et l'ecran Missions sait deja rendre
-- une demande nee d'un rappel, sans dossier.
-- =============================================================================

UPDATE interventions
   SET sav_case_id = NULL
 WHERE sav_case_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sav_cases s WHERE s.id = interventions.sav_case_id);

UPDATE missions
   SET sav_case_id = NULL
 WHERE sav_case_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sav_cases s WHERE s.id = missions.sav_case_id);

UPDATE intervention_part_requests
   SET sav_case_id = NULL
 WHERE sav_case_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sav_cases s WHERE s.id = intervention_part_requests.sav_case_id);

UPDATE item_parts
   SET sav_case_id = NULL
 WHERE sav_case_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sav_cases s WHERE s.id = item_parts.sav_case_id);

COMMENT ON COLUMN interventions.sav_case_id IS
    'Le dossier SAV qui a declare cette sortie. NULL quand le dossier a ete efface depuis : la sortie reste au registre.';
COMMENT ON COLUMN missions.sav_case_id IS
    'La reclamation a laquelle cette mission repond. NULL quand le dossier a ete efface depuis ; une mission nee d''un rappel n''en a jamais eu.';
