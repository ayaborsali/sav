package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.SoldPlanService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le devis d'un palier, écrit et relu dans la vraie base.
 *
 * La règle, telle que le porteur du portail l'a corrigée le 02/11 : le forfait ne vend
 * que la <b>main-d'œuvre</b>. Tout ce que l'article 5 du contrat exclut — la pièce
 * fournie par Saphir, le consommable, le déplacement, le travail correctif, la
 * prestation en plus — se chiffre au client, et le devis est le seul papier qui demande
 * son accord. Un palier dû a donc DROIT à un devis. Ce que la base doit montrer :
 *
 * — le devis porte le forfait et le palier dont il facture le supplément ;
 * — le devis ne décompte pas le palier : livré, il laisse la visite à faire. Sans cette
 *   garde, la facture d'une plaquette éteindrait le rappel d'une visite non faite ;
 * — le papier qui coche est celui qui travaille : l'ordre né du devis, une fois livré,
 *   tient le palier à DONE ;
 * — et la garde contre la double facture tient pour les papiers de travail : un second
 *   ordre sur le même palier se refuse et nomme le premier.
 *
 * Le forfait est posé pour l'essai, sur un bus qui existe déjà. La transaction est
 * annulée par Spring : sa base retrouve ses paliers et ses papiers tels qu'avant.
 */
@SpringBootTest
@Transactional
class LeDevisNommeLePalierEnBaseTest {

    @Autowired private SoldPlanService forfaits;
    @Autowired private MaintenanceChainService chaine;
    @Autowired private MaintenancePlanRepository plans;
    @Autowired private SoldPlanRepository soldPlans;
    @Autowired private MaintenanceRepository documents;
    @Autowired private VehicleRepository vehicles;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "chef@higer.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Ce que l'essai tient en main : le forfait vendu, la visite qu'il doit, et sa ligne de suivi. */
    private record Essai(SoldPlan vendu, MaintenancePlanStep palier, SoldPlanStep suivi) {}

    /**
     * Un forfait vendu sur un bus qui existe, avec deux paliers à faire. On pose sa
     * propre grille : un essai qui s'appuierait sur les données de l'atelier mesurerait
     * ce qu'il n'a pas écrit.
     */
    private Essai forfaitNeufAvecDeuxPaliers() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à vendre.");
        Vehicle choisi = bus.get(0);

        String marque = UUID.randomUUID().toString().substring(0, 8);
        MaintenancePlan grille = new MaintenancePlan();
        grille.setId("mpl-essai-" + marque);
        grille.setName("Essai devis sur palier " + marque);
        grille.setBusModel("Higer City");
        grille.setCreatedAt(LocalDateTime.now());

        MaintenancePlanStep dixMille = new MaintenancePlanStep();
        dixMille.setId("mps-essai-10-" + marque);
        dixMille.setPlan(grille);
        dixMille.setStepOrder(1);
        dixMille.setKm(10000L);
        dixMille.setLabel("visite des 10 000 km");

        MaintenancePlanStep quaranteCinq = new MaintenancePlanStep();
        quaranteCinq.setId("mps-essai-45-" + marque);
        quaranteCinq.setPlan(grille);
        quaranteCinq.setStepOrder(2);
        quaranteCinq.setKm(45000L);
        quaranteCinq.setLabel("visite des 45 000 km");

        grille.getSteps().add(dixMille);
        grille.getSteps().add(quaranteCinq);
        plans.saveAndFlush(grille);

