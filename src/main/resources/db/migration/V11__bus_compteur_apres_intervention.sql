-- Le technicien relit le compteur du bus quand il termine sur place : sans cette
-- valeur, impossible de dire combien le bus a roulé pendant l'intervention.
alter table interventions
    add column bus_mileage_end bigint;
