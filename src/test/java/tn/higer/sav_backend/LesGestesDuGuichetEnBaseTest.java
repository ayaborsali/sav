package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tn.higer.sav_backend.dto.InterventionDtos.ClosureRequest;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandResponse;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartReceptionRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPartRequest;
import tn.higer.sav_backend.model.InterventionPurpose;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPartRequestRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.InterventionPartService;
import tn.higer.sav_backend.service.InterventionService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les gestes du guichet et de la livraison, écrits dans la vraie base.
 *
 * La leçon de V30 est simple : 546 tests verts n'ont jamais vu le 500 de « j'arrive »,
 * parce qu'ils bouchonnent les dépôts et n'envoient jamais d'UPDATE à PostgreSQL. Ce
 * fichier fait donc partir les vrais UPDATE — commande, deux réceptions, puis la clôture
 * de la seconde sortie — sur des lignes posées pour l'essai, et relit les tables avec
 * SQL. Ce qu'il prouve ne se prouve nulle part ailleurs : que les colonnes `ordered_at`
 * / `ordered_by` / `quantity_received` / `received_at` / `received_by` sont bien écrites,
 * que les mots « PARTIELLE », « LIVREE » et « POSEE » passent la contrainte CHECK, que
 * les deux colonnes neuves de V32 (`fitting_mission_id`, `fitted_by`) existent et
 * reçoivent ce qu'on leur donne, qu'une mission « MT- » apparaît réellement dans la
 * table `missions` quand une pièce est livrée, et qu'un sixième état inventé est refusé
 * par la base au lieu de faire partir un 500 chez le magasin.
 *
 * Tout se passe dans une transaction que Spring annule : la base de travail n'est pas
 * encombrée d'une ligne d'essai, et rien ne se commande chez le fournisseur.
 */
@SpringBootTest
@Transactional
class LesGestesDuGuichetEnBaseTest {

    private static final String PIECE = "ipr-essai-guichet";

