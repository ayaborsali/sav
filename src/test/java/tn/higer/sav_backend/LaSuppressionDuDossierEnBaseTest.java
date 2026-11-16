package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.SavCaseService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La garde de suppression du dossier, écrite et relue dans la vraie base.
 *
 * Les tests de service bouchonnent les dépôts : ils prouvent la règle, jamais le
 * lien. Or la règle d'ici EST un lien — « cette sortie répond-elle encore à ce
 * dossier ? » — et ce lien n'existe pas en base : aucune clé étrangère vers
 * `sav_cases`, seulement des character varying(50) que le service remplit. Un dépôt
 * mocké peut donc affirmer le contraire de ce que PostgreSQL contient, et personne
 * ne le verrait.
 *
 * Ce fichier relit les quatre tables qui pointent un dossier et compte les
 * références qui mènent nulle part. Il répond à la question que le guichet s'est
 * vue faire sans le savoir : douze sorties et neuf missions portaient, le 28/10, un
 * `sav_case_id` de dossier effacé. V33 les a remis à NULL — cette lecture est la
 * preuve qu'il ne reste rien à réparer chez lui, et la garantie que le défaut ne
 * revient pas maintenant que la garde écrit avant d'effacer.
 *
 * Les trois autres posent une écriture réelle devant la garde : une sortie prise
 * telle quelle dans sa base, une demande insérée à l'instant, puis un dossier neuf
 * qui n'a encore de suite nulle part. Tout tient dans une transaction que Spring
 * annule : aucune ligne d'essai ne reste sur son travail.
 */
@SpringBootTest
@Transactional
class LaSuppressionDuDossierEnBaseTest {

    @Autowired private SavCaseService cases;
    @Autowired private SavCaseRepository savCases;
    @Autowired private InterventionRepository interventions;
    @Autowired private MissionRepository missions;
    @Autowired private VehicleRepository vehicles;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    /** Les pointeurs vers sav_cases, réunis : c'est cette liste qu'on veut voir vide. */
    private static final String ORPHELINS = """
            SELECT count(*) FROM (
                SELECT sav_case_id AS cible FROM interventions WHERE sav_case_id IS NOT NULL
                UNION ALL SELECT sav_case_id FROM missions WHERE sav_case_id IS NOT NULL
                UNION ALL SELECT sav_case_id FROM intervention_part_requests WHERE sav_case_id IS NOT NULL
                UNION ALL SELECT sav_case_id FROM item_parts WHERE sav_case_id IS NOT NULL
            ) liens WHERE NOT EXISTS (SELECT 1 FROM sav_cases s WHERE s.id = liens.cible)
            """;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test compte des lignes réelles dans PostgreSQL.");
    }

    /** Le ménage de V33 : plus aucune ligne ne pointe un dossier qui n'existe plus. */
    @Test
    void rienNePointeUnDossierAbsent() {
        Integer orphelins = base.queryForObject(ORPHELINS, Integer.class);

        assertNotNull(orphelins);
        assertEquals(0, orphelins,
                "des lignes répondent encore à un dossier effacé : la garde est contournée quelque part");
    }

    /**
     * Une sortie prise telle quelle dans sa base. Le refus doit nommer SON numéro
     * DP-, pas un chiffre d'inventaire : c'est ce numéro que le guichet va clore.
     */
    @Test
    void uneSortieReelleRetientSonDossier() {
        Intervention sortie = uneSortieAvecDossier();
        SavCase dossier = savCases.findById(sortie.getSavCaseId()).orElseThrow();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> cases.verifierSuppression(dossier));

        assertTrue(refus.getReason().contains(sortie.getNumber()), refus.getReason());
        Integer liens = base.queryForObject(
                "SELECT count(*) FROM interventions WHERE sav_case_id = ?", Integer.class, dossier.getId());
        assertNotNull(liens);
        assertTrue(liens > 0, "le lien que la garde protège est bien dans la table");
    }

    /** Une demande écrite à l'instant : la garde la voit jusqu'à sa table. */
    @Test
    void uneDemandeEnBaseRetientSonDossier() {
        SavCase dossier = savCases.findById(uneSortieAvecDossier().getSavCaseId()).orElseThrow();
        Mission demande = new Mission();
        demande.setId("mss-essai-suppression");
        demande.setNumber("ESSAI-MT-" + UUID.randomUUID().toString().substring(0, 8));
        demande.setSavCaseId(dossier.getId());
        demande.setState(MissionState.A_TRAITER);
        demande.setCreatedAt(LocalDateTime.now());
        demande.setCreatedBy("sav@saphir.tn");
        missions.save(demande);
        // Sans ce flush, Hibernate garde l'INSERT dans son carnet et la relecture
        // SQL compterait une demande que la base n'a pas encore reçue.
        em.flush();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> cases.verifierSuppression(dossier));

        assertTrue(refus.getReason().contains(demande.getNumber()), refus.getReason());
        assertEquals(1, nombre("SELECT count(*) FROM missions WHERE id = ? AND sav_case_id = ?",
                demande.getId(), dossier.getId()), "la demande est écrite avec son dossier au dos");
    }

    /**
     * Le dossier neuf, sans sortie ni demande : la garde ne doit pas devenir un
     * verrou, une saisie d'essai mal engagée doit pouvoir s'effacer.
     */
    @Test
    void unDossierSansSuiteSeSupprimeSansRefus() {
        Vehicle bus = vehicles.findAll().stream().findFirst().orElseThrow();
        SavCase neuf = new SavCase();
        neuf.setId("sav-essai-suppression");
        neuf.setCaseNumber("ESSAI-SAV-" + UUID.randomUUID().toString().substring(0, 8));
        neuf.setType(SavCaseType.RECLAMATIONS);
        neuf.setTitle("Dossier posé pour l'essai de la garde");
        neuf.setVehicleId(bus.getId());
        neuf.setStatus(SavCaseStatus.OPEN);
        neuf.setCreatedAt(LocalDate.now());
        savCases.save(neuf);
        em.flush();

        assertDoesNotThrow(() -> cases.verifierSuppression(neuf));

        String id = neuf.getId();
        assertEquals(0, nombre("""
                SELECT (SELECT count(*) FROM interventions WHERE sav_case_id = ?)
                     + (SELECT count(*) FROM missions WHERE sav_case_id = ?)
                """, id, id), "rien ne répond encore à ce dossier");
    }

    private int nombre(String sql, Object... params) {
        Integer value = base.queryForObject(sql, Integer.class, params);
        assertNotNull(value);
        return value;
    }

    /** Une sortie de sa base qui a un dossier au dos — et ce dossier existe encore. */
    private Intervention uneSortieAvecDossier() {
        List<Intervention> rows = interventions.findAll().stream()
                .filter(i -> i.getSavCaseId() != null)
                .filter(i -> savCases.existsById(i.getSavCaseId()))
                .toList();
        Assumptions.assumeTrue(!rows.isEmpty(),
                "Skippé : aucune sortie rattachée à un dossier encore présent.");
        return rows.get(0);
    }
}
