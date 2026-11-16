-- Une sortie peut naître d'un dossier SAV avant qu'on sache qui y va.
-- L'homme est alors nommé à l'affectation, avec le camion : le dossier n'est
-- pas toujours ouvert par quelqu'un qui connaît le planning de l'atelier.
-- La prime, elle, reste impossible tant que personne n'est affecté (contrôle
-- serveur dans PrimeService, pas une règle d'interface).
alter table interventions
    alter column technician_id drop not null;
