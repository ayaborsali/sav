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

import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.MissionService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4, écrit dans la vraie base : la même panne a un dépannage ET un devis.
 *
 * Les 49 tests unitaires de {@code MissionServiceTest} bouchonnent les dépôts ; ils
 * n'ont donc jamais envoyé à PostgreSQL l'UPDATE qui pose les DEUX colonnes de réponse
 * sur une même ligne de {@code missions}. C'est exactement ce que ce fichier vérifie :
 * la sortie et le devis existent comme deux lignes séparées, la mission les porte
 * tous les deux, et « Soldée » ne se lit qu'une fois les deux finis.
 *
 * Un dossier est posé pour l'essai, sur un bus qui existe : aucune ligne d'essai ne
 * reste dans sa base, la transaction étant annulée par Spring.
 */
@SpringBootTest
@Transactional
class LaDeuxiemeReponseDeLaMemeDemandeEnBaseTest {

    @Autowired private MissionService demandes;
    @Autowired private SavCaseRepository savCases;
    @Autowired private InterventionRepository interventions;
    @Autowired private MaintenanceRepository documents;
    @Autowired private VehicleRepository vehicles;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
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
     * Un dossier neuf, sur un bus qui existe : la demande n'attend personne d'autre.
     *
     * Le bus est mis exprès hors garantie. Depuis P5, « Établir un devis » se refuse
     * tant que la garantie court — et cette page joue les deux réponses d'une panne,
     * pas la garde du constructeur. La date reculée est écrite dans la transaction,
     * qui l'annule : la fiche du bus retrouve sa garantie dès l'essai fini.
     */
    private SavCase unDossierNeuf() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à réclamer.");
        Vehicle choisi = bus.get(0);
        // L'écriture passe par la fiche elle-même : un UPDATE SQL laisserait dans le
        // carnet d'Hibernate le bus avec sa date de garantie, et la garde lirait cette
        // copie — un refus qui n'existe pas dans la base, c'est-à-dire un faux essai.
        choisi.setWarrantyEndDate(LocalDate.now().minusDays(1));
        vehicles.saveAndFlush(choisi);
        SavCase neuf = new SavCase();
        neuf.setId("sav-essai-deux-reponses");
        neuf.setCaseNumber("ESSAI-2R-" + UUID.randomUUID().toString().substring(0, 8));
        neuf.setType(SavCaseType.RECLAMATIONS);
        neuf.setTitle("Dossier posé pour l'essai des deux réponses");
        neuf.setVehicleId(choisi.getId());
        neuf.setClientId(choisi.getClientId());
        neuf.setStatus(SavCaseStatus.OPEN);
        neuf.setCreatedAt(LocalDate.now());
        return savCases.saveAndFlush(neuf);
    }

    private MissionResponse uneDemandeAvecUnHommeDehors() {
        SavCase dossier = unDossierNeuf();
        MissionResponse demande = demandes.creer(
                new MissionRequest(dossier.getId(), null, "Le client appelle pour une panne"), jeton(), chef());
        return demandes.envoyerUnHomme(demande.id(), null, jeton(), chef());
    }

    /** La ligne de la mission, relue en SQL : c'est la colonne qui nous intéresse, pas le DTO. */
    private Map<String, Object> relireMission(String id) {
        em.flush();
        return base.queryForMap("select state, issue, intervention_id, maintenance_item_id"
                + " from missions where id = ?", id);
    }

    /**
     * Le coeur de P4 : après « Envoyer un homme », « Établir un devis » ne remplace pas
     * la sortie — les deux réponses tiennent sur la même demande, dans deux colonnes
     * différentes. Et le devis arrive bien comme un papier neuf, vide, DV-.
     */
    @Test
    void l_homme_dehors_et_le_devis_signent_tous_les_deux_sur_la_meme_demande() {
        MissionResponse engagee = uneDemandeAvecUnHommeDehors();
        assertNotNull(engagee.interventionNumber(), "la sortie a été faite par le serveur, pas par le test");

        MissionResponse vue = demandes.etablirDevis(engagee.id(), jeton(), chef());
        Map<String, Object> ligne = relireMission(engagee.id());

        assertEquals(engagee.interventionId(), ligne.get("intervention_id"),
                "la sortie qui court n'est pas effacée par le devis");
        assertNotNull(ligne.get("maintenance_item_id"), "le devis est bien rattaché à la mission");
        assertEquals("EN_COURS", ligne.get("state"));
        assertEquals("Établir un devis", vue.issueLabel());
        assertEquals(engagee.interventionNumber(), vue.interventionNumber());

        MaintenanceItem devis = documents.findById((String) ligne.get("maintenance_item_id")).orElseThrow();
        assertEquals(MaintenanceType.DEVIS, devis.getType());
        assertEquals(MaintenanceStatus.DRAFT, devis.getStatus());
        assertTrue(devis.getNumber().startsWith("DV-"), "un devis s'appelle DV-, pas OR- : " + devis.getNumber());
        assertTrue(devis.getDetails().isEmpty(), "le portail n'invente aucun prix à la place de l'atelier");
        // Une seule demande : les deux réponses ne refont pas une seconde mission sur
        // le même dossier.
        assertEquals(1, nombre("select count(*) from missions where id = ?", engagee.id()));
    }

    /**
     * La ligne se rend quand les DEUX papiers sont finis : l'homme est rentré, sa
     * sortie est clôturée, le devis est encore brouillon — la demande reste ouverte.
     * Elle ne passe à « Soldée » qu'une fois le devis terminé.
     */
    @Test
    void la_demande_n_est_soldee_qu_une_fois_que_les_deux_papiers_sont_finis() {
        MissionResponse engagee = uneDemandeAvecUnHommeDehors();
        MissionResponse avecDevis = demandes.etablirDevis(engagee.id(), jeton(), chef());
        Map<String, Object> ligne = relireMission(engagee.id());

        Intervention sortie = interventions.findById(avecDevis.interventionId()).orElseThrow();
        sortie.setStatus(InterventionStatus.CLOSED);
        interventions.saveAndFlush(sortie);
        assertEquals("En cours", demandes.get(engagee.id()).stateLabel(),
                "l'homme est rentré, le devis reste à chiffrer : la demande n'est pas rendue");

        MaintenanceItem devis = documents.findById((String) ligne.get("maintenance_item_id")).orElseThrow();
        devis.setStatus(MaintenanceStatus.COMPLETED);
        documents.saveAndFlush(devis);
        assertEquals("Soldée", demandes.get(engagee.id()).stateLabel(),
                "les deux papiers sont finis : la demande est rendue, sans que personne l'écrive");
        // « Soldée » reste une lecture : la colonne ne porte toujours que EN_COURS.
        assertEquals("EN_COURS", relireMission(engagee.id()).get("state"));
    }

    /**
     * La borne de la règle : ordre de travail et devis écrivent dans la même case.
     * Le refus est écrit, il nomme le papier qui tient la place — et RIEN n'est
     * ajouté à la base derrière le refus.
     */
    @Test
    void un_devis_deja_pose_refuse_qu_un_ordre_vienne_prendre_sa_place() {
        MissionResponse engagee = uneDemandeAvecUnHommeDehors();
        demandes.etablirDevis(engagee.id(), jeton(), chef());
        Map<String, Object> avant = relireMission(engagee.id());
        assertNotNull(avant.get("maintenance_item_id"));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> demandes.traiterAtelier(engagee.id(), jeton(), chef()));

        assertTrue(refus.getReason().contains("même case"), refus.getReason());
        assertTrue(refus.getReason().contains("DV-"), refus.getReason());
        Map<String, Object> apres = relireMission(engagee.id());
        assertEquals(avant.get("maintenance_item_id"), apres.get("maintenance_item_id"),
                "le devis qui court n'a pas été remplacé");
        assertEquals(avant.get("intervention_id"), apres.get("intervention_id"));
        assertEquals("EN_COURS", apres.get("state"));
    }

    /**
     * L'autre sens de la même case, et la question posée sur le lot « ordre » : un devis
     * d'extras peut-il s'ouvrir sur une demande qui a déjà son ordre ? Réponse en base :
     * non. Les deux papiers écrivent dans {@code maintenance_item_id}, le refus nomme
     * l'ordre qui tient la place, et la case de la demande ne bouge pas — le travail en
     * cours n'est pas effacé par un papier de prix.
     */
    @Test
    void un_ordre_deja_pose_refuse_qu_un_devis_vienne_prendre_sa_place() {
        MissionResponse engagee = uneDemandeAvecUnHommeDehors();
        demandes.traiterAtelier(engagee.id(), jeton(), chef());
        Map<String, Object> avant = relireMission(engagee.id());
        assertNotNull(avant.get("maintenance_item_id"), "l'ordre est bien rattaché à la demande");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> demandes.etablirDevis(engagee.id(), jeton(), chef()));

        assertTrue(refus.getReason().contains("même case"), refus.getReason());
        assertTrue(refus.getReason().contains("OR-"),
                "le refus nomme l'ordre qui tient la place : " + refus.getReason());
        assertEquals(avant.get("maintenance_item_id"),
                relireMission(engagee.id()).get("maintenance_item_id"),
                "l'ordre qui travaille n'a pas été remplacé");
        assertEquals(avant.get("intervention_id"), relireMission(engagee.id()).get("intervention_id"));
    }

    /**
     * Et cela ne change pas quand l'ordre est fermé : la case reste tenue. La demande
     * se lit « Soldée » (le papier du bout de la chaîne est fini) et le devis d'extras
     * se refuse toujours — ce qui veut dire qu'aujourd'hui une anomalie hors contrat
     * chiffrée APRÈS le travail passe par une demande de plus, pas par la même.
     */
    @Test
    void un_ordre_fini_ne_laisse_toujours_pas_passer_le_devis_d_extras() {
        MissionResponse engagee = uneDemandeAvecUnHommeDehors();
        demandes.traiterAtelier(engagee.id(), jeton(), chef());
        String ordreId = (String) relireMission(engagee.id()).get("maintenance_item_id");
        MaintenanceItem ordre = documents.findById(ordreId).orElseThrow();
        ordre.setStatus(MaintenanceStatus.COMPLETED);
        documents.saveAndFlush(ordre);
        // L'homme est rentré et sa sortie est clôturée : les deux réponses sont finies.
        Intervention sortie = interventions.findById(engagee.interventionId()).orElseThrow();
        sortie.setStatus(InterventionStatus.CLOSED);
        interventions.saveAndFlush(sortie);

        assertEquals("Soldée", demandes.get(engagee.id()).stateLabel(),
                "l'ordre est terminé : la demande est rendue à la lecture");
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> demandes.etablirDevis(engagee.id(), jeton(), chef()));

        assertTrue(refus.getReason().contains("même case"), refus.getReason());
        assertEquals(ordreId, relireMission(engagee.id()).get("maintenance_item_id"),
                "et la case n'a pas bougé d'un iota");
    }

    private int nombre(String sql, Object... params) {
        Integer value = base.queryForObject(sql, Integer.class, params);
        assertNotNull(value);
        return value;
    }
}
