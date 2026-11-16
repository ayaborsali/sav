-- =============================================================================
-- V5__contrat_document.sql — le contrat devient un document imprimable
--
-- Six zones de rédaction sur « contracts ». Elles sont TOUS nullables : un
-- contrat enregistré avant cette version s'imprime déjà, le serveur applique
-- alors la rédaction standard (ContractClauses) clause par clause. Saisir une
-- zone remplace donc le texte standard, jamais elle ne le duplique.
--
-- Une IA (Groq qwen3-32b) peut proposer le texte d'une zone via
-- POST /api/contracts/{id}/draft, mais l'IA écrit dans ces colonnes uniquement
-- après validation humaine. Les montants ne sont jamais stockés ici : ils
-- restent recalculés à la lecture à partir de vehicles.unit_price_ht.
--
-- Deux colonnes d'identification juridique sur « clients » (registre de
-- commerce, matricule fiscal) : sans elles, la partie « acheteur » du contrat
-- n'est pas opposable.
-- =============================================================================

ALTER TABLE contracts ADD COLUMN IF NOT EXISTS object_clause    character varying(1500);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS delivery_clause  character varying(1000);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS warranty_clause  character varying(1000);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS ownership_clause character varying(1000);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS dispute_clause   character varying(1000);
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS special_terms    character varying(2000);

ALTER TABLE clients ADD COLUMN IF NOT EXISTS trade_register character varying(60);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS tax_id         character varying(40);
