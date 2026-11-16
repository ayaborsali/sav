-- =============================================================================
-- V47__le_prix_de_vente_se_calcule_par_un_coefficient.sql — le second coefficient
--
-- L'atelier revient sur la règle de V42 et V43, et sa demande du 06/11 est nette :
-- le prix de vente se calcule comme le prix de revient — un coefficient, une
-- multiplication, un seul résultat. Le catalogue porte donc de nouveau DEUX
-- coefficients, chacun sa colonne, et le prix de vente redevient un calcul.
--
-- Ce que ça change, et ce que ça ne change pas :
--
--   1. La colonne est NULLABLE en base, mais OBLIGATOIRE à l'écriture. Les pièces
--      déjà au catalogue sont entrées sous la règle de V43, sans coefficient de
--      vente : un NOT NULL les rendrait illisibles avant qu'on ait de quoi les
--      remplir. Vide se lit donc « pièce entrée avant cette règle », jamais « pas
--      de coefficient voulu » — le service refuse une demande qui n'en porte pas,
--      et aucun taux n'est posé à la place de l'atelier.
--
--   2. Les prix de vente déjà saisis restent en place tant que la pièce n'est pas
--      réenregistrée. Aucune reprise de données : déduire un coefficient d'un prix
--      (prix ÷ revient) fabriquerait un taux que personne n'a décidé, et les six
--      prix effacés par V42 ne sont toujours pas ressuscitables. Au prochain
--      enregistrement, le prix devient revient × coefficient de vente — c'est la
--      décision du 06/11, et elle est dite à l'écran.
--
--   3. Le prix de vente reste STOCKÉ (prix_vente), comme le prix de revient : il
--      est le résultat figé du calcul au jour de l'enregistrement, pas une lecture
--      à la volée. Une ligne de devis qui cite la pièce garde sa propre copie
--      (V34, V46) : un catalogue remanié six mois après ne réécrit pas un devis
--      que le client a signé.
--
--   4. Le prix de vente reste une colonne du catalogue, calculée côté serveur :
--      l'écran ne l'envoie plus, et ne l'envoie jamais. Deux postes qui
--      multiplier avec deux arrondis différents écriraient deux prix sous la même
--      référence.
--
-- La contrainte « jamais 0 » de V43 est conservée : un coefficient nul ne donne
-- pas un prix à zéro dinar, il ne donne pas de prix du tout, et le CHECK sur le
-- coefficient le refuse avant la multiplication.
-- =============================================================================

ALTER TABLE piece_rechanges
    ADD COLUMN IF NOT EXISTS coefficient_vente numeric(10,4);

ALTER TABLE piece_rechanges
    DROP CONSTRAINT IF EXISTS piece_rechanges_coefficient_vente_check;

ALTER TABLE piece_rechanges
    ADD CONSTRAINT piece_rechanges_coefficient_vente_check
        CHECK (coefficient_vente IS NULL OR coefficient_vente > 0);

COMMENT ON COLUMN piece_rechanges.coefficient_vente IS
    'Coefficient de vente de la piece : prix de vente = prix de revient x coefficient de vente, calcule par le serveur. Obligatoire a l ecriture ; NULL = piece entree avant la regle de V47, son prix de vente reste celui de V43 jusqu au prochain enregistrement.';
