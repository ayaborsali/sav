-- =============================================================================
-- V17__compteur_bus_origine.sql — le compteur du bus a une porte, et une origine
--
-- Le même chiffre existait à deux endroits sans se parler : la colonne
-- « kilometrage » de l'écran Véhicules (vehicles.mileage, tapée à la main) et les
-- compteurs relus par le technicien pendant une sortie
-- (interventions.bus_mileage à l'arrivée, bus_mileage_end à la fin). Un bus à
-- 8 000 km sur sa fiche et relevé à 10 120 km le 18/10 donnait deux réponses selon
-- l'écran où l'on regardait, et le rappel des paliers devait comparer les trois
-- chiffres pour deviner le vrai.
--
-- Désormais une seule écriture, dans VehicleMileageService : un relevé du
-- technicien fait monter la fiche, la saisie de l'atelier est contrôlée contre le
-- dernier relevé, et le compteur ne redescend que sur motif écrit. Ces colonnes
-- disent d'où vient la valeur posée sur la fiche, parce que c'est la provenance
-- qui se discute quand un palier est contesté — pas le nombre seul.
--
-- mileage_recorded_at : l'heure à laquelle le portail a appris la valeur (l'heure
-- du bouton du technicien, l'heure de l'enregistrement à l'atelier), jamais la date
-- de l'événement routier.
--
-- mileage_correction_reason : remplie seulement quand la valeur est remise plus
-- bas volontairement (compteur changé, relevé faux). Comme la levée d'un rappel de
-- palier et les taux de prime, une baisse se justifie par écrit et se relit.
-- =============================================================================

alter table vehicles add column if not exists mileage_origin              character varying(30);
alter table vehicles add column if not exists mileage_recorded_at         timestamp without time zone;
alter table vehicles add column if not exists mileage_recorded_by         character varying(255);
alter table vehicles add column if not exists mileage_correction_reason   character varying(500);

-- Reprise : les sorties déjà parties avant cette phase portaient des compteurs que
-- la fiche du bus ignorait. Sans cette ligne, la fiche d'un bus sorti le 20/10
-- resterait plus basse que le relevé du technicien et le refus de saisie
-- demanderait demain un motif pour une valeur que le portail connaît déjà.
-- Pour chaque bus, la valeur la plus haute jamais relue sur une sortie devient la
-- valeur de la fiche, avec le nom de celui qui l'a lue et l'heure de l'appel.
update vehicles v
   set mileage             = r.km,
       mileage_origin      = 'RELEVE_TECHNICIEN',
       mileage_recorded_at = r.reported_at,
       mileage_recorded_by = r.releve_par
  from (
        select distinct on (i.vehicle_id)
               i.vehicle_id,
               greatest(coalesce(i.bus_mileage, 0), coalesce(i.bus_mileage_end, 0)) as km,
               i.reported_at,
               coalesce(t.name, 'compte ' || coalesce(i.created_by, '—')) as releve_par
          from interventions i
          left join technicians t on t.id = i.technician_id
         where i.bus_mileage is not null
            or i.bus_mileage_end is not null
         order by i.vehicle_id,
                  greatest(coalesce(i.bus_mileage, 0), coalesce(i.bus_mileage_end, 0)) desc,
                  i.reported_at desc
       ) r
 where r.vehicle_id = v.id
   and r.km > coalesce(v.mileage, 0);

-- Les fiches laissées à leur valeur d'origine sont des saisies humaines : c'est
-- aussi une information, et un compteur sans origine ne peut pas être défendu.
update vehicles
   set mileage_origin = 'ATELIER'
 where mileage_origin is null;
