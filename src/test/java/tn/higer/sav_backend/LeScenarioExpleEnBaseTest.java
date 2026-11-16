package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tn.higer.sav_backend.dto.InterventionDtos.ArrivalRequest;
import tn.higer.sav_backend.dto.InterventionDtos.ClosureRequest;
import tn.higer.sav_backend.dto.InterventionDtos.DeclarationQualiteRequest;
import tn.higer.sav_backend.dto.InterventionDtos.DeclarationQualiteResponse;
import tn.higer.sav_backend.dto.InterventionDtos.DepartRequest;
import tn.higer.sav_backend.dto.InterventionDtos.DispatchRequest;
import tn.higer.sav_backend.dto.InterventionDtos.FinishRequest;
import tn.higer.sav_backend.dto.InterventionDtos.ProgressRequest;
import tn.higer.sav_backend.dto.InterventionDtos.ReturnRequest;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandRequest;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandResponse;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartReceptionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionEnvoiRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.dto.SavCaseDtos.AgSortieJointe;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.CallerChannel;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.ServiceVehicle;
import tn.higer.sav_backend.model.ServiceVehicleStatus;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.ServiceVehicleRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AgPaperAssemblyService;
import tn.higer.sav_backend.service.AgPaperDocumentService;
import tn.higer.sav_backend.service.AgPaperPdfService;
import tn.higer.sav_backend.service.InterventionPartService;
import tn.higer.sav_backend.service.InterventionService;
import tn.higer.sav_backend.service.MissionService;
import tn.higer.sav_backend.service.SavCaseService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La feuille « exple » du 28/10 rejouée dans l'ordre de l'atelier, sur le code du
 * 29/10, dans la vraie base.
 *
 * Why this file exists: the 28/10 replay was played over HTTP against his running
 * server. Its verdicts stand, but that server is a build from before P1..P6 — its
 * {@code /v3/api-docs} carries no {@code papier-ag} route — so replaying the sheet over
 * HTTP today would grade yesterday's code and report holes already closed. Here the
 * lines go through the live services, and every claim is re-read in SQL.
 *
 * Ce que la feuille annonce, et ce que le code répond le 29/10 :
 *  - L2 — l'appel écrit ses quatre cases : tient.
 *  - L3 — la case « un technicien doit aller sur place » est la seule origine de la
 *    demande, et une demande n'a qu'une origine : tient.
 *  - L5 — un homme, une voiture, une sortie menée jusqu'à la clôture, le compteur du
 *    bus avancé par la porte unique : tient. La voiture ne vient toujours pas du
 *    dossier — c'est l'écran Dépannage qui la choisit. <b>Deux trous fermés par ce
 *    rejeu</b> : la sortie déjà rattachée à un dossier ne faisait plus avancer ce
 *    dossier à la clôture (il restait « Rentré » pour toujours, l'écran n'envoyant un
 *    numéro que quand l'atelier en re-choisit un), et l'homme du dossier se relit par
 *    son compte, pas par sa fiche paie.
 *  - marche 3 (« le technicien identifie → une pièce de rechange ») : c'était le trou
 *    du 28/10, la route répondait 404. P1 et P2 l'ont fermé : la ligne naît de la
 *    sortie, son état se calcule à la réception.
 *  - L7/L8 — la commande part, la livraison se déclare, et la dernière boîte ouvre la
 *    mission « changer la pièce » ; la seconde sortie écrit « Posée ». L8 n'existait
 *    pas le 28/10, elle tient.
 *  - L6 — « si garantie, pas de devis » : gardée depuis P5, jugée au jour de la panne
 *    (décision du 29/10), et les deux billets peuvent coexister (P4).
 *  - L4 — « un problème identifié ouvre un dossier qualité » : fermée le 29/10, mais
 *    par le clic du chef sur la ligne, jamais par le serveur (V34 porte le lien
 *    sortie → dossier). Le comptage en base reste à la fin : zéro avant le clic, un
 *    après — une ligne de la feuille ne s'acquitte pas d'un commentaire.
 *  - L9 + marche 7 — le papier pour Higer montre le dépannage qu'il se fait rembourser,
 *    et le document sort en octets. Le maillon était faux de son côté comme du nôtre :
 *    la sortie de dépannage reste rattachée à la réclamation (V20 tient la filiation),
 *    donc {@code AgPaperAssemblyService} la relit par le dossier parent, sinon Higer ne
 *    reçoit qu'une déclaration sans preuve.
 *
 * Un compte rendu qui n'est pas du code : ses trois comptes techniciens sont chacun
 * engagés sur une sortie « Dispatched » qui n'a jamais bougé, donc plus aucun homme
 * libre à envoyer dehors tant qu'elles ne sont pas rentrées ou clôturées. L'essai ouvre
 * le sien plutôt que de déplacer les siennes.
 *
 * Tout se passe dans une transaction que Spring annule : ses bus, ses sorties et ses
 * dossiers ressortent tels qu'ils sont entrés, et rien ne se commande chez le
 * fournisseur.
 */
@SpringBootTest
@Transactional
class LeScenarioExpleEnBaseTest {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private MissionService missions;
    @Autowired private InterventionService sorties;
    @Autowired private InterventionPartService pieces;
    @Autowired private SavCaseService dossiers;
    @Autowired private AgPaperAssemblyService papierHiger;
    @Autowired private AgPaperDocumentService feuille;
    @Autowired private AgPaperPdfService miseEnPage;
    @Autowired private SavCaseRepository savCases;
    @Autowired private InterventionRepository interventions;
    @Autowired private InterventionPhotoRepository preuves;
    @Autowired private MaintenanceRepository papiersAtelier;
    @Autowired private VehicleRepository vehicles;
    @Autowired private TechnicianRepository techniciens;
    @Autowired private ServiceVehicleRepository voitures;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;
    @Autowired private UserRepository comptes;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    // ------------------------------------------------------------------ comptes

    /** Le guichet : il note l'appel, il commande et réceptionne la pièce. */
    private static Authentication guichet() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_GESTIONNAIRE_SAV")));
    }

    /**
     * L'atelier : il envoie l'homme et il clôture. Les étapes de l'homme dehors sont
     * jouées par ce compte — la garde d'appartenance d'une sortie se prouve dans le
     * module Dépannage, pas ici.
     */
    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "chef.atelier@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    // ------------------------------------------------------------------ matière d'essai

    private String marque() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Un bus client, sa garantie réglée pour l'essai par la fiche elle-même. */
    private Vehicle unBus(LocalDate finDeGarantie) {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base.");
        Vehicle choisi = bus.get(0);
        choisi.setWarrantyEndDate(finDeGarantie);
        return vehicles.saveAndFlush(choisi);
    }

    /**
     * L'homme du rejeu, compte et fiche ouverts pour l'essai puis annulés avec la
     * transaction.
     *
     * Un homme choisi chez lui ne passerait pas : ses trois comptes techniciens sont
     * chacun engagés sur une sortie « Dispatched » qui n'a jamais bougé, et la porte de
     * l'affectation refuse un homme déjà dehors — le dossier ne peut hériter personne,
     * et l'envoi se refuse. Ce blocage-là se rapporte, il ne se truque pas en déplaçant
     * ses lignes.
     *
     * Le compte, pas seulement la fiche : l'homme du dossier se retrouve par son compte
     * ({@code TechnicianDirectory.ficheFor}), et une fiche sans compte technicien actif
     * derrière fait naître une sortie sans nom.
     */
    private Technician unHommeLibre() {
        String m = marque();
        AppUser compte = new AppUser();
        compte.setName("Homme d'essai " + m);
        compte.setEmail("exple." + m + "@higerbus.tn");
        compte.setPasswordHash("essai-jamais-connecte");
        compte.setRole(Role.TECHNICIEN);
        compte.setActive(true);
        comptes.saveAndFlush(compte);

        Technician fiche = new Technician();
        fiche.setId("tch-exple-" + m);
        fiche.setName(compte.getName());
        fiche.setUserId(compte.getId());
        fiche.setActive(true);
        return techniciens.saveAndFlush(fiche);
    }

    /**
     * La voiture de déplacement du rejeu, ouverte pour l'essai puis annulée avec la
     * transaction — la même règle que pour l'homme.
     *
     * Emprunter une voiture réelle ne tenait pas : l'atelier travaille dans le portail
     * pendant que la suite tourne, et une sortie « rentrée mais pas soldée » garde sa
     * voiture engagée du point de vue du serveur même quand la fiche de la voiture est
     * revenue à « libre ». Ce refus-là est juste — c'est le test qui doit parler de SA
     * voiture, comme il parle déjà de SON homme.
     */
    private ServiceVehicle uneVoitureLibre() {
        String m = marque();
        ServiceVehicle voiture = new ServiceVehicle();
        voiture.setId("svc-exple-" + m);
        voiture.setRegistration("ESSAI-" + m);
        voiture.setLabel("Voiture de déplacement d'essai");
        voiture.setKind("Voiture");
        voiture.setStatus(ServiceVehicleStatus.AVAILABLE);
        voiture.setCurrentMileage(5000L);
        return voitures.saveAndFlush(voiture);
    }

    /**
     * La réclamation telle que le guichet la tape (marche 1) : les quatre cases de
     * l'appel, le bus, l'homme du dossier, et ce que le client attend.
     */
    private SavCase uneReclamation(Vehicle bus, Technician homme, LocalDate jourDeLAppel, String m) {
        SavCase c = new SavCase();
        c.setId("sav-exple-" + m);
        c.setCaseNumber("EXPL-R-" + m);
        c.setType(SavCaseType.RECLAMATIONS);
        c.setTitle("Le bus ne redémarre plus devant le dépôt");
        c.setVehicleId(bus.getId());
        c.setClientId(bus.getClientId());
        c.setStatus(SavCaseStatus.OPEN);
        c.setCreatedAt(jourDeLAppel);
        c.setDescription("Le client signale une panne de démarrage, fumée côté alternateur.");
        c.setCallerName("M. Ben Ali, Saphir Trans");
        c.setCallerChannel(CallerChannel.TELEPHONE);
        c.setCalledAt(jourDeLAppel.atTime(9, 5));
        c.setExpectedAction("Un technicien doit aller sur place");
        c.setTechnicianUserId(homme.getUserId());
        c.setTechnician(homme.getName());
        return savCases.saveAndFlush(c);
    }

    /** Le geste du dossier : la case « un technicien doit aller sur place ». */
    private Mission uneDemandeNoteeParLeDossier(SavCase réclamation) {
        return missions.enregistrerDepuisDossier(réclamation, jeton());
    }

    /**
     * Les étapes de l'homme jusqu'au diagnostic : la voiture et l'homme affectés, le
     * départ, l'arrivée, le code défaut. Aucune ne se saute — une étape se refuse si
     * son horodatage manque.
     */
    private void jusquAuDiagnostic(String sortieId, ServiceVehicle voiture, long kmBus,
                                  String codeDefaut, String diagnostic, String pieceEnCause) {
        long auDepot = voiture.getCurrentMileage() == null ? 0L : voiture.getCurrentMileage();
        sorties.dispatch(sortieId, new DispatchRequest(voiture.getId(), auDepot, null, List.of()),
                jeton(), chef());
        preuvesDeLaVoiture(sortieId, PhotoAngle.DEPART, PhotoAngle.COMPTEUR_DEPART);
        sorties.depart(sortieId, new DepartRequest(auDepot + 40L), jeton(), chef());
        sorties.arrive(sortieId, new ArrivalRequest(kmBus, "Bus devant le dépôt, capot ouvert"), jeton(), chef());
        sorties.progress(sortieId, new ProgressRequest(codeDefaut, diagnostic, pieceEnCause), jeton(), chef());
    }

    /** Le même parcours, raconté pour la pompe à eau de la feuille. */
    private void jusquALaPompe(String sortieId, ServiceVehicle voiture, long kmBus) {
        jusquAuDiagnostic(sortieId, voiture, kmBus, "2115U",
                "Pompe à eau serrante, fuite au niveau de la poulie ; courroie à reprendre.",
                "Pompe à eau");
    }

    /** Les lieux quittés, la rentrée à l'atelier — la voiture se libère en rentrant. */
    private void rentree(String sortieId, ServiceVehicle voiture) {
        long auDepot = voiture.getCurrentMileage() == null ? 0L : voiture.getCurrentMileage();
        sorties.leaveSite(sortieId, jeton(), chef());
        preuvesDeLaVoiture(sortieId, PhotoAngle.RETOUR, PhotoAngle.COMPTEUR_RETOUR);
        sorties.returnToWorkshop(sortieId, new ReturnRequest(auDepot + 90L,
                "Chef d'atelier", "Bus rentré au dépôt"), jeton(), chef());
    }

    /**
     * Les deux clichés de la voiture de déplacement, versés ici sans passer par le
     * disque : le rejeu parle des étapes, pas du versement de fichiers. Les deux
     * lignes suffisent — le départ et le retour ne regardent que leur existence.
     */
    private void preuvesDeLaVoiture(String sortieId, PhotoAngle vehicule, PhotoAngle compteur) {
        for (PhotoAngle angle : List.of(vehicule, compteur)) {
            InterventionPhoto p = new InterventionPhoto();
            p.setId("itp-exple-" + marque() + "-" + angle.name().toLowerCase());
            p.setInterventionId(sortieId);
            p.setAngle(angle);
            p.setFilePath("essai/" + p.getId() + ".png");
            p.setSha256(marque() + marque());
            p.setUploadedAt(LocalDateTime.now());
            preuves.saveAndFlush(p);
        }
    }

    private void cloturer(String sortieId, String note) {
        sorties.close(sortieId, new ClosureRequest(note, null, null, null,
                "Équipe sans téléphone au moment du passage — clichés relevés au poste le lendemain"),
                jeton(), chef());
    }

    /** La ligne de pièce, relue dans sa table. Le flush avant SQL : sans lui, Hibernate n'a rien envoyé. */
    private Map<String, Object> relireLaPiece(String pieceId) {
        em.flush();
        return base.queryForMap("select state, sav_case_id, mission_id, intervention_id, fitting_mission_id,"
                + " fitted_intervention_id, fitted_at, quantity_ordered, quantity_received"
                + " from intervention_part_requests where id = ?", pieceId);
    }

    // ------------------------------------------------------------------ L2, L3

    /**
     * L2 et L3 jouées l'une après l'autre. Les quatre cases de l'appel s'écrivent dans
     * la table du dossier, et la demande qui suit ne les recopie pas : elle les relit.
     */
    @Test
    void l_appel_du_client_laisse_sa_trace_puis_la_case_du_dossier_note_une_demande() {
        LocalDate panne = LocalDate.now().minusWeeks(6);
        Vehicle bus = unBus(panne.plusDays(20));
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), panne, marque());

        em.flush();
        Map<String, Object> trace = base.queryForMap("select caller_name, caller_channel, called_at,"
                + " expected_action from sav_cases where id = ?", réclamation.getId());
        assertEquals("M. Ben Ali, Saphir Trans", trace.get("caller_name"));
        assertEquals("TELEPHONE", trace.get("caller_channel"));
        assertEquals(panne.toString(), String.valueOf(trace.get("called_at")).substring(0, 10));
        assertEquals("Un technicien doit aller sur place", trace.get("expected_action"));

        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        MissionResponse vue = missions.get(demande.getId());
        assertTrue(demande.getNumber().startsWith("MT-"), demande.getNumber());
        assertEquals("a_traiter", vue.state(), "le serveur ouvre la question, il ne la décide pas");
        assertEquals("M. Ben Ali, Saphir Trans", vue.callerName(),
                "la mission relit l'appel au lieu de le ressaisir");
        assertEquals("Téléphone", vue.callerChannelLabel());
        assertNotNull(vue.busLabel(), "le bus se nomme côté serveur, pas à l'écran");

        // Cocher, décocher, recocher : une seule demande pour le même appel.
        assertEquals(demande.getId(), uneDemandeNoteeParLeDossier(réclamation).getId(),
                "deux clics n'alignent pas deux missions sur la même panne");
    }

    /**
     * L3, sa face négative : une demande n'a qu'une origine. Le 28/10 ne notait que le
     * sens positif ; le refus compte autant, parce que c'est lui qui empêche une mission
     * de répondre à la fois à un appel et à un palier, donc à deux histoires.
     */
    @Test
    void une_demande_ne_peut_pas_repondre_a_deux_origines_a_la_fois() {
        Vehicle bus = unBus(LocalDate.now().plusMonths(4));
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), LocalDate.now(), marque());

        ResponseStatusException sansOrigine = assertThrows(ResponseStatusException.class,
                () -> missions.creer(new MissionRequest(null, null, "Appel noté sans dossier"),
                        jeton(), guichet()));
        assertTrue(sansOrigine.getReason().contains("jamais aux deux"), sansOrigine.getReason());

        // Le dossier existe, le rappel aussi : les deux à la fois se refusent, et rien
        // ne s'écrit.
        Integer avant = base.queryForObject("select count(*) from missions", Integer.class);
        assertThrows(ResponseStatusException.class, () -> missions.creer(
                new MissionRequest(réclamation.getId(), "spa-inexistant", "Deux origines"),
                jeton(), guichet()));
        em.flush();
        assertEquals(avant, base.queryForObject("select count(*) from missions", Integer.class),
                "un refus ne laisse pas de mission derrière lui");
    }

    // ------------------------------------------------------------------ L5, marches 3 et 4

    /**
     * L5 du début à la fin, avec la marche 3 de la feuille — le vrai trou du 28/10.
     *
     * Ce qui n'existait pas et se lit ici : « il faut cette pièce » écrit sur la sortie
     * de dépannage (la route rendait un 404), et le compteur du bus avancé par la porte
     * unique au moment où l'homme relève son dernier kilométrage.
     *
     * L4 se rejoue maintenant telle que la feuille l'écrit : la sortie vécue jusqu'au
     * diagnostic n'ouvre AUCUN dossier qualité, puis le clic du chef en ouvre un — avec
     * le code défaut relevé devant le bus dans son titre. Les deux compteurs se lisent à
     * la fin, pour que la ligne ne se croie pas acquise.
     */
    @Test
    void l_homme_envoye_dehors_demande_sa_piece_et_rentre_en_ayant_avance_le_compteur_du_bus() {
        LocalDate panne = LocalDate.now().minusWeeks(2);
        Vehicle bus = unBus(panne.plusDays(30));
        long kmConnu = bus.getMileage() == null ? 0L : bus.getMileage();
        Technician homme = unHommeLibre();
        SavCase réclamation = uneReclamation(bus, homme, panne, marque());
        int qualiteAvant = dossiersQualite();

        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        // La voiture ne vient pas du dossier : elle se choisit à l'écran Dépannage.
        MissionResponse engagee = missions.envoyerUnHomme(demande.getId(),
                new MissionEnvoiRequest(null, null), jeton(), chef());
        String sortieId = engagee.interventionId();
        assertNotNull(sortieId, "la demande envoyée doit tenir sa sortie");
        Intervention sortie = interventions.findById(sortieId).orElseThrow();
        assertEquals(réclamation.getId(), sortie.getSavCaseId());
        assertEquals(homme.getId(), sortie.getTechnicianId(), "l'homme vient du dossier");
        assertNull(sortie.getServiceVehicleId(), "la voiture, elle, reste à choisir dehors");

        // La voiture se tient une fois : la relancer après le départ ne trouverait plus
        // qu'un parc vide, puisqu'elle est engagée sur cette sortie.
        ServiceVehicle voiture = uneVoitureLibre();
        jusquALaPompe(sortieId, voiture, kmConnu + 20L);

        // Marche 3 : « le technicien identifie → pièce de rechange ». La ligne naît de la
        // sortie, avec le bus, la demande et le dossier dans son dos — rien de ressaisi.
        PartDemandResponse piece = pieces.demander(sortieId, new PartDemandRequest("2115U",
                "Pompe à eau", 2, "Fuite constatée au niveau de la poulie, pièce à changer"),
                jeton(), chef());
        Map<String, Object> ligne = relireLaPiece(piece.id());
        assertEquals("A_COMMANDER", ligne.get("state"), "la demande ouvre l'état, personne ne le tape");
        assertEquals(réclamation.getId(), ligne.get("sav_case_id"), "la pièce connaît déjà sa réclamation");
        assertEquals(demande.getId(), ligne.get("mission_id"),
                "la ligne sait quelle demande l'a fait sortir");
        assertEquals(sortieId, ligne.get("intervention_id"));

        // L5 : le compteur du bus avance par la porte unique, et son origine s'écrit.
        sorties.finish(sortieId, new FinishRequest("repaired",
                "Pompe déposée, en attente de la pièce au magasin", kmConnu + 60L), jeton(), chef());
        em.flush();
        Map<String, Object> compteur = base.queryForMap("select mileage, mileage_origin from vehicles where id = ?",
                bus.getId());
        assertEquals(kmConnu + 60L, compteur.get("mileage"), "la sortie a avancé le compteur de la fiche");
        assertEquals("RELEVE_TECHNICIEN", compteur.get("mileage_origin"));

        rentree(sortieId, voiture);
        cloturer(sortieId, "Bus rendu au client, pièce commandée au magasin.");
        em.flush(); // Sans ce vide, la clôture n'a rien envoyé et SQL relirait « returned ».
        assertEquals("closed", base.queryForObject("select status from interventions where id = ?",
                String.class, sortieId).toLowerCase());
        assertEquals("CLOSED", base.queryForObject("select status from sav_cases where id = ?",
                String.class, réclamation.getId()), "le dossier suit la sortie jusqu'au bout");

        // L4, rejouée comme la feuille l'écrit : le diagnostic a posé son code sur la
        // sortie, et rien d'autre. Aucun dossier qualité tant qu'un humain ne clique pas.
        assertEquals(qualiteAvant, dossiersQualite(),
                "ligne 4 de la feuille : un problème identifié n'ouvre pas de dossier tout seul");

        DeclarationQualiteResponse declaree = sorties.declarerEnQualite(sortieId,
                new DeclarationQualiteRequest("Pompe à eau", null, null, null), jeton(), chef());
        assertEquals(qualiteAvant + 1, dossiersQualite(), "un clic, un dossier");

        Map<String, Object> qualite = base.queryForMap(
                "select type, status, declared_from_intervention_id, title from sav_cases where id = ?",
                declaree.dossierId());
        assertEquals("QUALITE", qualite.get("type"));
        assertEquals("OPEN", qualite.get("status"), "la déclaration s'ouvre, elle ne se clôt pas avec la sortie");
        assertEquals(sortieId, qualite.get("declared_from_intervention_id"),
                "le dossier sait de quelle sortie il est né — sans V34, rien ne relait ce lien");
        assertTrue(String.valueOf(qualite.get("title")).contains("2115U"),
                String.valueOf(qualite.get("title")));
        assertEquals("CLOSED", base.queryForObject("select status from sav_cases where id = ?",
                String.class, réclamation.getId()),
                "déclarer ajoute un dossier, cela ne remplace pas la réclamation de la sortie");
    }

    /**
     * Une pièce ne se demande pas avant d'avoir vu le bus : le refus du 28/10 était
     * correct et il tient. Le magasin ne peut pas être lancé sur une pièce qu'aucun
     * œil n'a contrôlée devant le carter.
     */
    @Test
    void une_piece_ne_se_demande_pas_avant_d_etre_arrive_devant_le_bus() {
        Vehicle bus = unBus(LocalDate.now().plusMonths(2));
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), LocalDate.now(), marque());
        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        MissionResponse engagee = missions.envoyerUnHomme(demande.getId(),
                new MissionEnvoiRequest(null, null), jeton(), chef());
        String sortieId = engagee.interventionId();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> pieces.demander(sortieId, new PartDemandRequest("2115U", "Pompe à eau", 1,
                        "À vérifier"), jeton(), chef()));
        assertTrue(refus.getReason().contains("devant le bus"), refus.getReason());
        assertEquals(0, relireLignesDeLaSortie(sortieId), "le refus n'a rien posé en base");
    }

    // ------------------------------------------------------------------ L7, L8

    /**
     * L7 et L8 — le maillon qui manquait à sa chaîne du bus. La commande part du
     * guichet, la livraison est écrite par un humain (personne ne la détermine), la
     * dernière boîte arrivée ouvre la mission « changer la pièce », et le second
     * passage la marque « Posée ».
     */
    @Test
    void la_piece_livree_ouvre_la_mission_de_pose_et_le_second_passage_la_marque_posee() {
        LocalDate panne = LocalDate.now().minusWeeks(3);
        Vehicle bus = unBus(panne.plusDays(10));
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), panne, marque());
        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        MissionResponse premiere = missions.envoyerUnHomme(demande.getId(),
                new MissionEnvoiRequest(null, null), jeton(), chef());
        ServiceVehicle voiture = uneVoitureLibre();
        jusquALaPompe(premiere.interventionId(), voiture, 150_000L);
        PartDemandResponse piece = pieces.demander(premiere.interventionId(),
                new PartDemandRequest("2115U", "Pompe à eau", 2, "Fuite au niveau de la poulie"),
                jeton(), chef());
        sorties.finish(premiere.interventionId(), new FinishRequest("repaired",
                "Bus rendu, pompe en commande", null), jeton(), chef());
        rentree(premiere.interventionId(), voiture);
        cloturer(premiere.interventionId(), "Bus rendu, pièce en commande.");

        // L7 : le guichet commande, un clic, aucune case.
        pieces.commander(piece.id(), jeton(), guichet());
        assertEquals("COMMANDEE", relireLaPiece(piece.id()).get("state"));

        // Marche 5 du 28/10, toujours vraie : « la pièce est arrivée » se déclare, elle ne
        // se déduit de rien. Et « Partielle » n'envoie personne.
        pieces.receptionner(piece.id(), new PartReceptionRequest(1), jeton(), guichet());
        assertEquals("PARTIELLE", relireLaPiece(piece.id()).get("state"));
        assertNull(relireLaPiece(piece.id()).get("fitting_mission_id"),
                "à moitié livrée : rien à monter, personne d'envoyé");

        // L8 : la dernière boîte fait naître la demande de pose.
        pieces.receptionner(piece.id(), new PartReceptionRequest(1), jeton(), guichet());
        Map<String, Object> livree = relireLaPiece(piece.id());
        assertEquals("LIVREE", livree.get("state"));
        Object idDePose = livree.get("fitting_mission_id");
        assertNotNull(idDePose, "la pièce livrée n'a pas ouvert de mission : L8 ne tiendrait pas");
        Mission pose = em.find(Mission.class, String.valueOf(idDePose));
        assertTrue(pose.getNumber().startsWith("MT-"), pose.getNumber());
        assertEquals(MissionState.A_TRAITER, pose.getState(),
                "le serveur pose la question de la pose, il ne l'enverra pas seul");
        assertEquals(réclamation.getId(), pose.getSavCaseId(), "la même réclamation, pas une inventée");

        // Le second passage : nouvelle sortie sur la même réclamation, puis « Posée ».
        MissionResponse envoyee = missions.envoyerUnHomme(pose.getId(),
                new MissionEnvoiRequest(null, null), jeton(), chef());
        assertNotNull(envoyee.interventionId(), "la pose attend sa sortie");
        assertFalse(envoyee.interventionId().equals(premiere.interventionId()),
                "la pose part sur sa propre sortie, elle ne reprend pas celle qui est clôturée");
        ServiceVehicle deuxieme = uneVoitureLibre();
        jusquALaPompe(envoyee.interventionId(), deuxieme, 150_080L);
        sorties.finish(envoyee.interventionId(), new FinishRequest("repaired",
                "Pompe montée, essai de charge conforme", null), jeton(), chef());
        rentree(envoyee.interventionId(), deuxieme);
        cloturer(envoyee.interventionId(), "Pièce montée, bus rendu au client.");

        Map<String, Object> posee = relireLaPiece(piece.id());
        assertEquals("POSEE", posee.get("state"), "le mot doit passer la contrainte CHECK de V31");
        assertEquals(envoyee.interventionId(), posee.get("fitted_intervention_id"));
        assertNotNull(posee.get("fitted_at"), "une pose sans date ne se discute pas chez le constructeur");
    }

    // ------------------------------------------------------------------ L6, la fourche

    /**
     * L6 — la ligne que le 28/10 ne pouvait pas vendre : « si garantie, pas de devis ».
     * Elle est gardée depuis P5 et jugée <b>au jour de la panne</b> (décision du 29/10) :
     * une garantie éteinte depuis trois semaines ne rend pas la panne facturabile au
     * client.
     *
     * Et les deux billets restent sur la même demande (P4) : l'homme est sorti ET le
     * devis se dresse, dès que le constructeur a dit non. C'est la fourche de la
     * feuille, jouée dans son ordre.
     */
    @Test
    void le_devis_se_refuse_sur_un_bus_couvert_le_jour_de_la_panne_puis_se_libre_apres_le_non() {
        LocalDate panne = LocalDate.now().minusWeeks(3);
        Vehicle bus = unBus(panne.plusDays(10));
        assertTrue(bus.getWarrantyEndDate().isBefore(LocalDate.now()),
                "l'essai se joue sur une garantie éteinte depuis peu : c'est le cas que la date du jour ratait");
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), panne, marque());

        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        missions.envoyerUnHomme(demande.getId(), new MissionEnvoiRequest(null, null), jeton(), chef());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> missions.etablirDevis(demande.getId(), jeton(), chef()));
        assertTrue(refus.getReason().contains("au jour de la panne"), refus.getReason());
        assertTrue(refus.getReason().contains(panne.format(JOUR)), refus.getReason());
        assertTrue(refus.getReason().contains("appel du client"), refus.getReason());
        assertTrue(refus.getReason().contains("appel en garantie"), refus.getReason());

        // Le constructeur a répondu non : la panne retombe sur le client.
        réclamation.setClaimState(ClaimState.REFUSE);
        réclamation.setClaimDecision("Hors garantie constructeur : usure de la poulie, pas défaut.");
        réclamation.setClaimDecisionAt(LocalDate.now());
        savCases.saveAndFlush(réclamation);

        MissionResponse vue = missions.etablirDevis(demande.getId(), jeton(), chef());
        assertNotNull(vue.papierNumero());
        assertTrue(vue.papierNumero().startsWith("DV-"), vue.papierNumero());
        assertEquals(MaintenanceType.DEVIS, papiersAtelier.findById(vue.papierId()).orElseThrow().getType());
        assertNotNull(vue.interventionId(), "le devis n'a pas effacé la sortie : les deux billets restent");

        em.flush();
        Map<String, Object> ligne = base.queryForMap("select intervention_id, maintenance_item_id, state"
                + " from missions where id = ?", demande.getId());
        assertNotNull(ligne.get("intervention_id"));
        assertNotNull(ligne.get("maintenance_item_id"));
        assertEquals("EN_COURS", ligne.get("state"));
    }

    /**
     * La garantie était déjà finie le jour où le bus est tombé en panne : plus rien à
     * demander au constructeur, le devis se dresse sans attendre un écrit. Le rejeu du
     * 28/10 disait « le devis passe sur un bus garanti » ; la garde lit maintenant les
     * deux dates et ne refuse pas ce qui est hors garantie.
     */
    @Test
    void une_panne_hors_garantie_se_chiffre_sans_attendre_un_non_du_constructeur() {
        LocalDate panne = LocalDate.now().minusMonths(8);
        Vehicle bus = unBus(panne.minusDays(15));
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), panne, marque());
        Mission demande = uneDemandeNoteeParLeDossier(réclamation);

        MissionResponse vue = missions.etablirDevis(demande.getId(), jeton(), chef());

        assertNotNull(vue.papierNumero());
        assertNull(vue.interventionId(), "personne n'est encore envoyé dehors");
    }

    /**
     * La garde ne dévie pas quand la réponse du constructeur est « Accordé » : la panne
     * est prise en charge, elle ne se chiffre pas chez le client. Le refus se relit en
     * base, et aucun papier n'est sorti.
     */
    @Test
    void un_appel_accorde_ne_se_chiffre_pas_chez_le_client() {
        LocalDate panne = LocalDate.now().minusWeeks(1);
        Vehicle bus = unBus(panne.plusMonths(6));
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), panne, marque());
        réclamation.setClaimState(ClaimState.ACCORDE);
        réclamation.setClaimDecision("Défaut de fabrication reconnu, remplacement pris en charge.");
        réclamation.setClaimDecisionAt(LocalDate.now());
        savCases.saveAndFlush(réclamation);
        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        int papiersAvant = nombreDePapiers();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> missions.etablirDevis(demande.getId(), jeton(), chef()));

        assertTrue(refus.getReason().contains("sous garantie"), refus.getReason());
        assertEquals(papiersAvant, nombreDePapiers(), "le refus ne dresse aucun papier");
    }

    // ------------------------------------------------------------------ L9, marche 7

    /**
     * L9 et la marche 7 — « dossier complet à envoyer à Higer ». Le 28/10 : les cases
     * existaient, la sortie liée restait null, et aucun document ne sortait (404 sur
     * {@code document.pdf}). Ici le papier se construit sur la réclamation et son
     * dépannage, et le pont Python en écrit les octets.
     *
     * Le point qui ferme la chaîne : un appel en garantie doit montrer le travail qu'il
     * se fait rembourser. Sans ces lignes, Higer relit une déclaration sans preuve.
     */
    @Test
    void l_appel_en_garantie_montre_le_depannage_qu_il_reclame_et_sort_en_document() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");
        LocalDate panne = LocalDate.now().minusWeeks(4);
        Vehicle bus = unBus(panne.plusMonths(11));
        long km = bus.getMileage() == null ? 120_000L : bus.getMileage();
        SavCase réclamation = uneReclamation(bus, unHommeLibre(), panne, marque());
        Mission demande = uneDemandeNoteeParLeDossier(réclamation);
        MissionResponse envoyee = missions.envoyerUnHomme(demande.getId(),
                new MissionEnvoiRequest(null, null), jeton(), chef());
        ServiceVehicle voiture = uneVoitureLibre();
        jusquAuDiagnostic(envoyee.interventionId(), voiture, km + 20L, "2165J",
                "Régulateur intégré hors service, charge mesurée à 24 V au banc.",
                "Alternateur 28V 120A");
        pieces.demander(envoyee.interventionId(), new PartDemandRequest("37300-10300",
                "Alternateur 28V 120A", 1, "Charge mesurée à 24 V"), jeton(), chef());
        sorties.finish(envoyee.interventionId(), new FinishRequest("repaired",
                "Alternateur changé sur place", km + 45L), jeton(), chef());
        rentree(envoyee.interventionId(), voiture);
        cloturer(envoyee.interventionId(), "Pris en charge garantie, pièce neuve montée.");

        // L9 : la réclamation enfante l'appel, et l'appel porte la trace de l'appel.
        SavCase claim = dossiers.openClaimFrom(réclamation, jeton());
        assertEquals(SavCaseType.GARANTIES, claim.getType());
        assertEquals(réclamation.getId(), claim.getParentCaseId());
        assertEquals(panne.atTime(9, 5), claim.getCalledAt(),
                "le jour de la panne se transmet, sinon la garantie se discute à l'aveugle");
        assertEquals("A_DEPOSER", claim.getClaimState().name());

        // Les cases du papier AG, écrites comme au guichet.
        claim.setAgIncidentDate(panne);
        claim.setAgWriterName("Mounir Bouazizi");
        claim.setAgRepairOrderNumber("OR-EXPLE-" + marque());
        claim.setAgRepairOrderDate(panne.plusDays(1));
        claim.setAgKm((int) (km + 45L));
        claim.setAgCause("Défaut de fabrication du régulateur intégré, contrôlé au banc.");
        claim.setAgConcernedPart("Alternateur 28V 120A");
        claim.setAgSecurityImpact(false);
        claim.setAgAllocatedTime(2.1);
        claim.setAgHourlyCost(70.0);
        claim.setAgLaborOperationCode("ELEC-ALT-02");
        claim.setResolution("Alternateur remplacé, charge mesurée à 28,2 V après reprise.");
        claim.setClaimAmountClaimed(1200.0);
        savCases.saveAndFlush(claim);

        AgPapierJustifie lu = papierHiger.relire(claim.getId(), chef());

        // Le maillon qui manquait : le dossier de garantie montre le dépannage qu'il
        // réclame, relu par le dossier parent.
        List<AgSortieJointe> laSortie = lu.sorties().stream()
                .filter(s -> s.number().equals(envoyee.interventionNumber())).toList();
        assertEquals(1, laSortie.size(), "le papier ne porte pas la sortie de dépannage : "
                + lu.sorties().stream().map(AgSortieJointe::number).toList());
        assertTrue(laSortie.get(0).terminee(), "un papier qui montre une sortie en cours réclame un travail fini");
        assertEquals("2165J", laSortie.get(0).defectCode(),
                "le code défaut relevé dehors se lit sur la ligne du papier");
        assertEquals(Long.valueOf(km + 45L), laSortie.get(0).kmReleveApres(),
                "le compteur de la sortie se recopie sur la feuille, sans ressaisie");
        assertFalse(lu.manquants().stream().anyMatch(x -> x.contains("Aucune sortie")),
                lu.manquants().toString());

        // La marchandise suit : la pièce vue devant le bus, avec sa référence.
        assertTrue(lu.pieces().stream().anyMatch(p -> "37300-10300".equals(p.reference())),
                lu.pieces().toString());

        // Ce qui ne dépend pas de l'atelier reste un signal, pas une faute.
        assertTrue(lu.signes().stream().anyMatch(x -> x.contains("codes que seul le constructeur")),
                lu.signes().toString());

        Map<String, Object> plan = feuille.payload(claim.getId(), chef());
        assertEquals(claim.getCaseNumber(), plan.get("number"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        @SuppressWarnings("unchecked")
        List<List<String>> lignesDeSortie = (List<List<String>>) tables.get(0).get("rows");
        assertTrue(lignesDeSortie.stream().anyMatch(l -> l.contains(envoyee.interventionNumber())),
                "la feuille imprimée doit porter la ligne de sortie : " + lignesDeSortie);

        byte[] pdf = miseEnPage.render(plan);
        assertTrue(pdf.length > 1000, "une feuille A4 remplie : " + pdf.length + " octets");
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        String queue = new String(pdf, pdf.length - 64, 64, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(queue.contains("%%EOF"), "papier tronqué : " + queue);
    }

    // ------------------------------------------------------------------ L1

    /**
     * L1 — la visite. Le dossier de visite planifié ouvre bien sa demande, et le devis
     * y passe sans passer par le constructeur. C'est sa décision du 29/10 : le rejeu a
     * découvert que la garde de la garantie ne regardait pas la famille du dossier et
     * refusait un devis sur une visite d'un bus couvert — une visite périodique n'est
     * pas appelable, elle est vendue dans le forfait, donc rien ne peut y obliger un
     * appel en garantie.
     */
    @Test
    void une_visite_planifiee_ouvre_une_demande_et_le_devis_y_passe_sans_appel_en_garantie() {
        Vehicle bus = unBus(LocalDate.now().plusMonths(9));
        String m = marque();
        SavCase visite = new SavCase();
        visite.setId("sav-exple-v-" + m);
        visite.setCaseNumber("EXPL-V-" + m);
        visite.setType(SavCaseType.VISITES);
        visite.setTitle("Visite périodique des 60 000 km");
        visite.setVehicleId(bus.getId());
        visite.setClientId(bus.getClientId());
        visite.setStatus(SavCaseStatus.OPEN);
        visite.setCreatedAt(LocalDate.now());
        visite.setPlannedDate(LocalDate.now().plusDays(3));
        savCases.saveAndFlush(visite);

        MissionResponse demande = missions.creer(new MissionRequest(visite.getId(), null,
                "Le client amène son bus pour la visite de son forfait"), jeton(), guichet());
        assertTrue(demande.number().startsWith("MT-"), demande.number());
        assertEquals("a_traiter", demande.state());
        assertEquals("Visite périodique des 60 000 km", demande.caseTitle());

        int papiersAvant = nombreDePapiers();
        MissionResponse reponse = missions.etablirDevis(demande.id(), jeton(), chef());
        assertEquals("devis", reponse.issue(), "la demande répond maintenant par un devis");
        assertEquals("en_cours", reponse.state());
        assertTrue(reponse.papierNumero() != null && reponse.papierNumero().startsWith("DV-"),
                String.valueOf(reponse.papierNumero()));
        assertEquals(papiersAvant + 1, nombreDePapiers(), "un seul papier chiffré naît du devis");
    }

    // ------------------------------------------------------------------ sondes

    /** Combien de dossiers qualité en base : L4 doit répondre zéro mouvement. */
    private int dossiersQualite() {
        em.flush();
        Integer n = base.queryForObject("select count(*) from sav_cases where type = 'QUALITE'", Integer.class);
        return n == null ? 0 : n;
    }

    /** Les lignes de pièce posées sur une sortie. */
    private int relireLignesDeLaSortie(String sortieId) {
        em.flush();
        Integer n = base.queryForObject("select count(*) from intervention_part_requests"
                + " where intervention_id = ?", Integer.class, sortieId);
        return n == null ? 0 : n;
    }

    /** Le nombre de papiers de l'atelier : un refus n'en ajoute pas. */
    private int nombreDePapiers() {
        em.flush();
        Integer total = base.queryForObject("select count(*) from maintenance_items", Integer.class);
        assertNotNull(total);
        return total;
    }

    private static boolean reportlabPresent() {
        try {
            Process probe = new ProcessBuilder("python", "-c", "import reportlab").start();
            boolean ok = probe.waitFor(20, TimeUnit.SECONDS) && probe.exitValue() == 0;
            probe.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }
}
