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
import tn.higer.sav_backend.dto.FactureDtos.AFacturerDto;
import tn.higer.sav_backend.dto.FactureDtos.EncaissementRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureEmissionRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.SignatureRequest;
import tn.higer.sav_backend.model.Facture;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.EncaissementRepository;
import tn.higer.sav_backend.repository.FactureRepository;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.BonLivraisonService;
import tn.higer.sav_backend.service.FactureService;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceTotals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La facture naît du bon de livraison signé dans la vraie base, et l'argent qui
 * rentre s'y note jusqu'à solder la créance (I01, I02).
 *
 * V48 ajoute ce qui manquait entre le bus rendu et l'argent reçu. Ce que ces essais
 * vérifient en PostgreSQL, et qu'aucun test à mocks ne peut voir :
 *
 * — la facture reprend le montant du devis accepté en amont (BL → ordre → devis),
 *   ligne à ligne, et les totaux se recalculent serveur avec la TVA ;
 * — un BL ne donne qu'une facture : le second geste est refusé et nomme la première ;
 * — on ne facture ni un BL non signé, ni un BL sans devis accepté — aucun prix inventé ;
 * — l'encaissement est borné au restant dû, et l'état passe émise → partielle → payée ;
 * — une facture soldée n'attend plus rien, un versement de trop se refuse ;
 * — la liste « à facturer » (I02) rend le BL signé sans facture, et l'efface une fois émise ;
 * — le compte qui n'a pas droit aux chiffres lit la facture sans ses montants ;
 * — les colonnes neuves passent par la base, et le journal porte l'émission et l'encaissement.
 *
 * Chaque essai pose ses propres papiers marqués d'un UUID ; la transaction est annulée
 * par Spring, la base retrouve ses dossiers tels qu'avant.
 */
@SpringBootTest
@Transactional
class LaFactureNaitDuBonSigneEnBaseTest {