        SoldPlan vendu = new SoldPlan();
        vendu.setId("sp-essai-" + marque);
        vendu.setPlan(grille);
        vendu.setVehicleId(choisi.getId());
        vendu.setClientId(choisi.getClientId());
        vendu.setStartDate(LocalDate.now().minusMonths(1));
        vendu.setStatus(SoldPlanStatus.ACTIVE);
        vendu.setCreatedAt(LocalDateTime.now());
        SoldPlanStep premier = new SoldPlanStep();
        premier.setId("sps-essai-10-" + marque);
        premier.setSoldPlan(vendu);
        premier.setStep(dixMille);
        premier.setStatus(SoldPlanStepStatus.TODO);
        SoldPlanStep second = new SoldPlanStep();
        second.setId("sps-essai-45-" + marque);
        second.setSoldPlan(vendu);
        second.setStep(quaranteCinq);
        second.setStatus(SoldPlanStepStatus.TODO);
        vendu.getSteps().add(premier);
        vendu.getSteps().add(second);
        soldPlans.saveAndFlush(vendu);
        return new Essai(vendu, quaranteCinq, second);
    }

    private MaintenanceItem papier(MaintenanceType type, MaintenanceStatus statut, Essai essai) {
        MaintenanceItem item = new MaintenanceItem();
        item.setId("maint-essai-" + UUID.randomUUID().toString().substring(0, 12));
        item.setType(type);
        item.setNumber((type == MaintenanceType.DEVIS ? "DV-" : "OR-") + "ESS-"
                + UUID.randomUUID().toString().substring(0, 8));
        item.setVehicleId(essai.vendu().getVehicleId());
        item.setClientId(essai.vendu().getClientId());
        item.setDate(LocalDate.now());
        item.setStatus(statut);
        item.setSoldPlanId(essai.vendu().getId());
        item.setPlanStepId(essai.palier().getId());
        return documents.saveAndFlush(item);
    }

    /** La ligne de suivi relue en SQL : c'est la colonne qui décide, pas le DTO. */
    private Map<String, Object> relireSuivi(String soldPlanStepId) {
        em.flush();
        return base.queryForMap("select status, maintenance_item_id from sold_plan_steps where id = ?",
                soldPlanStepId);
    }

    private Map<String, Object> relirePapier(String id) {
        em.flush();
        return base.queryForMap("select type, status, sold_plan_id, plan_step_id, source_item_id"
                + " from maintenance_items where id = ?", id);
    }

    /**
     * Le coeur de la correction : un devis qui nomme un palier dû passe. Le serveur
     * n'est plus la porte fermée d'hier matin, et les deux cases du lien tiennent.
     */
    @Test
    void un_devis_peut_nommer_le_palier_dont_il_facture_le_supplement() {
        Essai essai = forfaitNeufAvecDeuxPaliers();
        MaintenanceItem devis = papier(MaintenanceType.DEVIS, MaintenanceStatus.DRAFT, essai);

        forfaits.verifierRattachement(devis);

        Map<String, Object> ligne = relirePapier(devis.getId());
        assertEquals("DEVIS", ligne.get("type"));
        assertEquals(essai.vendu().getId(), ligne.get("sold_plan_id"), "le devis dit de quel forfait il sort");
        assertEquals(essai.palier().getId(), ligne.get("plan_step_id"),
                "et de quel palier il facture ce que la main-d'œuvre vendue ne couvre pas");
    }

    /**
     * Le devis ne décompte pas la visite : passé à « livré », il laisse le palier à
     * faire et n'écrit aucun papier dans la ligne de suivi.
     */
    @Test
    void livrer_un_devis_laisse_le_palier_a_faire() {
        Essai essai = forfaitNeufAvecDeuxPaliers();
        MaintenanceItem devis = papier(MaintenanceType.DEVIS, MaintenanceStatus.DELIVERED, essai);

        forfaits.appliquerSuivi(devis, null, null, jeton());

        Map<String, Object> ligne = relireSuivi(essai.suivi().getId());
        assertEquals("TODO", ligne.get("status"), "facturer une pièce ne fait pas la visite");
        assertNull(ligne.get("maintenance_item_id"), "le palier ne se réclame pas du devis");
    }

    /**
     * Le palier se coche au papier qui travaille : le devis accepté donne l'ordre — qui
     * garde le forfait et le palier — et c'est l'ordre livré qui tient le suivi à DONE.
     */
    @Test
    void l_ordre_ne_du_devis_est_le_papier_qui_coche_le_palier() {
        Essai essai = forfaitNeufAvecDeuxPaliers();
        MaintenanceItem devis = papier(MaintenanceType.DEVIS, MaintenanceStatus.VALIDATED, essai);

        MaintenanceItem ordre = chaine.ordreDepuisDevis(devis.getId(), jeton());

        assertEquals(essai.vendu().getId(), ordre.getSoldPlanId(), "l'ordre exécute la visite du forfait");
        assertEquals(essai.palier().getId(), ordre.getPlanStepId());
        assertEquals(devis.getId(), ordre.getSourceItemId(), "le lien d'origine s'écrit à la base, pas au clavier");

        ordre.setStatus(MaintenanceStatus.DELIVERED);
        documents.saveAndFlush(ordre);
        forfaits.appliquerSuivi(ordre, null, null, jeton());

        Map<String, Object> ligne = relireSuivi(essai.suivi().getId());
        assertEquals("DONE", ligne.get("status"));
        assertEquals(ordre.getId(), ligne.get("maintenance_item_id"), "c'est l'ordre qui a fait la visite");
    }

    /**
     * La garde de la double facture tient pour les papiers de travail : une seconde
     * visite du même palier se refuse, et le refus nomme l'ordre qui l'a déjà faite.
     */
    @Test
    void un_second_ordre_sur_le_meme_palier_se_refuse_et_nomme_le_premier() {
        Essai essai = forfaitNeufAvecDeuxPaliers();
        MaintenanceItem premier = papier(MaintenanceType.OPERATION, MaintenanceStatus.DELIVERED, essai);
        forfaits.appliquerSuivi(premier, null, null, jeton());
        MaintenanceItem second = papier(MaintenanceType.OPERATION, MaintenanceStatus.DRAFT, essai);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> forfaits.verifierRattachement(second));

        assertTrue(refus.getReason().contains(premier.getNumber()), refus.getReason());
        assertEquals("DONE", relireSuivi(essai.suivi().getId()).get("status"),
                "le refus n'a pas bougé le palier déjà tenu");
    }

    /** Un devis nomme un palier, mais un palier DE CE FORFAIT : une ligne d'un autre catalogue se refuse. */
    @Test
    void un_devis_ne_peut_pas_nommer_le_palier_d_une_autre_grille() {
        Essai essai = forfaitNeufAvecDeuxPaliers();
        MaintenanceItem devis = papier(MaintenanceType.DEVIS, MaintenanceStatus.DRAFT, essai);
        devis.setPlanStepId("mps-qui-n-existe-pas");
        documents.saveAndFlush(devis);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> forfaits.verifierRattachement(devis));

        assertTrue(refus.getReason().contains("n'appartient pas au forfait"), refus.getReason());
    }
}
