-- =============================================================================
-- V52__mission_groupe_periode_estimee.sql — la période prévue et l'appel reçu
--
-- L'atelier ne notait que l'heure du départ prévu. Pour dire qu'un homme ou une
-- voiture est déjà pris, il faut la période entière : du départ estimé au retour
-- estimé. Deux missions se chevauchent quand l'une commence avant que l'autre ne
-- finisse — c'est cette règle qui calcule les listes de disponibles.
--
-- Les kilomètres estimés se notent aussi : ils annoncent le voyage, ils ne
-- remplacent pas le compteur photographié par le technicien. Le mode de
-- réception de l'appel (téléphone, WhatsApp, email, sur place, autre) garde la
-- trace de la demande, comme pour une réclamation SAV.
--
-- Aucune de ces colonnes n'est obligatoire : les dossiers déjà écrits avant
-- cette version n'ont pas de période, et un dossier sans période ne bloque
-- personne. L'écran demande, lui, le retour estimé pour calculer les
-- disponibilités.
-- =============================================================================

ALTER TABLE mission_group_dossiers
    ADD COLUMN IF NOT EXISTS call_channel              character varying(40),
    ADD COLUMN IF NOT EXISTS planned_return_at         timestamp without time zone,
    ADD COLUMN IF NOT EXISTS planned_departure_mileage bigint,
    ADD COLUMN IF NOT EXISTS planned_return_mileage    bigint;