    @Autowired private FactureService factures;
    @Autowired private BonLivraisonService bons;
    @Autowired private MaintenanceChainService chaine;
    @Autowired private MaintenanceRepository documents;
    @Autowired private MaintenanceAttachmentRepository piecesJointes;
    @Autowired private FactureRepository factureRepo;
    @Autowired private EncaissementRepository encaissementRepo;
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
                .claim("email", "guichet@higer.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static String marque() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Vehicle bus() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à facturer.");
        return bus.get(0);
    }

    // -------------------------------------------------------------------------- les fabriques

    private static MaintenanceDetail ligneDevis(String reference, String libelle, double quantite,
                                                double prix, LineNature nature, PartSupplier fournisseur) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(libelle);
        d.setQuantity(quantite);
        d.setUnitPrice(prix);
        d.setNature(nature);
        d.setSuppliedBy(fournisseur);
        return d;
    }

    /** Un devis accepté : 3 h à 50 + une pièce Saphir à 200 + une pièce client à 0 = 350 HT. */
    private MaintenanceItem devisAccepte() {
        MaintenanceItem devis = new MaintenanceItem();
        devis.setId("maint-essai-" + marque());
        devis.setType(MaintenanceType.DEVIS);
        devis.setNumber("DV-ESS-" + marque());
        devis.setVehicleId(bus().getId());
        devis.setClientId(bus().getClientId());
        devis.setDate(LocalDate.now());
        devis.setStatus(MaintenanceStatus.VALIDATED);
        devis.setAcceptedOn(LocalDate.now());
        devis.setDetails(new ArrayList<>(List.of(
                ligneDevis("MO-1", "Main-d'œuvre pompe", 3.0, 50.0, LineNature.LABOR, null),
                ligneDevis("F-21", "Pompe à carburant", 1.0, 200.0, LineNature.PART, PartSupplier.SAPHIR),
                ligneDevis("CL-3", "Pièce fournie par le client", 1.0, 0.0, LineNature.PART, PartSupplier.CLIENT))));
        MaintenanceTotals.appliquer(devis, 19);
        documents.saveAndFlush(devis);
        return devis;
    }

    private void joindreLeScan(MaintenanceItem bl) {
        MaintenanceAttachment piece = new MaintenanceAttachment();
        piece.setId("att-essai-" + marque());
        piece.setMaintenanceItemId(bl.getId());
        piece.setOriginalName("bon-signe.jpg");
        piece.setContentType("image/jpeg");
        piece.setSizeBytes(3072L);
        piece.setStoragePath("essai/" + bl.getId() + "/scan.jpg");
        piece.setSha256("0".repeat(64));
        piece.setUploadedBy("guichet@higer.tn");
        piece.setUploadedAt(LocalDateTime.now());
        piecesJointes.saveAndFlush(piece);
    }

    /** La chaîne complète : devis accepté → ordre terminé → BL édité, scan joint, client signataire. */
    private MaintenanceItem bonSigneAvecDevis() {
        MaintenanceItem devis = devisAccepte();
        MaintenanceItem ordre = documents.findById(chaine.ordreDepuisDevis(devis.getId(), jeton()).getId())
                .orElseThrow();
        ordre.setStatus(MaintenanceStatus.COMPLETED);
        documents.saveAndFlush(ordre);
        MaintenanceItem bl = documents.findById(chaine.bonLivraisonDepuisOrdre(ordre.getId(), jeton()).getId())
                .orElseThrow();
        joindreLeScan(bl);
        bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", "Responsable dépôt", null), jeton());
        return documents.findById(bl.getId()).orElseThrow();
    }

    /** Un BL signé créé à la main, sans devis en amont : il n'a aucun montant accepté à facturer. */
    private MaintenanceItem bonSigneSansDevis() {
        MaintenanceItem bl = new MaintenanceItem();
        bl.setId("maint-essai-" + marque());
        bl.setType(MaintenanceType.BL);
        bl.setNumber("BL-ESS-" + marque());
        bl.setVehicleId(bus().getId());
        bl.setClientId(bus().getClientId());
        bl.setDate(LocalDate.now());
        bl.setStatus(MaintenanceStatus.DRAFT);
        documents.saveAndFlush(bl);
        joindreLeScan(bl);
        bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton());
        return documents.findById(bl.getId()).orElseThrow();
    }

    // ---------------------------------------------------------------------------- relu en SQL

    private Map<String, Object> relireFacture(String id) {
        em.flush();
        return base.queryForMap("select number, bl_id, devis_id, issued_on, due_on, total_ht, total_tva,"
                + " total_ttc, created_by from factures where id = ?", id);
    }

    private String detailDuJournal(String id, String morceau) {
        em.flush();
        List<String> lignes = base.queryForList(
                "select detail from audit_logs where entity_id = ? and detail like ?", String.class,
                id, "%" + morceau + "%");
        return lignes.isEmpty() ? null : lignes.get(0);
    }

    // ------------------------------------------------------------------------------ les essais

    @Test
    void la_facture_nait_du_bon_signe_et_reprend_le_devis_accepte() {
        MaintenanceItem bl = bonSigneAvecDevis();

        FactureResponse facture = factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true);

        assertTrue(facture.number().startsWith("FA-"), "le numéro suit la famille de l'année : " + facture.number());
        assertEquals(416.50, facture.totalTTC(), 0.0001, "350 HT × 1,19 : le montant du devis, pas un chiffre tapé");
        assertEquals(350.0, facture.totalHT(), 0.0001);
        assertEquals(66.50, facture.totalTVA(), 0.0001);
        assertEquals(3, facture.lines().size(), "les lignes du devis descendent sur la facture");
        assertEquals("emise", facture.etat(), "rien n'est encore rentré");
        assertEquals(0.0, facture.montantEncaisse(), 0.0001);
        assertEquals(416.50, facture.restantDu(), 0.0001);
        assertEquals(LocalDate.now().plusDays(30), facture.dueOn(), "l'échéance par défaut est un mois après l'émission");
        assertEquals(bl.getId(), facture.blId());
        assertNotNull(facture.devisId(), "la facture nomme le devis dont l'argent vient");

        Map<String, Object> lu = relireFacture(facture.id());
        assertEquals(facture.number(), lu.get("number"));
        assertEquals(bl.getId(), lu.get("bl_id"));
        assertEquals(416.50, ((Number) lu.get("total_ttc")).doubleValue(), 0.0001);
        assertEquals("guichet@higer.tn", lu.get("created_by"));
        assertNotNull(detailDuJournal(facture.id(), "émise depuis le bon"), "l'émission part au journal");
    }

    @Test
    void un_meme_bon_ne_donne_qu_une_facture() {
        MaintenanceItem bl = bonSigneAvecDevis();
        FactureResponse premiere = factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true);

        ResponseStatusException seconde = assertThrows(ResponseStatusException.class,
                () -> factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true));

        assertTrue(seconde.getReason().contains("déjà donné la facture"), seconde.getReason());
        assertTrue(seconde.getReason().contains(premiere.number()), "le refus nomme la facture déjà émise");
        assertEquals(1, factureRepo.findAllBlIds().stream().filter(id -> id.equals(bl.getId())).count());
    }

    @Test
    void facturer_un_bon_non_signe_se_refuse() {
        MaintenanceItem devis = devisAccepte();
        MaintenanceItem ordre = documents.findById(chaine.ordreDepuisDevis(devis.getId(), jeton()).getId()).orElseThrow();
        ordre.setStatus(MaintenanceStatus.COMPLETED);
        documents.saveAndFlush(ordre);
        MaintenanceItem bl = documents.findById(chaine.bonLivraisonDepuisOrdre(ordre.getId(), jeton()).getId()).orElseThrow();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true));

        assertTrue(refus.getReason().contains("n'est pas signé"), refus.getReason());
        assertTrue(factureRepo.findAllBlIds().stream().noneMatch(id -> id.equals(bl.getId())));
    }

    @Test
    void facturer_un_bon_signe_sans_devis_se_refuse_sans_prix_invente() {
        MaintenanceItem bl = bonSigneSansDevis();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true));

        assertTrue(refus.getReason().contains("pas de devis en amont"), refus.getReason());
    }

    @Test
    void une_echeance_avant_l_emission_se_refuse() {
        MaintenanceItem bl = bonSigneAvecDevis();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> factures.emettre(new FactureEmissionRequest(bl.getId(), LocalDate.now().minusDays(1)), jeton(), true));

        assertTrue(refus.getReason().contains("ne naît pas déjà en retard"), refus.getReason());
    }

    @Test
    void l_encaissement_fait_passer_partielle_puis_payee() {
        MaintenanceItem bl = bonSigneAvecDevis();
        FactureResponse facture = factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true);

        FactureResponse partielle = factures.encaisser(facture.id(),
                new EncaissementRequest(100.0, "espèces", null), jeton(), true);
        assertEquals("partielle", partielle.etat());
        assertEquals(100.0, partielle.montantEncaisse(), 0.0001);
        assertEquals(316.50, partielle.restantDu(), 0.0001);
        assertEquals(1, partielle.encaissements().size());
        assertEquals(LocalDate.now(), partielle.encaissements().get(0).paidOn(), "la date est le jour du clic");

        FactureResponse payee = factures.encaisser(facture.id(),
                new EncaissementRequest(316.50, "virement", null), jeton(), true);
        assertEquals("payee", payee.etat());
        assertEquals(0.0, payee.restantDu(), 0.0001);
        assertEquals(2, payee.encaissements().size());
        assertNotNull(detailDuJournal(facture.id(), "Encaissement de"), "l'argent rentré part au journal");
    }

    @Test
    void un_versement_plus_grand_que_le_restant_du_se_refuse() {
        MaintenanceItem bl = bonSigneAvecDevis();
        FactureResponse facture = factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> factures.encaisser(facture.id(), new EncaissementRequest(500.0, null, null), jeton(), true));

        assertTrue(refus.getReason().contains("dépasse ce qui reste dû"), refus.getReason());
        assertEquals(0, encaissementRepo.findByFactureIdOrderByPaidOnAsc(facture.id()).size(),
                "le refus n'écrit aucun versement");
    }

    @Test
    void une_facture_payee_n_attend_plus_rien() {
        MaintenanceItem bl = bonSigneAvecDevis();
        FactureResponse facture = factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true);
        factures.encaisser(facture.id(), new EncaissementRequest(416.50, null, null), jeton(), true);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> factures.encaisser(facture.id(), new EncaissementRequest(10.0, null, null), jeton(), true));

        assertTrue(refus.getReason().contains("déjà payée"), refus.getReason());
    }

    @Test
    void la_relance_rend_le_bon_signe_sans_facture_puis_l_efface() {
        MaintenanceItem bl = bonSigneAvecDevis();

        List<AFacturerDto> avant = factures.aFacturer(true);
        AFacturerDto ligne = avant.stream().filter(a -> a.blId().equals(bl.getId())).findFirst().orElseThrow();
        assertEquals(bl.getNumber(), ligne.blNumber());
        assertEquals(416.50, ligne.montantAFacturer(), 0.0001, "le montant à facturer est celui du devis");
        assertNotNull(ligne.devisNumber());

        factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), true);

        assertTrue(factures.aFacturer(true).stream().noneMatch(a -> a.blId().equals(bl.getId())),
                "une fois la facture émise, le bon sort de la relance");
    }

    @Test
    void le_compte_sans_droit_aux_chiffres_lit_la_facture_sans_montants() {
        MaintenanceItem bl = bonSigneAvecDevis();

        FactureResponse facture = factures.emettre(new FactureEmissionRequest(bl.getId(), null), jeton(), false);

        assertNotNull(facture.number(), "le numéro et l'état restent lisibles");
        assertEquals("emise", facture.etat());
        assertNull(facture.totalTTC(), "mais l'argent est masqué serveur, pas grisé à l'écran");
        assertNull(facture.montantEncaisse());
        assertNull(facture.restantDu());
        assertTrue(facture.lines().stream().allMatch(l -> l.unitPrice() == null && l.lineTotal() == null));
    }

    @Test
    void une_facture_echue_sans_rien_passe_impayee_a_la_lecture() {
        MaintenanceItem bl = bonSigneAvecDevis();
        FactureResponse facture = factures.emettre(
                new FactureEmissionRequest(bl.getId(), LocalDate.now()), jeton(), true);
        // L'échéance est aujourd'hui : pas encore en retard. On la recule en base pour
        // simuler une créance qui a dormi, et l'état se recalcule à la lecture.
        em.flush();
        base.update("update factures set due_on = ? where id = ?", LocalDate.now().minusDays(5), facture.id());
        // La mise à jour est passée par SQL, hors d'Hibernate : l'entité tenue en session
        // porte encore l'échéance du clic. On la détache pour que la lecture relise la base.
        em.clear();

        FactureResponse relue = factures.get(facture.id(), true);
        assertEquals("impayee", relue.etat(), "l'état se calcule à la lecture, il n'est pas stocké");
        assertTrue(relue.enRetard());
    }
}