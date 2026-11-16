-- =============================================================================
-- V24__criticite_et_classification_texte.sql — élargir les deux cases libres de
-- l'onglet Qualité
--
-- V23 a posé `severity` en varchar(20) et `classification` en varchar(120), en
-- partant du papier de référence : Mineure | Majeure | Critique. Mais le
-- constructeur n'a jamais fourni sa grille, et le portail ne ferme donc pas la
-- case à trois mots — l'écran les propose, l'atelier écrit ce qu'il voit.
--
-- « Critique — bus immobilisé, client SNTRI » fait 37 caractères : avec la
-- colonne étroite, PostgreSQL refuse l'écriture et l'écran rend une erreur
-- technique au poste qui vient de décrire une panne grave. Une case de texte
-- libre ne se dimensionne pas au doigt : les deux passent en text.
--
-- V23 est jouée sur la base de travail depuis le 26/10 : ce fichier ne la
-- corrige pas, il la suit. C'est la règle des migrations — une colonne déjà
-- écrite ailleurs ne se réécrit jamais, sinon le serveur refuse de redémarrer
-- sur un contrôle de somme.
-- =============================================================================

alter table sav_cases alter column severity type text;
alter table sav_cases alter column classification type text;
