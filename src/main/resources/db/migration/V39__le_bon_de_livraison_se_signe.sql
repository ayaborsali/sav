-- =============================================================================
-- V39__le_bon_de_livraison_se_signe.sql -- troisième lot des trois maquettes
-- (devis -> ordre de travail -> bon de livraison), côté bon de livraison.
--
-- Le bon de livraison était le dernier papier de la chaîne et il n'avait rien à lui :
-- pas d'état propre, pas de signataire, pas de compteur. Or c'est LE papier qui rend
-- le bus au client — le seul que le client signe à la restitution. Sans nom sur le
-- papier, un « le bus est reparti avec une réserve » se juge entre deux mémoires ;
-- sans compteur, le palier du forfait suivant part d'une valeur que plus personne ne
-- peut relire ; sans état « signé », le portail ne savait pas dire si le client avait
-- reçu son bus ou pas.
--
-- Un mot neuf dans `status`, six cases neuves :
--
--   SIGNED (« Signé »)      -- le client a reçu le bus et le papier, et l'a signé.
--     C'est le seul état du BL avec « brouillon » (DRAFT) : un bon de livraison ne
--     s'envoie pas, ne s'accepte pas et ne se refuse pas — il se signe ou il ne se
--     signe pas. Le verrou est là : un BL signé ferme le BL et l'ordre dont il est né,
--     parce que le client a tenu les deux papiers en main.
--   signataire_nom / signataire_qualite -- qui a signé chez le client, et à quel
--     titre. Saisis, jamais déduits d'un compte : le portail n'a pas de compte client,
--     et le chauffeur qui signe ne vaut pas le directeur qui engage.
--   signed_on -- le jour du clic « Marquer signé », posé par le serveur. La même leçon
--     que V25 (clôture du dossier) et V36 (accord du devis) : une date de fin tapée à
--     la main est une date qu'on recule quand la garantie se discute.
--   reserves -- ce que le client a constaté en reprenant le bus. Ce qui est écrit ici
--     se relit à la réclamation ; ce qui ne l'est pas disparaît avec la mémoire.
--   mileage_at_delivery -- le compteur du bus à la restitution, SAISI sur le papier.
--     C'est la valeur qui fera avancer le rappel du palier suivant, et elle est
--     recopiée sur la fiche du bus par la porte unique du compteur
--     (VehicleMileageService) : une fiche à 8 000 km et un BL à 6 500 km ne se
--     réconcilient pas tout seuls.
--   parts_returned -- les pièces remplacées ont-elles été remises au client ? Le
--     constructeur les réclame pour un dossier garantie ; le client les garde quand
--     c'est lui qui a payé. Le papier doit dire laquelle des deux s'est passée.
--
-- Trois choix de la maison, reconduits :
--
-- 1. AUCUNE CLÉ ÉTRANGÈRE et aucun rattrapage des papiers existants. Les six bons de
--    livraison déjà posés restent lisibles tels quels : `status` garde leur valeur,
--    les six cases neuves restent vides. Le portail ne devine pas un signataire que
--    personne n'a écrit.
--
-- 2. TOUT EST NULLABLE EN BASE, OBLIGATOIRE AU SERVEUR AU BON MOMENT. « Marquer signé »
--    exige le nom du signataire et le scan joint, et écrit le refus en français ; la
--    colonne, elle, n'exige rien — un BL en brouillon n'a encore personne dessus.
--
-- 3. LE CHECK DE `status` EST REPRIS ENTIÈREMENT, avec un mot de plus. Leçon de V30 :
--    une contrainte plus vieille que Flyway ne connaissait que six des huit états, et
--    « j'arrive » partait en 500 sur l'écran du technicien. `ContraintesDeStatutsEnBaseTest`
--    relit les deux listes pour qu'un écart se voie dans un test et pas sur un écran.
--
-- Pas de prix neuf, et le BL cesse d'en porter : la maquette le dit, et l'argent est
-- sur le devis que le client a signé. Les montants déjà posés sur les BL existants
-- restent en base et se lisent — on cesse d'en écrire, on n'efface pas ce qui est écrit.
-- =============================================================================

ALTER TABLE maintenance_items DROP CONSTRAINT IF EXISTS maintenance_items_status_check;
ALTER TABLE maintenance_items ADD CONSTRAINT maintenance_items_status_check
    CHECK (((status)::text = ANY (ARRAY[
        'DRAFT'::character varying,
        'SENT'::character varying,
        'VALIDATED'::character varying,
        'REFUSED'::character varying,
        'IN_PROGRESS'::character varying,
        'DELIVERED'::character varying,
        'COMPLETED'::character varying,
        'CANCELLED'::character varying,
        'SIGNED'::character varying]::text[])));

-- La réception par le client, telle que le papier la demande.
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS signataire_nom      character varying(150);
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS signataire_qualite  character varying(150);
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS signed_on           date;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS reserves            text;
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS parts_returned      boolean;

-- Le compteur à la restitution, dans le même format que celui de la fiche du bus
-- (`vehicles.mileage`, bigint). Il n'est pas une copie décorative : c'est par la porte
-- unique qu'il rejoint la fiche, et la porte refuse une baisse sans motif écrit.
ALTER TABLE maintenance_items ADD COLUMN IF NOT EXISTS mileage_at_delivery bigint;

-- Un compteur qui descend ne se tape pas deux fois pour voir. La case reste nullable
-- (un BL en brouillon n'a pas encore de relevé), mais une valeur écrite est une valeur.
ALTER TABLE maintenance_items DROP CONSTRAINT IF EXISTS maintenance_items_mileage_at_delivery_check;
ALTER TABLE maintenance_items ADD CONSTRAINT maintenance_items_mileage_at_delivery_check
    CHECK (mileage_at_delivery IS NULL OR (mileage_at_delivery >= 0 AND mileage_at_delivery <= 999999));