    @Autowired private InterventionPartService service;
    @Autowired private InterventionService sorties;
    @Autowired private InterventionPartRequestRepository pieces;
    @Autowired private InterventionRepository interventions;
    @Autowired private SavCaseRepository savCases;
    @Autowired private VehicleRepository vehicles;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    private static Authentication guichet() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_GESTIONNAIRE_SAV")));
    }

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "sav@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /**
     * La ligne de pièce posée pour l'essai, telle que la sortie l'aurait laissée.
     * Le dossier est la troisième main : c'est lui qui, à la livraison, décide si une
     * mission peut s'ouvrir — une pièce sans réclamation au dos ne demande à personne
     * de la monter, et ce cas se lit ici aussi.
     */
    private InterventionPartRequest lignePosee(String sortieId, String busId, String dossierId) {
        InterventionPartRequest p = new InterventionPartRequest();
        p.setId(PIECE);
        p.setInterventionId(sortieId);
        p.setVehicleId(busId);
        p.setSavCaseId(dossierId);
        p.setReference("2115U");
        p.setDesignation("Pompe à eau");
        p.setReason("Fuite constatée au niveau de la poulie");
        p.setQuantityOrdered(2);
        p.setState(PartRequestState.A_COMMANDER);
        p.setDemandedBy("technicien@higerbus.tn");
        p.setCreatedAt(LocalDateTime.now());
        return pieces.saveAndFlush(p);
    }

    /**
     * Ce que la base dit de la ligne. Le flush n'est pas un détail de test : en HTTP
     * réel, l'UPDATE part quand la transaction se termine. Sans lui, Hibernate garde
     * l'écriture dans son carnet et SQL lirait l'état d'avant — un faux « ça n'a pas
     * été écrit » qui n'apprend rien à personne.
     */
    private Map<String, Object> relire() {
        em.flush();
        return base.queryForMap("select state, quantity_ordered, quantity_received, ordered_at, ordered_by,"
                + " received_at, received_by, fitting_mission_id, fitted_intervention_id, fitted_at, fitted_by"
                + " from intervention_part_requests where id = ?", PIECE);
    }

    /**
     * Une vraie sortie de la base, prise parmi celles qui ont un bus : c'est le chemin
     * de lecture du module Dépannage ({@code full}) qui doit rendre, pas ma ligne d'essai.
     */
    private Intervention uneSortie() {
        List<Intervention> sorties = interventions.findAll().stream()
                .filter(s -> s.getVehicleId() != null)
                .toList();
        Assumptions.assumeTrue(!sorties.isEmpty(), "Aucune sortie avec un bus en base : rien à rattacher.");
        return sorties.get(0);
    }

    /**
     * Une sortie née d'une réclamation qui existe encore : seule celle-là peut voir sa
     * pièce livrée ouvrir une mission, puisqu'une mission répond à un dossier.
     *
     * Le dossier et la sortie sont posés par l'essai, comme la sortie de pose plus bas.
     * Prendre « la première sortie de la base qui a un dossier » mesurait les lignes
     * d'à côté : la mission de pose que l'atelier a jouée le 02/11 porte la même note
     * (« … vue sur la sortie DP-… ») que celle de l'essai, et le comptage retombait
     * dessus — un rouge qui n'accusait aucune écriture de notre part. Un numéro unique
     * par essai rend le compte exact, et l'essai survit aux données de l'atelier.
     */
    private Intervention uneSortieNeuveAvecSonDossier() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à réclamer.");
        Vehicle choisi = bus.get(0);

        String marque = UUID.randomUUID().toString().substring(0, 8);
        SavCase dossier = new SavCase();
        dossier.setId("sav-essai-piece-" + marque);
        dossier.setCaseNumber("ESSAI-PIECE-" + marque);
        dossier.setType(SavCaseType.RECLAMATIONS);
        dossier.setTitle("Panne signalée pour l'essai des gestes du guichet");
        dossier.setVehicleId(choisi.getId());
        dossier.setClientId(choisi.getClientId());
        dossier.setStatus(SavCaseStatus.OPEN);
        dossier.setCreatedAt(LocalDate.now());
        savCases.saveAndFlush(dossier);

        Intervention sortie = new Intervention();
        sortie.setId("itv-essai-piece-" + marque);
        sortie.setNumber("ESSAI-DP-" + marque);
        sortie.setStatus(InterventionStatus.RETURNED);
        sortie.setPurpose(InterventionPurpose.PANNE);
        sortie.setVehicleId(choisi.getId());
        sortie.setClientId(choisi.getClientId());
        sortie.setSavCaseId(dossier.getId());
        sortie.setReportedAt(LocalDateTime.now());
        sortie.setCreatedAt(LocalDateTime.now());
        return interventions.saveAndFlush(sortie);
    }

    /**
     * Une sortie née d'un rappel de forfait, ou une sortie d'avant le rattachement :
     * c'est elle qui joue le cas « la pièce n'a pas de réclamation au dos ».
     */
    private Intervention uneSortieSansDossier() {
        List<Intervention> sorties = interventions.findAll().stream()
                .filter(s -> s.getVehicleId() != null && s.getSavCaseId() == null)
                .toList();
        Assumptions.assumeTrue(!sorties.isEmpty(),
                "Toutes les sorties de la base ont un dossier : ce cas ne peut pas se jouer ici.");
        return sorties.get(0);
    }

    /** La sortie du second passage, telle que la mission de pose l'aurait fait sortir. */
    private Intervention sortieDePose(Intervention naissance) {
        Intervention s = new Intervention();
        s.setId("itv-essai-pose");
        s.setNumber("ESSAI-POSE-" + UUID.randomUUID().toString().substring(0, 8));
        s.setStatus(InterventionStatus.RETURNED);
        s.setPurpose(InterventionPurpose.PANNE);
        s.setVehicleId(naissance.getVehicleId());
        s.setClientId(naissance.getClientId());
        s.setSavCaseId(naissance.getSavCaseId());
        s.setReportedAt(LocalDateTime.now());
        s.setCreatedAt(LocalDateTime.now());
        return interventions.saveAndFlush(s);
    }

    /**
     * Les missions de pose que CETTE sortie a fait naître, comptées en base. La note
     * écrite par le serveur porte son numéro (« … vue sur la sortie DP-2026-015 ») :
     * c'est le seul fil qui relie la ligne d'essai à sa mission. Le fil ne tient que
     * parce que la sortie est neuve et son numéro unique — compter sur une sortie déjà
     * présente en base mesurerait les poses jouées par l'atelier, et un rouge ne dirait
     * plus quelle écriture est fautive.
     */
    private int nombreDeMissionsDeCetteSortie(Intervention sortie) {
        Integer n = base.queryForObject("select count(*) from missions where sav_case_id = ?"
                        + " and note like ?", Integer.class, sortie.getSavCaseId(),
                "Changer la pièce%vue sur la sortie " + sortie.getNumber());
        return n == null ? 0 : n;
    }

    @Test
    void laCommandeEtLesDeuxReceptionsSecriventJusqueDansLaBase() {
        Intervention sortie = uneSortie();
        lignePosee(sortie.getId(), sortie.getVehicleId(), sortie.getSavCaseId());

        // 1. Le guichet passe la commande : un clic, aucune case.
        PartDemandResponse apresCommande = service.commander(PIECE, jeton(), guichet());
        Map<String, Object> ligne = relire();
        assertEquals("COMMANDEE", ligne.get("state"));
        assertEquals("commandee", apresCommande.state());
        assertNotNull(ligne.get("ordered_at"), "La date de commande s'est écrite en base");
        assertEquals("sav@saphir.tn", ligne.get("ordered_by"));
        assertNull(ligne.get("quantity_received"), "Rien n'est encore arrivé au magasin");

        // 2. Première livraison : un sur deux. Le mot « Partielle » sort du cumul.
        service.receptionner(PIECE, new PartReceptionRequest(1), jeton(), guichet());
        ligne = relire();
        assertEquals("PARTIELLE", ligne.get("state"));
        assertEquals(1, ligne.get("quantity_received"));
        assertEquals("sav@saphir.tn", ligne.get("received_by"));

        // 3. Seconde livraison : le cumul égale le commandé, et la base accepte le mot.
        PartDemandResponse fin = service.receptionner(PIECE, new PartReceptionRequest(1), jeton(), guichet());
        ligne = relire();
        assertEquals("LIVREE", ligne.get("state"));
        assertEquals(2, ligne.get("quantity_received"));
        assertEquals("livree", fin.state());

        // Le journal de la sortie porte les trois gestes, dans l'ordre où ils ont eu lieu.
        List<String> notes = base.queryForList(
                "select note from intervention_events where intervention_id = ? and event_type = 'PART'"
                        + " order by recorded_at", String.class, sortie.getId());
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("Commande passée au guichet")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("« Partielle »")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("« Livrée »")), notes.toString());
    }

    /**
     * Ce que P3 ajoute, vu dans la base et non dans un carnet Mockito : la dernière
     * boîte arrivée fait naître une ligne dans `missions`, sur LE MÊME dossier que la
     * réclamation d'origine, et la ligne de pièce porte son identifiant. Sans ce
     * `fitting_mission_id` écrit jusqu'à PostgreSQL, la clôture du second passage ne
     * saurait plus quelle pièce marquer « Posée ».
     */
    @Test
    void laLivraisonDunePieceOuvreUneVraieMissionDansLaTable() {
        Intervention sortie = uneSortieNeuveAvecSonDossier();
        lignePosee(sortie.getId(), sortie.getVehicleId(), sortie.getSavCaseId());
        service.commander(PIECE, jeton(), guichet());

        // « Partielle » n'ouvre rien : la garde se lit aussi en base. Le compte ne
        // porte que sur CET essai — le numéro de la sortie est écrit dans la note de la
        // mission, et un dossier peut légitimement porter une mission de pose née d'un
        // autre papier (c'est le cas sur la base de l'atelier depuis le 28/10). Compter
        // par dossier mesurerait les lignes d'à côté, et le test rouge ne dirait plus
        // quelle écriture est fautive.
        service.receptionner(PIECE, new PartReceptionRequest(1), jeton(), guichet());
        assertNull(relire().get("fitting_mission_id"), "Une commande à moitié livrée ne fait sortir personne");
        assertEquals(0, nombreDeMissionsDeCetteSortie(sortie),
                "Une livraison partielle ne doit pas ouvrir la mission de pose par avance");

        service.receptionner(PIECE, new PartReceptionRequest(1), jeton(), guichet());
        Object missionId = relire().get("fitting_mission_id");
        assertNotNull(missionId, "La pièce livrée n'a pas laissé de mission en base");
        assertEquals(1, nombreDeMissionsDeCetteSortie(sortie),
                "Une seule mission pour une seule boîte entièrement arrivée");

        Map<String, Object> mission = base.queryForMap(
                "select number, state, issue, sav_case_id, alert_id, intervention_id, note from missions where id = ?",
                String.valueOf(missionId));
        assertTrue(String.valueOf(mission.get("number")).startsWith("MT-"), String.valueOf(mission.get("number")));
        assertEquals("A_TRAITER", mission.get("state"), "Le serveur ouvre la demande, il ne la décide pas");
        assertNull(mission.get("issue"), "Qui y va reste un choix d'homme");
        assertEquals(sortie.getSavCaseId(), mission.get("sav_case_id"), "La même réclamation, pas une inventée");
        assertNull(mission.get("alert_id"), "Une pièce ne naît pas d'un rappel de palier");
        assertNull(mission.get("intervention_id"), "Aucune sortie n'est encore partie là-dessus");
        String note = String.valueOf(mission.get("note"));
        assertTrue(note.contains("Pompe à eau") && note.contains("2115U") && note.contains(sortie.getNumber()), note);

        // Et le journal de la sortie de naissance le dit aussi, en base.
        List<String> notes = base.queryForList(
                "select note from intervention_events where intervention_id = ? and event_type = 'PART'"
                        + " order by recorded_at", String.class, sortie.getId());
        assertTrue(notes.stream().anyMatch(n -> n.contains("« changer la pièce »")), notes.toString());
    }

    /**
     * Une pièce livrée sur une sortie née d'un rappel de forfait n'a pas de
     * réclamation au dos : la mission ne peut pas s'ouvrir, et le vide s'écrit au
     * journal au lieu d'inventer un dossier.
     */
    @Test
    void sansDossierLaPieceLivreeNouvreRienMaisLeJournalLeDit() {
        Intervention sortie = uneSortieSansDossier();
        lignePosee(sortie.getId(), sortie.getVehicleId(), null);
        service.commander(PIECE, jeton(), guichet());
        service.receptionner(PIECE, new PartReceptionRequest(2), jeton(), guichet());

        Map<String, Object> ligne = relire();
        assertEquals("LIVREE", ligne.get("state"));
        assertNull(ligne.get("fitting_mission_id"));
        List<String> notes = base.queryForList(
                "select note from intervention_events where intervention_id = ? and event_type = 'PART'"
                        + " order by recorded_at", String.class, sortie.getId());
        assertTrue(notes.stream().anyMatch(n -> n.contains("pas de réclamation au dossier")), notes.toString());
    }

    /**
     * Le dossier a été effacé entre-temps : {@code DELETE /api/sav-cases} ne détachait
     * aucun pointeur, et la base de l'atelier en portait douze sorties comme celle-là
     * avant V33. Si l'ouverture automatique de la mission était partie chercher ce
     * dossier, le magasin aurait reçu un refus sur une boîte qu'il tient dans ses bras.
     * Ici : la livraison passe, la mission ne s'ouvre pas, et le journal dit pourquoi.
     *
     * Le lien cassé est posé par l'essai lui-même, avec un identifiant de dossier qui
     * n'existe pas : compter sur ceux encore présents dans la base rendrait le test
     * muet le jour où la dette est réparée — et V33 la répare.
     */
    @Test
    void unDossierSupprimeNempechePasLaReceptionMaisLEcrit() {
        Intervention sortie = uneSortie();
        String dossierEfface = "sav-essai-dossier-efface";
        lignePosee(sortie.getId(), sortie.getVehicleId(), dossierEfface);
        service.commander(PIECE, jeton(), guichet());
        service.receptionner(PIECE, new PartReceptionRequest(2), jeton(), guichet());

        Map<String, Object> ligne = relire();
        assertEquals("LIVREE", ligne.get("state"), "Le magasin a bien écrit la livraison");
        assertEquals(2, ligne.get("quantity_received"));
        assertNull(ligne.get("fitting_mission_id"), "Aucune mission sur un dossier qui n'existe plus");
        List<String> notes = base.queryForList(
                "select note from intervention_events where intervention_id = ? and event_type = 'PART'"
                        + " order by recorded_at", String.class, sortie.getId());
        assertTrue(notes.stream().anyMatch(n -> n.contains("n'existe plus")), notes.toString());
    }

    /**
     * Le bout de la chaîne, en base : la sortie que la mission de pose a fait sortir se
     * clôt, et « Posée » s'écrit avec sa date, son nom et sa seconde sortie. C'est le
     * seul endroit où le mot « POSEE » touche PostgreSQL — s'il ne passait pas la
     * contrainte CHECK de V31, le 500 sort ici et non sur l'écran du chef.
     */
    @Test
    void laClotureDeLaSortieDePoseEcritPoseeJusquEnBase() {
        Intervention naissance = uneSortieNeuveAvecSonDossier();
        lignePosee(naissance.getId(), naissance.getVehicleId(), naissance.getSavCaseId());
        service.commander(PIECE, jeton(), guichet());
        service.receptionner(PIECE, new PartReceptionRequest(2), jeton(), guichet());
        Object idDeMission = relire().get("fitting_mission_id");
        assertNotNull(idDeMission, "Sans mission de pose, la clôture n'a rien à marquer.");
        String missionId = String.valueOf(idDeMission);

        Intervention pose = sortieDePose(naissance);
        Mission demande = em.find(Mission.class, missionId);
        demande.setInterventionId(pose.getId());
        em.flush();

        sorties.close(pose.getId(), new ClosureRequest("Pièce montée, bus rendu au client.",
                null, null, null, "clichés relevés au poste demain"), jeton(), chef());

        Map<String, Object> ligne = relire();
        assertEquals("POSEE", ligne.get("state"), "Le mot n'a pas passé la contrainte CHECK");
        assertEquals(missionId, ligne.get("fitting_mission_id"), "Le lien d'origine reste lisible");
        assertEquals(pose.getId(), ligne.get("fitted_intervention_id"));
        assertNotNull(ligne.get("fitted_at"), "Une date sans nom ne se discute pas en litige");
        assertEquals("sav@saphir.tn", ligne.get("fitted_by"));

        List<String> notes = base.queryForList(
                "select note from intervention_events where intervention_id = ? and event_type = 'PART'"
                        + " order by recorded_at", String.class, pose.getId());
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("Pièce montée pendant cette sortie")), notes.toString());
    }

    /**
     * Un « Livrée » ne se réceptionne plus : le refus est écrit par le service et la
     * ligne reste telle qu'elle était en base — une seconde écriture effacerait la date
     * de la livraison qui fait foi.
     */
    @Test
    void unePieceDejaLivreeNeSeReceptionnePlusSansRienEcrire() {
        Intervention sortie = uneSortie();
        InterventionPartRequest p = lignePosee(sortie.getId(), sortie.getVehicleId(), sortie.getSavCaseId());
        p.setState(PartRequestState.LIVREE);
        p.setQuantityReceived(2);
        p.setOrderedAt(LocalDateTime.now());
        p.setReceivedAt(LocalDateTime.now());
        pieces.saveAndFlush(p);
        Map<String, Object> avant = relire();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service.receptionner(PIECE, new PartReceptionRequest(1), jeton(), guichet()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refusReason(refus).contains("Livrée"), refusReason(refus));
        assertEquals(avant.get("state"), relire().get("state"));
        assertEquals(avant.get("received_at"), relire().get("received_at"));
    }

    /**
     * Le geste du guichet fermé au compte de technicien : la vérification est faite sur
     * le vrai chemin, avant toute écriture, et rien n'est posé en base.
     */
    @Test
    void leCompteDeTechnicienNeCommandePas() {
        Intervention sortie = uneSortie();
        lignePosee(sortie.getId(), sortie.getVehicleId(), sortie.getSavCaseId());
        Authentication homme = new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_TECHNICIEN")));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service.commander(PIECE, jeton(), homme));

        assertEquals(HttpStatus.FORBIDDEN, refus.getStatusCode());
        assertEquals("A_COMMANDER", relire().get("state"));
        assertNull(relire().get("ordered_at"));
    }

    /**
     * Le sixième état que personne n'a déclaré : la base le refuse. Si un état était
     * ajouté au Java sans migration, c'est ici que ça se voit, pas sur l'écran du
     * magasin.
     */
    @Test
    void unEtatQueLaBaseNeConnaitPasEstRefuse() {
        Intervention sortie = uneSortie();

        DataIntegrityViolationException refuse = assertThrows(DataIntegrityViolationException.class,
                () -> base.update("insert into intervention_part_requests"
                        + " (id, intervention_id, vehicle_id, designation, quantity_ordered, state, created_at)"
                        + " values (?, ?, ?, 'Essai', 1, 'EN_ATTENTE_DU_CLIENT', ?)",
                    PIECE, sortie.getId(), sortie.getVehicleId(), LocalDateTime.now()));
        assertTrue(String.valueOf(refuse.getMessage()).contains("intervention_part_requests_state_check")
                || String.valueOf(refuse.getCause()).contains("intervention_part_requests_state_check"),
                String.valueOf(refuse.getMessage()));
    }

    private static String refusReason(ResponseStatusException e) {
        return e.getReason() == null ? "" : e.getReason();
    }
}
