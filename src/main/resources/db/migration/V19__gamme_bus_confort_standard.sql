-- =============================================================================
-- V19__gamme_bus_confort_standard.sql — le bus a une gamme, pas un slogan
--
-- L'écran Véhicules faisait choisir un modèle commercial (Higer KLQ6129G 12m
-- Urbain, Azure 12m Électrique, Touring KLQ6128…), sept valeurs écrites en dur
-- dans le formulaire. Aucune ne disait la seule chose dont le forfait d'entretien
-- a besoin : ce bus se suit-il en Confort ou en Standard. Le catalogue, lui,
-- gardait un « bus_model » libre, rempli par la fiche Excel.
--
-- Il n'y a que deux gammes, et chaque gamme a son ensemble de forfaits. Le modèle
-- commercial ne porte pas cette décision : deux bus du même KLQ6129G peuvent être
-- livrés l'un en Confort, l'autre en Standard. Le châssis (vehicles.vin) et
-- l'immatriculation disent déjà exactement quel bus c'est ; la colonne « model »
-- devient donc la gamme, et le serveur refuse toute autre écriture.
--
-- Cette reprise ne change aucune structure : les deux colonnes existent déjà et
-- sont du texte. Elle convertit ce qu'elles contiennent.
--
-- Comment les anciennes valeurs sont lues :
--   ce qui écrit « standard »           -> Standard
--   le reste (les sept modèles Higer,
--   y compris « BUS HIGER CONFORT»)     -> Confort
-- La seule flotte documentée du portail est la fiche « BUS HIGER CONFORT » du
-- contrat SNTRI : rien dans les papiers ne décrit un bus Standard. Un bus qui en
-- est un aura donc été converti en Confort ; sa fiche se corrige en un choix dans
-- l'écran Véhicules, et la vente du forfait refuse tant que la gamme du bus ne
-- correspond pas à celle du plan.
-- =============================================================================

-- 1) Les bus : « standard » d'abord, pendant que le texte d'origine est lisible.
update vehicles
   set model = 'Standard'
 where model is not null
   and btrim(model) <> ''
   and lower(btrim(model)) like '%standard%';

-- 2) Tout ce qui restait est un modèle commercial, donc ne disait rien d'une
--    gamme : la valeur devient Confort.
update vehicles
   set model = 'Confort'
 where model is not null
   and btrim(model) <> ''
   and model <> 'Standard';

-- 3) Les plans du catalogue, même règle sur leur case Modèle.
update maintenance_plans
   set bus_model = 'Standard'
 where bus_model is not null
   and btrim(bus_model) <> ''
   and lower(btrim(bus_model)) like '%standard%';

update maintenance_plans
   set bus_model = 'Confort'
 where bus_model is not null
   and btrim(bus_model) <> ''
   and bus_model <> 'Standard';

-- 4) Un plan importé sans case Modèle : sa propre réponse est souvent dans son
--    titre, que le document Higer écrit en toutes lettres
--    (« BUS HIGER CONFORT »). Sinon la case reste vide, et la vente refuse ce
--    plan en disant quoi reprendre dans la fiche d'import — jamais une gamme
--    devinée par le portail.
update maintenance_plans
   set bus_model = 'Standard'
 where (bus_model is null or btrim(bus_model) = '')
   and lower(coalesce(name, '')) like '%standard%';

update maintenance_plans
   set bus_model = 'Confort'
 where (bus_model is null or btrim(bus_model) = '')
   and lower(coalesce(name, '')) like '%confort%';
