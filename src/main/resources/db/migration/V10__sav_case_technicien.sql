-- =============================================================================
-- V10__sav_case_technicien.sql — le technicien d'un dossier est un compte du portail
--
-- Le dossier SAV portait jusqu'ici une chaîne libre : « technician character
-- varying(255) », remplie à la main, pré-tapée même avec un nom inventé. Cette
-- colonne ne peut pas répondre à la question qui compte : « qui est sorti ce
-- jour-là, et est-ce la même personne que celle de la sortie de dépannage ? »
--
-- La personne est désormais cherchée dans `users` (les comptes ayant le rôle
-- technicien), et c'est elle qui porte le lien :
--   sav_cases.technician_user_id -> users.id
--
-- `technicians` (la fiche) garde son rôle : c'est le dossier de paie, taux
-- horaire et forfait de sortie. Le lien entre les deux existe déjà (la fiche
-- porte user_id). Un compte peut donc être choisi sur un dossier avant même
-- d'avoir sa fiche : la fiche est créée à ce moment-là, sans taux, et la prime
-- se signale non calculable tant que le taux manque.
--
-- `technician` (la chaîne) n'est pas supprimée : elle continue d'afficher un
-- nom, y compris pour les dossiers d'avant, où aucune identité fiable
-- n'existait. Un dossier dont technician_user_id est null alors que technician
-- est rempli est un héritage : l'écran le dit « à rattacher ».
--
-- Comme partout dans ce schéma, aucune clé étrangère n'est déclarée : les
-- références sont des colonnes simples, contrôlées par le serveur. L'index,
-- lui, sert vraiment — les recherches « les dossiers de ce technicien »
-- partent de cette colonne.
-- =============================================================================

ALTER TABLE sav_cases ADD COLUMN IF NOT EXISTS technician_user_id uuid;

CREATE INDEX IF NOT EXISTS idx_sav_cases_technician_user ON sav_cases (technician_user_id);
