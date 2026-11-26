-- Une ligne de bus peut désigner un véhicule par son immatriculation saisie à la main
-- (hors portefeuille du client) : vehicle_id doit alors pouvoir rester vide.
ALTER TABLE mission_group_buses ALTER COLUMN vehicle_id DROP NOT NULL;
