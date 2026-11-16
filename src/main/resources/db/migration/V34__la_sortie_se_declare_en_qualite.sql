-- =============================================================================
-- V34__la_sortie_se_declare_en_qualite.sql -- dire qu'une sortie a eu son dossier
-- qualite, et lequel
--
-- La quatrieme ligne de la feuille « exple » du 28/10 annoncait : « un probleme
-- identifie ouvre un dossier qualite ». Elle n'existait pas. Le 29/10, la decision
-- est prise : elle n'existera pas toute seule non plus. Pas de moteur qui devine la
-- qualite dans un diagnostic -- un bouton sur la ligne de la sortie, preempli du
-- code defaut releve devant le bus, et c'est un humain qui enregistre.
--
-- Restait le lien. Une declaration part d'une sortie : son code defaut, sa piece en
-- cause, son compteur, son homme. Sans colonne, le dossier qualite etait une feuille
-- blanche ou l'atelier retape ce qu'il a deja ecrit -- et une case recopiee a la main
-- est une case qui peut dire autre chose que sa source (le portail l'a deja paye avec
-- les numeros d'appel en garantie inventes dans le navigateur).
--
-- L'autre possibility etait de re-ecrire interventions.sav_case_id. Ecartee : ce
-- pointeur-la dit « cette sortie est le depannage de ce dossier », et un dossier
-- qualite n'est pas le dossier d'un depannage -- il ne se rembourse pas chez le
-- constructeur, il se traite a l'atelier. Deux sens differents dans une meme colonne
-- aurait fait dire a l'ecran Missions qu'une panne de qualite etait le travail en
-- cours du bus.
--
-- Donc une colonne, dans le sens declaration -> sortie, nullable : la majeure partie
-- des dossiers (reclamation, visite, piece, garantie) ne naissent d'aucune sortie.
-- L'index sert a la lookup du refus « cette sortie est deja declaree », qui se fait
-- par ce champ.
--
-- character varying(50) comme tous les identifiants du portail, et pas de cle
-- etrangere : le portail n'en a aucune, les liens se tiennent dans les services. La
-- suppression d'une sortie met donc NULL, comme V33 le fait pour les dossiers partis
-- (InterventionService.delete).
-- =============================================================================

alter table sav_cases
    add column if not exists declared_from_intervention_id character varying(50);

create index if not exists idx_sav_cases_declared_from
    on sav_cases (declared_from_intervention_id);
