-- Le statut du dossier a gagne deux etats le 22/10, quand le dossier s'est mis a
-- suivre la sortie qui le traite : ON_SITE (« l'homme est sur place ») et RETURNED
-- (« il est rentre a l'atelier »). Ces deux mots n'ont jamais ete ecrits dans la
-- contrainte CHECK de la table, parce que cette contrainte est plus ancienne que
-- Flyway : elle date de la toute premiere base, ou la liste des statuts tenait dans
-- six valeurs.
--
-- Ce que ca coutait en reel : des qu'une sortie partie d'un dossier arrivait sur
-- place, le serveur ecrivait ON_SITE dans sav_cases, PostgreSQL refusait la ligne
-- (SQLState 23514), et la transaction entiere partait en 500. Le technicien voyait
-- « le serveur a refuse la demande (code 500) » et son arrivee n'etait pas enregistree.
-- Aucun test Java ne l'a vu : ils bouchonnent les depots, donc aucun n'envoie
-- vraiment d'UPDATE a la base.
--
-- Regle posee ici : la liste de la base est celle de l'enum Java, ni plus ni moins.
-- Un test relit cette contrainte et la compare a SavCaseStatus a chaque demarrage
-- verifie (ContraintesDeStatutsEnBaseTest), pour qu'un statut ajoute du cote Java
-- sans passer par la base ne puisse plus se cacher jusqu'a l'ecran d'un technicien.
ALTER TABLE sav_cases DROP CONSTRAINT IF EXISTS sav_cases_status_check;

ALTER TABLE sav_cases
    ADD CONSTRAINT sav_cases_status_check CHECK (status IN (
        'OPEN',
        'IN_PROGRESS',
        'ON_SITE',
        'PENDING_PARTS',
        'APPROVED',
        'REJECTED',
        'RETURNED',
        'CLOSED'
    ));
