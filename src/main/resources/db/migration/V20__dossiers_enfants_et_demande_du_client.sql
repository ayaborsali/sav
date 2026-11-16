-- =============================================================================
-- V20__dossiers_enfants_et_demande_du_client.sql — une réclamation peut enfanter
-- un dossier garantie, et l'appel du client laisse une trace
--
-- Deux trous, vérifiés dans le code et non dans un plan.
--
-- 1) LE TYPE D'UN DOSSIER NE CHANGE JAMAIS. Un dossier naît avec une famille
--    (visite, garantie, qualité, pièce, réclamation) et il y meurt. Quand un
--    client se plaint (« Réclamation ») et que la panne s'avère couverte,
--    l'atelier doit déposer un claim (« Garantie ») — et aujourd'hui il ne peut
--    pas : il recrée un dossier neuf, sans rien qui dise d'où il sort.
--
--    On ne transforme pas la réclamation en dossier garantie. Si on changeait son
--    type on réécrirait l'histoire : la preuve que le client s'était plaint
--    d'abord disparaîtrait, et les compteurs des cinq familles se mettraient à
--    bouger tout seuls après coup. Un fait passé ne s'édite pas.
--
--    parent_case_id garde donc le lien dans le sens unique : le claim sait de
--    quelle réclamation il est né. Une réclamation n'enfante qu'un seul claim —
--    deux dossiers garantie pour une même panne, c'est deux demandes de
--    remboursement pour le même travail, et c'est exactement ce qu'un contrôleur
--    constructeur cherche.
--
-- 2) RIEN NE GARDE LA DEMANDE DU CLIENT. Si un client appelle et que le poste ne
--    crée pas de dossier, il n'y a aucune trace de l'appel. Et quand un dossier
--    existe, ses champs disent ce que NOUS on a fait, jamais ce que LUI a
--    demandé : qui a téléphoné, par quel canal, à quelle heure, ce qu'il attend.
--    Le contrat SNTRI engage une réponse sous 48 h — sans l'heure de l'appel, ce
--    délai ne se mesure pas, il se discute.
--
--    Les quatre colonnes reprennent les libellés du document de référence
--    (« Contact client », « Canal : Téléphone | E-mail | Courrier | Visite |
--    Autre », « Date de réclamation », « Action attendue »). Rien n'est ajouté,
--    rien n'est renommé.
--
--    « caller_name » reste du texte libre, et c'est une dette déclarée : la table
--    des contacts du client n'existe pas encore. Le jour où elle existe, cette
--    colonne devient une référence et l'écran ne demandera plus de retaper un nom.
--
-- Aucune reprise de données : les dossiers déjà écrits n'ont pas d'enfant et
-- n'ont pas d'appel enregistré. Leurs colonnes restent vides, ce qui est la
-- vérité, plutôt qu'une valeur inventée par le portail.
-- =============================================================================

alter table sav_cases add column if not exists parent_case_id  character varying(50);
alter table sav_cases add column if not exists caller_name     character varying(255);
alter table sav_cases add column if not exists caller_channel  character varying(20);
alter table sav_cases add column if not exists called_at       timestamp without time zone;
alter table sav_cases add column if not exists expected_action text;

-- L'enfant cherche son parent, et le parent cherche ses enfants : les deux se
-- lisent sur une fiche ouverte, jamais en parcourant la table entière à l'écran.
create index if not exists idx_sav_cases_parent_case_id on sav_cases (parent_case_id);
