-- =============================================================================
-- V25__date_de_cloture_du_dossier.sql — la date où le travail est fini
--
-- Le papier de référence pose « Date de clôture » dans trois de ses onglets
-- (qualité, réclamation, garantie). Le portail ne l'avait pas : il ne gardait
-- que `updated_at`, qui change à la moindre correction de texte. Un dossier
-- fermé en juin et corrigé de deux mots en octobre se relisait donc « fermé en
-- octobre » — et c'est ce chiffre que le constructeur regarderait.
--
-- Une seule colonne pour les familles : une clôture, c'est la fin du travail,
-- qu'on appelle ça anomalie, réclamation ou claim.
--
-- Elle ne se saisit pas à l'écran. C'est le serveur qui l'écrit au moment où le
-- statut passe à « clôturé », et il la retire si le dossier repart dehors (une
-- sortie rattachée rouvre le travail) : une date de fermeture sur un dossier
-- ouvert est un mensonge daté.
-- =============================================================================

alter table sav_cases add column if not exists closed_at date;
