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

import tn.higer.sav_backend.dto.MaintenanceDtos.ClotureRequest;
import tn.higer.sav_backend.dto.OrdreDtos.EtatClotureDto;
import tn.higer.sav_backend.dto.OrdreDtos.FicheResponse;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.InterventionPurpose;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionIssue;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceTotals;
import tn.higer.sav_backend.service.OrdreService;
import tn.higer.sav_backend.service.SoldPlanService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L'ordre de travail se clôt dans la vraie base, et il y laisse ses preuves.
 *
 * V38 a donné à l'ordre ce qui lui manquait pour être un papier de travail et non un
 * devis recopié : le coche « fait » et les heures réelles sur chaque ligne, une date de
 * clôture, une raison écrite quand la photo n'est pas là. Ce que ces tests vérifient
 * dans PostgreSQL, et qu'aucun test à mocks ne peut voir :
 *
 * — les mots nouveaux passent par la base (le piège de V30 : la suite était verte pendant
 *   qu'un 500 attendait l'atelier sur « j'arrive ») ;
 * — le refus de clôture est écrit avec le motif exact, ligne par ligne, avant que
 *   n'importe quelle colonne bouge ;
 * — les heures se demandent sur le travail, pas sur la marchandise ;
 * — la preuve se relit là où elle a été posée : le cliché « après intervention » de la
 *   SORTIE d'origine, la fiche d'atelier jointe au papier, ou la raison écrite — la
 *   troisième part au journal avec son texte ;
 * — le palier du forfait se coche au clic qui clôture, et ne se coche pas deux fois :
 *   le second papier se refuse et nomme le premier ;
 * — le bon de livraison ne se pose que sur un ordre terminé, et laisse le palier à
 *   l'ordre qui a travaillé ;
 * — l'argent : un ordre nouveau ne porte plus de total, et les montants posés avant V38
 *   restent en base intacts.
 *
 * Chaque essai pose son propre bus, son propre forfait et ses propres papiers, marqués
 * d'un UUID. La transaction est annulée par Spring : sa base retrouve ses dossiers tels
 * qu'avant.
 */
@SpringBootTest
@Transactional
class LeOrdreSeClotEnBaseTest {

    @Autowired private OrdreService ordre;
    @Autowired private SoldPlanService forfaits;
    @Autowired private MaintenanceChainService chaine;
    @Autowired private MaintenanceRepository documents;
    @Autowired private MaintenanceAttachmentRepository piecesJointes;
    @Autowired private MaintenancePlanRepository plans;
    @Autowired private SoldPlanRepository soldPlans;
    @Autowired private InterventionRepository sorties;
    @Autowired private InterventionPhotoRepository photos;
    @Autowired private MissionRepository missions;
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

    private static String marque() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Le bus de l'essai : un bus qui a un client, comme partout où un forfait se vend. */
    private Vehicle bus() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à travailler.");
        return bus.get(0);
    }

    // -------------------------------------------------------------------------- les fabriques

    /** Ce que l'essai tient en main : le forfait vendu, la visite qu'il doit, et sa ligne de suivi. */
    private record Palier(SoldPlan vendu, MaintenancePlanStep step, SoldPlanStep suivi) {}

    /**
     * Un forfait vendu avec deux paliers à faire, posé pour l'essai. On écrit sa propre
     * grille : un essai qui s'appuierait sur les forfaits de l'atelier mesurerait ce
     * qu'il n'a pas écrit, et le nombre de paliers affichés viendrait d'ailleurs.
     */
    private Palier forfaitNeuf() {
        String marque = marque();
        Vehicle choisi = bus();

        MaintenancePlan grille = new MaintenancePlan();
        grille.setId("mpl-essai-" + marque);
        grille.setName("Essai clôture d'ordre " + marque);
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
        return new Palier(vendu, quaranteCinq, second);
    }

    private static MaintenanceDetail ligne(String reference, String libelle, LineNature nature,
                                           PartSupplier fournisseur, String prevu) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(libelle);
        d.setQuantity(1.0);
        d.setUnitPrice(0.0);
        d.setQuantityText(prevu);
        d.setNature(nature);
        d.setSuppliedBy(fournisseur);
        d.setDone(false);
        return d;
    }

    /**
     * Un ordre de travail de deux lignes — une opération et une pièce — brouillon, rien
     * de coché, aucune heure. C'est l'état d'un papier qu'on vient de créer depuis un
     * devis ou depuis la mission.
     */
    private MaintenanceItem ordreNeuf(Palier palier) {
        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("maint-essai-" + marque());
        papier.setType(MaintenanceType.OPERATION);
        papier.setNumber("OR-ESS-" + marque());
        papier.setVehicleId(bus().getId());
        papier.setClientId(bus().getClientId());
        papier.setDate(LocalDate.now());
        papier.setStatus(MaintenanceStatus.DRAFT);
        papier.setDetails(new ArrayList<>(List.of(
                ligne("MO-1", "Vidange et contrôle des niveaux", LineNature.LABOR, null, "2,0 h"),
                ligne("F-10", "Filtre à huile", LineNature.PART, PartSupplier.SAPHIR, "1"))));
        if (palier != null) {
            papier.setSoldPlanId(palier.vendu().getId());
            papier.setPlanStepId(palier.step().getId());
        }
        return documents.saveAndFlush(papier);
    }

    /** Tout cocher, et poser les heures réelles sur les lignes. {@code heures} nul laisse les cases vides. */
    private void cocherTout(MaintenanceItem papier, Double heures) {
        papier.getDetails().forEach(ligne -> {
            ligne.setDone(true);
            ligne.setRealHours(heures);
        });
        documents.saveAndFlush(papier);
    }

    /** Une sortie d'atelier, celle dont l'ordre est peut-être né. */
    private Intervention sortieNeuve(MaintenanceItem papier) {
        Intervention sortie = new Intervention();
        sortie.setId("int-essai-" + marque());
        sortie.setNumber("DP-ESS-" + marque());
        sortie.setStatus(InterventionStatus.RETURNED);
        sortie.setPurpose(InterventionPurpose.ENTRETIEN);
        sortie.setVehicleId(papier.getVehicleId());
        sortie.setClientId(papier.getClientId());
        sortie.setMaintenanceItemId(papier.getId());
        sortie.setReportedAt(LocalDateTime.now().minusHours(3));
        sortie.setCreatedAt(LocalDateTime.now().minusHours(3));
        return sorties.saveAndFlush(sortie);
    }

    private void verserPhoto(Intervention sortie, PhotoAngle angle) {
        InterventionPhoto photo = new InterventionPhoto();
        photo.setId("ph-essai-" + marque());
        photo.setInterventionId(sortie.getId());
        photo.setAngle(angle);
        photo.setFilePath("essai/" + sortie.getId() + "/" + angle.name().toLowerCase() + ".jpg");
        photo.setSha256("0".repeat(64));
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(2048L);
        photo.setUploadedAt(LocalDateTime.now());
        photo.setActorEmail("aya@higer.tn");
        photos.saveAndFlush(photo);
    }

    /** La fiche d'atelier signée, jointe au papier : le registre suffit, le fichier se prouve ailleurs. */
    private void joindreLaFicheSignee(MaintenanceItem papier) {
        MaintenanceAttachment piece = new MaintenanceAttachment();
        piece.setId("att-essai-" + marque());
        piece.setMaintenanceItemId(papier.getId());
        piece.setOriginalName("fiche-atelier-signee.pdf");
        piece.setContentType("application/pdf");
        piece.setSizeBytes(1024L);
        piece.setStoragePath("essai/" + papier.getId() + "/fiche.pdf");
        piece.setSha256("0".repeat(64));
        piece.setUploadedBy("chef@higer.tn");
        piece.setUploadedAt(LocalDateTime.now());
        piecesJointes.saveAndFlush(piece);
    }

    private Mission missionNeuve(MaintenanceItem papier, Intervention sortie) {
        Mission m = new Mission();
        m.setId("mt-essai-" + marque());
        m.setNumber("MT-ESS-" + marque());
        m.setState(MissionState.EN_COURS);
        m.setIssue(MissionIssue.ATELIER);
        m.setInterventionId(sortie.getId());
        m.setMaintenanceItemId(papier.getId());
        m.setCreatedAt(LocalDateTime.now());
        return missions.saveAndFlush(m);
    }

    // ---------------------------------------------------------------------------- relus en SQL

    /** La colonne décide, pas le DTO : sans flush, Hibernate n'aurait rien envoyé. */
    private Map<String, Object> relirePapier(String id) {
        em.flush();
        return base.queryForMap("select status, closed_on, closure_reason, anomalies, totalht, totalttc,"
                + " sold_plan_id, plan_step_id, source_item_id from maintenance_items where id = ?", id);
    }

    private Map<String, Object> relireSuivi(String soldPlanStepId) {
        em.flush();
        return base.queryForMap("select status, maintenance_item_id from sold_plan_steps where id = ?",
                soldPlanStepId);
    }

    private boolean datePoseeAUjourhui(String id) {
        em.flush();
        return Boolean.TRUE.equals(base.queryForObject(
                "select coalesce(closed_on = current_date, false) from maintenance_items where id = ?",
                Boolean.class, id));
    }

    private String detailDuJournal(String id, String morceau) {
        em.flush();
        List<String> lignes = base.queryForList(
                "select detail from audit_logs where entity_id = ? and detail like ?", String.class,
                id, "%" + morceau + "%");
        return lignes.isEmpty() ? null : lignes.get(0);
    }

    private int lignesCochesEnBase(String papierId) {
        em.flush();
        Integer n = base.queryForObject(
                "select count(*) from maintenance_details where maintenance_id = ? and done is true",
                Integer.class, papierId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------ les essais

    /**
     * Rien de coché : le refus nomme la tâche, et aucune colonne de la clôture n'est
     * écrite. Un refus muet laisserait l'atelier relire son papier ligne par ligne. La pièce
     * ne se coche pas : elle se pose, et elle n'est donc jamais réclamée à la clôture.
     */
    @Test
    void cloturer_sans_rien_cocher_refuse_et_nomme_chaque_ligne() {
        MaintenanceItem papier = ordreNeuf(forfaitNeuf());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton()));

        String motif = refus.getReason();
        assertTrue(motif.contains("Vidange et contrôle des niveaux"), motif);
        assertFalse(motif.contains("Filtre à huile"), "une pièce n'est pas une tâche à cocher : " + motif);
        assertTrue(motif.contains("non cochée"), motif);
        assertEquals("DRAFT", relirePapier(papier.getId()).get("status"), "le refus ne fait pas avancer le papier");
        assertNull(relirePapier(papier.getId()).get("closed_on"), "et n'écrit aucune date de clôture");
        assertEquals(0, lignesCochesEnBase(papier.getId()));
    }

    /**
     * Les heures se demandent sur le travail, pas sur la marchandise : la ligne du
     * catalogue qui porte une pièce n'a pas de temps, elle a une quantité. Le refus ne
     * réclame donc des heures que sur l'opération — et il nomme le palier que ce papier
     * doit acquitter, ce qui explique à l'atelier POURQUOI le temps est exigé ici.
     */
    @Test
    void les_heures_se_demandent_sur_operation_pas_sur_la_piece() {
        Palier palier = forfaitNeuf();
        MaintenanceItem papier = ordreNeuf(palier);
        verserPhoto(sortieNeuve(papier), PhotoAngle.AFTER);
        cocherTout(papier, null);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton()));

        String motif = refus.getReason();
        assertTrue(motif.contains("heures réelles manquent"), motif);
        assertTrue(motif.contains("Vidange et contrôle des niveaux"), motif);
        assertFalse(motif.contains("heures réelles manquent sur « Filtre à huile"), motif);
        assertTrue(motif.contains("visite des 45 000 km"), motif);
    }

    /**
     * Une ligne SANS nature — les six ordres nés avant V36 dans la base de l'atelier —
     * ne réclame pas d'heures : seule la main-d'œuvre nommée donne le temps. Sans cette
     * règle, une visite déjà finie resterait bloquée sur un temps que personne n'a jamais
     * saisi, et l'atelier annulerait un papier juste pour le refermer.
     */
    @Test
    void une_ligne_sans_nature_ne_reclame_pas_d_heures() {
        Palier palier = forfaitNeuf();
        MaintenanceItem papier = ordreNeuf(palier);
        papier.getDetails().forEach(ligne -> ligne.setNature(null));
        documents.saveAndFlush(papier);
        verserPhoto(sortieNeuve(papier), PhotoAngle.AFTER);
        cocherTout(papier, null);

        EtatClotureDto etat = ordre.etatDeCloture(papier.getId());
        assertTrue(etat.heuresManquantes().isEmpty(), etat.ceQuiManque().toString());
        assertTrue(etat.pret(), etat.message());
        assertEquals("COMPLETED", ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton())
                .getStatus().name());
        assertEquals("DONE", relireSuivi(palier.suivi().getId()).get("status"),
                "le palier s'acquitte sans qu'un temps ait été tapé sur du vide");
    }

    /**
     * La preuve est la dernière porte : sans photo de la sortie, sans fiche jointe et
     * sans raison écrite, le papier reste où il était, même cochée et horodatée ligne par
     * ligne. Le refus dit QUELLE photo manque et où la trouver.
     */
    @Test
    void cloturer_sans_preuve_refuse_en_disant_quoi_apporter() {
        MaintenanceItem papier = ordreNeuf(forfaitNeuf());
        Intervention sortie = sortieNeuve(papier);
        cocherTout(papier, 2.0);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton()));

        String motif = refus.getReason();
        assertTrue(motif.contains("aucune preuve"), motif);
        assertTrue(motif.contains("après intervention"), motif);
        assertTrue(motif.contains(sortie.getNumber()), motif);
        assertTrue(motif.contains("fiche d'atelier"), motif);
        assertNull(relirePapier(papier.getId()).get("closed_on"), motif);
    }

    /**
     * Le chemin de l'atelier, tel que le jeu l'a fait le 02/11 : l'ordre reprend la
     * photo « après intervention » déjà versée sur la SORTIE — personne ne rephotographie
     * un bus rentré. Le clic écrit l'état, la date du jour, et coche le palier au nom de
     * ce papier.
     */
    @Test
    void la_photo_apres_de_la_sortie_d_origine_clot_l_ordre_et_coche_le_palier() {
        Palier palier = forfaitNeuf();
        MaintenanceItem papier = ordreNeuf(palier);
        verserPhoto(sortieNeuve(papier), PhotoAngle.AFTER);
        cocherTout(papier, 2.0);

        MaintenanceItem cloture = ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton());

        assertEquals("COMPLETED", cloture.getStatus().name());
        Map<String, Object> lu = relirePapier(papier.getId());
        assertEquals("COMPLETED", lu.get("status"));
        assertTrue(datePoseeAUjourhui(papier.getId()), "la date de clôture est celle du clic");
        assertNull(lu.get("closure_reason"), "la photo dispense d'écrire une raison");
        Map<String, Object> suivi = relireSuivi(palier.suivi().getId());
        assertEquals("DONE", suivi.get("status"), "le palier s'acquitte au papier qui a travaillé");
        assertEquals(papier.getId(), suivi.get("maintenance_item_id"));
        assertNotNull(detailDuJournal(papier.getId(), "clôturé"), "la clôture part au journal");
        assertTrue(detailDuJournal(papier.getId(), "clôturé").contains("après intervention"));
    }

    /**
     * La troisième porte de la preuve : une raison écrite. Elle remplace la photo, elle
     * reste sur le papier dans sa colonne, et le journal la relit — c'est ce qui permet
     * d'en retrouver la trace six mois après, quand le client conteste.
     */
    @Test
    void une_raison_ecrite_tient_lieu_de_preuve_et_le_journal_la_porte() {
        MaintenanceItem papier = ordreNeuf(null);
        cocherTout(papier, null);

        MaintenanceItem cloture = ordre.cloturer(papier.getId(),
                new ClotureRequest("Bus rentré de nuit, photo impossible à reprendre"), jeton());

        assertEquals("COMPLETED", cloture.getStatus().name());
        assertEquals("Bus rentré de nuit, photo impossible à reprendre",
                relirePapier(papier.getId()).get("closure_reason"));
        assertNotNull(detailDuJournal(papier.getId(), "Bus rentré de nuit"), "la raison est tracée au journal");
    }

    /**
     * La fiche d'atelier signée jointe au papier est la deuxième porte, et un ordre hors
     * forfait ne réclame aucune heure : le temps passé ne décompte rien puisqu'aucun
     * palier n'est engagé.
     */
    @Test
    void la_fiche_signee_suffit_et_un_ordre_hors_forfait_ne_reclame_pas_d_heures() {
        MaintenanceItem papier = ordreNeuf(null);
        cocherTout(papier, null);
        joindreLaFicheSignee(papier);

        EtatClotureDto etat = ordre.etatDeCloture(papier.getId());
        assertTrue(etat.pret(), etat.message());
        assertTrue(etat.justificatif().ficheSigneeJointe());
        assertTrue(etat.heuresManquantes().isEmpty(), "hors forfait, le temps n'est pas exigé");

        assertEquals("COMPLETED", ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton())
                .getStatus().name());
    }

    /**
     * Le palier ne se compte pas deux fois : le second papier qui prétend l'avoir fait
     * se refuse, et le refus nomme l'autre ordre. Aucun mot de la clôture n'est écrit sur
     * celui qui échoue.
     */
    @Test
    void cloturer_un_ordre_dont_le_palier_est_deja_tenu_nomme_l_autre_papier() {
        Palier palier = forfaitNeuf();
        MaintenanceItem premier = ordreNeuf(palier);
        verserPhoto(sortieNeuve(premier), PhotoAngle.AFTER);
        cocherTout(premier, 2.0);
        ordre.cloturer(premier.getId(), new ClotureRequest(null), jeton());

        MaintenanceItem second = ordreNeuf(palier);
        verserPhoto(sortieNeuve(second), PhotoAngle.AFTER);
        cocherTout(second, 2.0);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ordre.cloturer(second.getId(), new ClotureRequest(null), jeton()));

        assertEquals(409, refus.getStatusCode().value(), refus.getReason());
        assertTrue(refus.getReason().contains(premier.getNumber()), refus.getReason());
        assertEquals("DRAFT", relirePapier(second.getId()).get("status"), "le refus laisse le papier à faire");
        assertNull(relirePapier(second.getId()).get("closed_on"));
        // Le papier qui se refuse garde la référence du palier qu'il visait : le détacher
        // effacerait la demande de l'atelier, et le rappel ressortirait comme si de rien.
        assertEquals(palier.vendu().getId(), relirePapier(second.getId()).get("sold_plan_id"),
                "le refus ne délie pas le papier de son forfait");
        assertEquals(palier.step().getId(), relirePapier(second.getId()).get("plan_step_id"),
                "ni de son palier");
        assertEquals(premier.getId(), relireSuivi(palier.suivi().getId()).get("maintenance_item_id"),
                "et le palier reste tenu par le premier papier");
    }

    /**
     * L'écran colore, le serveur décide : le même calcul se lit avant le clic (ce qui
     * manque, ligne par ligne), puis après (le bouton ne se retire pas tout seul).
     */
    @Test
    void l_etat_de_cloture_dit_avant_le_clic_ce_qui_manque_puis_que_c_est_fait() {
        MaintenanceItem papier = ordreNeuf(forfaitNeuf());

        EtatClotureDto d_abord = ordre.etatDeCloture(papier.getId());
        assertFalse(d_abord.pret(), d_abord.message());
        assertEquals(1, d_abord.operationsNonCochees().size(),
                "seule la tâche se coche, pas la pièce : " + d_abord.ceQuiManque());
        assertFalse(d_abord.justificatif().photoApresDeLaSortie());

        Intervention sortie = sortieNeuve(papier);
        cocherTout(papier, 2.0);
        verserPhoto(sortie, PhotoAngle.AFTER);

        EtatClotureDto ensuite = ordre.etatDeCloture(papier.getId());
        assertTrue(ensuite.pret(), ensuite.message());
        assertEquals(sortie.getNumber(), ensuite.justificatif().numeroSortie(),
                "la preuve se relit sur la sortie, pas dans le papier");

        ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton());

        EtatClotureDto apres = ordre.etatDeCloture(papier.getId());
        assertFalse(apres.pret(), "un ordre clos ne se clôt pas une seconde fois");
        assertEquals("Ordre déjà clos", apres.message());
    }

    /**
     * Le bon de livraison naît de la clôture. Tant que l'ordre est « À faire », le bus
     * n'est pas fini : rendre un bus qu'on n'a pas terminé se paie au démarrage du
     * client, et le bouton de la maquette est grisé pour ça.
     */
    @Test
    void le_bon_de_livraison_ne_se_pose_que_sur_un_ordre_cloture() {
        MaintenanceItem papier = ordreNeuf(forfaitNeuf());
        verserPhoto(sortieNeuve(papier), PhotoAngle.AFTER);
        cocherTout(papier, 2.0);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> chaine.bonLivraisonDepuisOrdre(papier.getId(), jeton()));
        assertTrue(refus.getReason().contains("n'est pas terminé"), refus.getReason());
        assertTrue(refus.getReason().contains("À faire"), refus.getReason());

        ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton());
        MaintenanceItem bl = chaine.bonLivraisonDepuisOrdre(papier.getId(), jeton());

        assertEquals(papier.getId(), bl.getSourceItemId());
        assertNull(bl.getSoldPlanId(), "le palier reste à l'ordre qui a travaillé");
        assertNull(bl.getPlanStepId());
        assertNotNull(relirePapier(papier.getId()).get("closed_on"),
                "et le bon de livraison n'efface pas la clôture de l'ordre");
        assertTrue(datePoseeAUjourhui(papier.getId()));
    }

    /**
     * Les trois gardes du formulaire, écrites sur le papier et non sur la case état : un
     * ordre ne naît pas terminé, « À faire » et « En cours » se règlent à l'écran parce
     * qu'ils se voient sur le bus, « Terminé » ne se tape pas, et un ordre clos ne se
     * réécrit plus — sauf à s'annuler, la seule porte qui libère le palier quand le
     * papier s'est trompé de visite.
     */
    @Test
    void un_ordre_ne_nait_pas_termine_et_un_ordre_clos_ne_se_reecrit_plus() {
        ordre.verifierNaissance(MaintenanceType.OPERATION, null);
        ordre.verifierNaissance(MaintenanceType.OPERATION, MaintenanceStatus.IN_PROGRESS);
        ResponseStatusException naissance = assertThrows(ResponseStatusException.class,
                () -> ordre.verifierNaissance(MaintenanceType.OPERATION, MaintenanceStatus.DELIVERED));
        assertTrue(naissance.getReason().contains("ne naît pas"), naissance.getReason());

        MaintenanceItem papier = ordreNeuf(null);
        cocherTout(papier, null);
        joindreLaFicheSignee(papier);

        // Le cours du travail se règle à l'écran : « en cours » se constate sur le bus.
        ordre.verifierModification(papier, MaintenanceStatus.IN_PROGRESS);
        // « Terminé », lui, ne se tape pas : ce serait une visite sans preuve.
        ResponseStatusException termine_tape = assertThrows(ResponseStatusException.class,
                () -> ordre.verifierModification(papier, MaintenanceStatus.COMPLETED));
        assertTrue(termine_tape.getReason().contains("ne se tape pas"), termine_tape.getReason());
        // Un état du devis sur un ordre de travail se refuse et le dit.
        ResponseStatusException etat_de_devis = assertThrows(ResponseStatusException.class,
                () -> ordre.verifierModification(papier, MaintenanceStatus.VALIDATED));
        assertTrue(etat_de_devis.getReason().contains("n'est pas un état d'un ordre"), etat_de_devis.getReason());

        ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton());

        ResponseStatusException retouche = assertThrows(ResponseStatusException.class,
                () -> ordre.verifierModification(papier, null));
        assertTrue(retouche.getReason().contains("ne se réécrivent plus"), retouche.getReason());
        ordre.verifierModification(papier, MaintenanceStatus.CANCELLED);
    }

    /** Un ordre annulé ne reprend pas de travail : deux papiers au même palier paieraient deux fois. */
    @Test
    void un_ordre_annule_ne_redevient_pas_en_cours() {
        MaintenanceItem papier = ordreNeuf(null);
        papier.setStatus(MaintenanceStatus.CANCELLED);
        documents.saveAndFlush(papier);

        ordre.verifierModification(papier, null);
        ordre.verifierModification(papier, MaintenanceStatus.CANCELLED);
        ResponseStatusException reprise = assertThrows(ResponseStatusException.class,
                () -> ordre.verifierModification(papier, MaintenanceStatus.IN_PROGRESS));

        assertTrue(reprise.getReason().contains("est annulé"), reprise.getReason());
    }

    /**
     * Un ordre nouveau ne chiffre pas ; un ordre d'avant garde ses montants. Le portail
     * n'efface pas ce qu'il a écrit, il cesse d'en écrire — et le devis signé reste le
     * seul papier qui porte un prix.
     */
    @Test
    void l_ordre_ne_chiffre_plus_et_ceux_d_avant_gardent_leurs_montants() {
        MaintenanceItem papier = ordreNeuf(null);
        papier.setTotalHT(120.0);
        papier.setTotalTTC(142.8);
        documents.saveAndFlush(papier);

        MaintenanceTotals.appliquer(papier, 19);
        documents.saveAndFlush(papier);

        Map<String, Object> lu = relirePapier(papier.getId());
        assertEquals(120.0, (Double) lu.get("totalht"), 0.0001, "les montants posés avant V38 restent lisibles");
        assertEquals(142.8, (Double) lu.get("totalttc"), 0.0001);

        MaintenanceItem neuf = ordreNeuf(null);
        MaintenanceTotals.appliquer(neuf, 19);
        documents.saveAndFlush(neuf);
        assertNull(relirePapier(neuf.getId()).get("totalht"), "un ordre d'aujourd'hui ne porte pas de total");
    }

    /**
     * La fiche rend ce que le papier ne sait pas tout seul : le devis dont il est né, la
     * mission qui l'a fait ouvrir, la sortie dont il reprend les clichés, et le forfait
     * avec ses paliers. Tout vient du serveur — l'écran ne devine aucune origine.
     */
    @Test
    void la_fiche_nomme_le_devis_la_mission_la_sortie_et_ses_photos() {
        Palier palier = forfaitNeuf();
        MaintenanceItem devis = new MaintenanceItem();
        devis.setId("maint-essai-devis-" + marque());
        devis.setType(MaintenanceType.DEVIS);
        devis.setNumber("DV-ESS-" + marque());
        devis.setVehicleId(bus().getId());
        devis.setClientId(bus().getClientId());
        devis.setDate(LocalDate.now());
        devis.setStatus(MaintenanceStatus.VALIDATED);
        devis.setDetails(new ArrayList<>(List.of(
                ligne("MO-1", "Vidange et contrôle des niveaux", LineNature.LABOR, null, "2,0 h"),
                ligne("F-10", "Filtre à huile", LineNature.PART, PartSupplier.SAPHIR, "1"))));
        documents.saveAndFlush(devis);

        MaintenanceItem papier = chaine.ordreDepuisDevis(devis.getId(), jeton());
        papier.setSoldPlanId(palier.vendu().getId());
        papier.setPlanStepId(palier.step().getId());
        documents.saveAndFlush(papier);
        Intervention sortie = sortieNeuve(papier);
        Mission mission = missionNeuve(papier, sortie);
        // Le clic d'une mission écrit les deux moitiés du lien : le papier porte la
        // demande qui l'a ouvert, la demande porte le papier qu'elle a fait naître.
        papier.setMissionId(mission.getId());
        documents.saveAndFlush(papier);
        verserPhoto(sortie, PhotoAngle.BEFORE);
        verserPhoto(sortie, PhotoAngle.AFTER);

        FicheResponse fiche = ordre.fiche(papier.getId());

        assertEquals(devis.getNumber(), fiche.origine().numeroSource());
        assertEquals("Devis", fiche.origine().typeSource());
        assertEquals(devis.getNumber(), fiche.origine().numeroDevis());
        assertEquals(mission.getNumber(), fiche.origine().numeroMission());
        assertEquals(sortie.getNumber(), fiche.origine().numeroSortie());
        assertEquals(2, fiche.photosSortie().size(), "les clichés de la sortie, pas d'autres");
        assertEquals("Ordre de travail", fiche.papier().typeLabel());
        assertEquals("À faire", fiche.papier().statusLabel(), "le mot de l'ordre, pas celui du devis");
        assertEquals(2, fiche.papier().details().size(), "les lignes du devis se repostent sur l'ordre");
        assertEquals(2, fiche.forfait().paliersTotal());
        assertEquals("visite des 45 000 km", fiche.forfait().labelPalier());
        assertEquals("À faire", fiche.forfait().etatDuPalier());
        assertFalse(fiche.cloture().pret(), "rien n'est coché : le clic refuserait");
        assertTrue(fiche.piecesPosees().isEmpty());
    }

    /**
     * Un ordre sans ligne est le pire des papiers : l'écran ne montre rien à cocher, et
     * une clôture sans case cochée serait une visite déclarée par personne. Le refus
     * comme la lecture disent la même chose — « l'écran dit prêt, le serveur refuse »
     * est un piège déjà payé sur ce portail.
     */
    @Test
    void un_ordre_sans_ligne_ne_pretend_pas_etre_pret_a_cloturer() {
        MaintenanceItem papier = ordreNeuf(null);
        papier.getDetails().clear();
        documents.saveAndFlush(papier);
        joindreLaFicheSignee(papier);

        EtatClotureDto lu = ordre.etatDeCloture(papier.getId());
        assertFalse(lu.pret(), lu.message());
        assertTrue(lu.ceQuiManque().toString().contains("aucune opération"), lu.ceQuiManque().toString());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton()));
        assertTrue(refus.getReason().contains("aucune opération"), refus.getReason());
    }

    /**
     * Un devis ou un bon de livraison ne se clôturent pas par cette porte : le clic est
     * réservé au papier qui dit que l'atelier a travaillé, et le refus nomme le papier
     * plutôt qu'un identifiant.
     */
    @Test
    void la_cloture_ne_se_demande_pas_sur_un_devis() {
        MaintenanceItem devis = new MaintenanceItem();
        devis.setId("maint-essai-devis-" + marque());
        devis.setType(MaintenanceType.DEVIS);
        devis.setNumber("DV-ESS-" + marque());
        devis.setVehicleId(bus().getId());
        devis.setClientId(bus().getClientId());
        devis.setDate(LocalDate.now());
        devis.setStatus(MaintenanceStatus.DRAFT);
        documents.saveAndFlush(devis);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ordre.cloturer(devis.getId(), new ClotureRequest("raison"), jeton()));
        assertTrue(refus.getReason().contains("n'est pas un ordre de travail"), refus.getReason());
        assertEquals("DRAFT", relirePapier(devis.getId()).get("status"),
                "le devis reste où il était : il n'a pas d'état de clôture");
    }
}
