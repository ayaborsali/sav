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

import tn.higer.sav_backend.controller.MaintenanceController;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceLineDto;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.RefusRequest;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.PieceFamily;
import tn.higer.sav_backend.model.PieceRechange;
import tn.higer.sav_backend.model.PieceType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.PieceRechangeRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.DevisService;
import tn.higer.sav_backend.service.ItemPartService;
import tn.higer.sav_backend.service.MaintenanceTotals;

import java.math.BigDecimal;
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
 * Le devis joue ses états dans la vraie base, et ses lignes gardent leurs mots neufs.
 *
 * V36 a ajouté trois états au devis (« envoyé », « accepté », « refusé »), trois dates,
 * le motif du refus, et deux mots par ligne (nature, « fourni par »). Un service qui
 * refuse se voit dans ses tests à lui ; ce que ces tests-ci vérifient, c'est que
 * PostgreSQL accepte les mots que le Java écrit. C'est exactement le trou de V30 : six
 * des huit états du dossier connus de la base, un 500 sur « j'arrive », et la suite
 * verte sans s'en apercevoir parce que les dépôts étaient bouchonnés.
 *
 * Ce que la base doit montrer, dans l'ordre du papier :
 *
 * — une ligne naturée s'enregistre et se relit avec ses deux mots — main-d'œuvre, pièce
 *   fournie par Saphir ou par le client ;
 * — « Envoyer au client » puis « Enregistrer l'accord » écrivent l'état et les dates,
 *   posées par le serveur ;
 * — l'accord se prouve : sans papier joint au dossier, le devis reste « envoyé » ;
 * — le refus laisse sa raison écrite dans la colonne, et son accord à lui reste vide ;
 * — le formulaire ne peut plus retoucher un devis parti chez le client, ni écrire un
 *   état au clavier, ni faire naître un devis déjà accepté, ni chiffrer une ligne dont
 *   personne ne sait ce qu'elle facture ;
 * — la porte des pièces posées reste fermée devant un devis : rien n'est monté tant
 *   que le client n'a pas signé.
 *
 * Le devis est posé pour l'essai sur un bus qui existe, avec un numéro marqué à part.
 * La transaction est annulée par Spring : sa base retrouve ses papiers tels qu'avant.
 */
@SpringBootTest
@Transactional
class LeDevisSuitSesEtatsEnBaseTest {

    @Autowired private DevisService devis;
    @Autowired private ItemPartService pieces;
    @Autowired private MaintenanceController formulaire;
    @Autowired private MaintenanceRepository documents;
    @Autowired private MaintenanceAttachmentRepository piecesJointes;
    @Autowired private VehicleRepository vehicles;
    @Autowired private PieceRechangeRepository piecesDuCatalogue;
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

    private static MaintenanceDetail ligne(String reference, String libelle, LineNature nature,
                                           PartSupplier fournisseur, double prix) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(libelle);
        d.setQuantity(1.0);
        d.setUnitPrice(prix);
        d.setNature(nature);
        d.setSuppliedBy(fournisseur);
        return d;
    }

    /**
     * Un devis brouillon de trois lignes, dont une pièce fournie par Saphir et une pièce
     * que le client apporte lui-même. On pose ses propres lignes et ses propres totaux :
     * un essai qui s'appuierait sur les papiers de l'atelier mesurerait ce qu'il n'a pas
     * écrit.
     */
    private MaintenanceItem devisNeufAvecTroisLignes() {
        List<Vehicle> bus = vehicles.findAll().stream().toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus en base : rien à chiffrer.");
        String marque = UUID.randomUUID().toString().substring(0, 8);

        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("maint-essai-" + marque);
        papier.setType(MaintenanceType.DEVIS);
        papier.setNumber("DV-ESS-" + marque);
        papier.setVehicleId(bus.get(0).getId());
        papier.setClientId(bus.get(0).getClientId() == null ? "" : bus.get(0).getClientId());
        papier.setDate(LocalDate.now());
        papier.setStatus(MaintenanceStatus.DRAFT);
        papier.setDetails(new ArrayList<>(List.of(
                ligne("MO-1", "Main-d'œuvre de la visite", LineNature.LABOR, null, 21.0),
                ligne("F-10", "Filtre à huile", LineNature.PART, PartSupplier.SAPHIR, 34.5),
                ligne("F-20", "Courroie apportée par le client", LineNature.PART, PartSupplier.CLIENT, 0.0))));
        MaintenanceTotals.appliquer(papier, 19);
        return documents.saveAndFlush(papier);
    }

    /**
     * Le devis est « envoyé » et son accord devient prouvable : la ligne du registre des
     * pièces jointes est écrite ici, sans fichier sur le disque. Ce que le service
     * regarde pour refuser un accord, c'est ce registre — le versement réel du scan, son
     * empreinte et son nom nettoyé se prouvent dans {@code MaintenanceAttachmentServiceTest}.
     */
    private void joindreLeScanSigne(MaintenanceItem papier) {
        MaintenanceAttachment piece = new MaintenanceAttachment();
        piece.setId("att-essai-" + UUID.randomUUID().toString().substring(0, 12));
        piece.setMaintenanceItemId(papier.getId());
        piece.setOriginalName("devis-signe.pdf");
        piece.setContentType("application/pdf");
        piece.setSizeBytes(1024L);
        piece.setStoragePath("essai/" + papier.getId() + "/devis-signe.pdf");
        piece.setSha256("0".repeat(64));
        piece.setUploadedBy("guichet@higer.tn");
        piece.setUploadedAt(LocalDateTime.now());
        piecesJointes.saveAndFlush(piece);
    }

    /** L'état et le motif relus en SQL : c'est la colonne qui décide, pas le DTO. */
    private Map<String, Object> relirePapier(String id) {
        em.flush();
        return base.queryForMap("select status, refusal_reason from maintenance_items where id = ?", id);
    }

    /** La date que le serveur a posée est bien le jour de l'essai, comparée en base. */
    private boolean datePoseeAUjourhui(String id, String colonne) {
        em.flush();
        Boolean ceJour = base.queryForObject(
                "select coalesce(" + colonne + " = current_date, false) from maintenance_items where id = ?",
                Boolean.class, id);
        return Boolean.TRUE.equals(ceJour);
    }

    private boolean dateVide(String id, String colonne) {
        em.flush();
        return Boolean.TRUE.equals(base.queryForObject(
                "select " + colonne + " is null from maintenance_items where id = ?", Boolean.class, id));
    }

    private double totalTTCenBase(String id) {
        em.flush();
        return base.queryForObject("select totalttc from maintenance_items where id = ?", Double.class, id);
    }

    private int compteDePapiers() {
        em.flush();
        Integer n = base.queryForObject("select count(*) from maintenance_items", Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ les mots neufs des lignes

    /**
     * Le premier critère de V36 et V37 : les mots que {@code LineNature} et
     * {@code PartSupplier} écrivent sont bien ceux que la contrainte CHECK accepte. Un
     * « SAPPHIR » resté avec deux P dans la base après le renommage de l'enum ferait
     * partir le premier devis en 23514 — sur l'écran du guichet, pas dans un test.
     */
    @Test
    void une_ligne_naturee_senregistre_et_se_relit_ses_deux_mots() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();

        List<Map<String, Object>> lignes = base.queryForList(
                "select nature, supplied_by from maintenance_details where maintenance_id = ?"
                        + " order by line_order", papier.getId());

        assertEquals(3, lignes.size());
        assertEquals("LABOR", lignes.get(0).get("nature"));
        assertNull(lignes.get(0).get("supplied_by"), "une main-d'œuvre n'a pas de fournisseur");
        assertEquals("PART", lignes.get(1).get("nature"));
        assertEquals("SAPHIR", lignes.get(1).get("supplied_by"), "la pièce du magasin se facture");
        assertEquals("CLIENT", lignes.get(2).get("supplied_by"), "la pièce du client ne se facture pas");
    }

    // ------------------------------------------------------------------ envoyer, accepter, refuser

    @Test
    void lenvoi_puis_laccord_eignent_le_statut_et_les_deux_dates() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();

        devis.envoyer(papier.getId(), jeton());
        assertEquals("SENT", relirePapier(papier.getId()).get("status"));
        assertTrue(datePoseeAUjourhui(papier.getId(), "sent_on"), "la date d'envoi vient du serveur");
        assertTrue(dateVide(papier.getId(), "accepted_on"), "envoyer n'est pas un accord");

        joindreLeScanSigne(papier);
        devis.accord(papier.getId(), jeton());

        assertEquals("VALIDATED", relirePapier(papier.getId()).get("status"));
        assertTrue(datePoseeAUjourhui(papier.getId(), "accepted_on"));
        assertTrue(datePoseeAUjourhui(papier.getId(), "sent_on"), "l'accord n'efface pas la date d'envoi");
    }

    @Test
    void un_accord_sans_le_papier_signe_refuse_et_le_devis_reste_envoye() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();
        devis.envoyer(papier.getId(), jeton());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.accord(papier.getId(), jeton()));

        assertTrue(refus.getReason().contains("Aucun papier joint"), refus.getReason());
        assertEquals("SENT", relirePapier(papier.getId()).get("status"), "le refus n'a pas fait avancer le papier");
        assertTrue(dateVide(papier.getId(), "accepted_on"));
    }

    @Test
    void un_refus_laisse_sa_raison_ecrite_sur_le_papier() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();
        devis.envoyer(papier.getId(), jeton());

        devis.refus(papier.getId(), new RefusRequest("trop cher, on attend le budget de décembre"), jeton());

        Map<String, Object> refuse = relirePapier(papier.getId());
        assertEquals("REFUSED", refuse.get("status"));
        assertEquals("trop cher, on attend le budget de décembre", refuse.get("refusal_reason"));
        assertTrue(datePoseeAUjourhui(papier.getId(), "refused_on"));
        assertTrue(dateVide(papier.getId(), "accepted_on"), "un refus n'est pas un accord qui s'ignore");
    }

    /** Un refus n'ouvre pas un ordre : la chaîne reste gardée par l'accord du client. */
    @Test
    void un_devis_refuse_ne_donne_pas_un_ordre() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();
        devis.envoyer(papier.getId(), jeton());
        devis.refus(papier.getId(), new RefusRequest("trop cher"), jeton());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.ordreDepuisDevis(papier.getId(), jeton()));

        assertTrue(refus.getReason().contains("refusé"), refus.getReason());
        em.flush();
        Integer enfants = base.queryForObject(
                "select count(*) from maintenance_items where source_item_id = ?", Integer.class, papier.getId());
        assertEquals(0, enfants == null ? 0 : enfants, "aucun ordre n'est né d'un refus");
    }

    // ------------------------------------------------------------------ les gardes du formulaire, en base

    /**
     * Le verrou lu depuis l'API : un papier parti chez le client ne se retouche plus, et
     * le montant que le client a sous les yeux reste celui de la base.
     */
    @Test
    void un_devis_envoye_ne_se_retouche_plus() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();
        devis.envoyer(papier.getId(), jeton());
        double totalALenvoi = totalTTCenBase(papier.getId());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.update(papier.getId(), requete(papier, MaintenanceStatus.SENT, 999.0), jeton(), null));

        assertTrue(refus.getReason().contains("n'est plus modifiable"), refus.getReason());
        assertEquals("SENT", relirePapier(papier.getId()).get("status"));
        assertEquals(totalALenvoi, totalTTCenBase(papier.getId()), 0.0001,
                "le montant envoyé chez le client n'a pas bougé");
    }

    /** Le même verrou, lu depuis la case d'état : personne n'écrit « accepté » au clavier. */
    @Test
    void letat_dun_devis_ne_se_tape_pas_au_clavier() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.update(papier.getId(), requete(papier, MaintenanceStatus.VALIDATED, 45.0),
                        jeton(), null));

        assertTrue(refus.getReason().contains("ne se change pas au clavier"), refus.getReason());
        assertEquals("DRAFT", relirePapier(papier.getId()).get("status"));
        assertFalse(datePoseeAUjourhui(papier.getId(), "accepted_on"));
    }

    @Test
    void un_devis_ne_nait_pas_accepte_en_base() {
        int avant = compteDePapiers();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.create(requeteNeuve(MaintenanceStatus.VALIDATED, "part", "saphir"), jeton(), null));

        assertTrue(refus.getReason().contains("commence brouillon"), refus.getReason());
        assertEquals(avant, compteDePapiers(), "le refus n'a rien laissé derrière lui");
    }

    /** Une ligne sans nature ne passe pas la porte d'entrée : le devis ne dirait pas ce qu'il chiffre. */
    @Test
    void une_ligne_sans_nature_arrete_la_creation_du_devis() {
        int avant = compteDePapiers();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.create(requeteNeuve(MaintenanceStatus.DRAFT, null, null), jeton(), null));

        assertTrue(refus.getReason().contains("Ligne 1"), refus.getReason());
        assertTrue(refus.getReason().contains("main-d'œuvre, pièce ou déplacement"), refus.getReason());
        assertEquals(avant, compteDePapiers(), "rien n'est enregistré sur un refus");
    }

    // ------------------------------------------------------------------ la porte des pièces posées

    /**
     * Le devis n'a pas de bloc « Pièces posées », et ce n'est pas l'écran qui le décide :
     * la garde est serveur. Une pièce attachée à un papier que le client n'a pas encore
     * signé partirait dans un claim comme si elle était montée dans le bus.
     */
    @Test
    void une_piece_ne_se_pose_pas_sur_un_devis_en_base() {
        MaintenanceItem papier = devisNeufAvecTroisLignes();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> pieces.ajouter(papier.getId(),
                        new ItemPartRequest("F-10", "Filtre à huile", 1.0, null), jeton()));

        assertTrue(refus.getReason().contains("est un devis"), refus.getReason());
        assertTrue(refus.getReason().contains("ordre de travail"), refus.getReason());
        assertEquals("DRAFT", relirePapier(papier.getId()).get("status"), "le refus laisse le devis où il était");
        Integer posees = base.queryForObject(
                "select count(*) from item_parts where maintenance_item_id = ?", Integer.class, papier.getId());
        assertEquals(0, posees == null ? 0 : posees, "aucune pièce montée sur un devis");
    }

    // ------------------------------------------------------------------ la pièce du catalogue (V46)

    /**
     * La ligne d'un devis peut citer une pièce du catalogue. Ce que ces essais
     * mesurent, c'est la colonne et le refus, pas la forme de l'écran : le prix
     * posé au clic est re-vérifié contre la pièce réelle, un compte de l'atelier
     * ne peut pas l'ajuster, et `piece_id` se relit en SQL — sans clé étrangère,
     * la pièce retirée du catalogue reste lisible par le devis d'avant.
     */
    private static Authentication role(String authority) {
        return new UsernamePasswordAuthenticationToken("x@y.tn", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private PieceRechange pieceAuCatalogue(String prixVente) {
        String marque = UUID.randomUUID().toString().substring(0, 8);
        PieceRechange piece = new PieceRechange();
        piece.setId("pc-essai-" + marque);
        piece.setReference("ESSAI-PR-" + marque);
        piece.setDesignationFr("Filtre à huile d'essai");
        piece.setFamille(PieceFamily.MECANIQUE);
        piece.setTypePiece(PieceType.MECANIQUE);
        piece.setPrixExWorks(new BigDecimal("50.000"));
        piece.setCoefficientRevient(new BigDecimal("1.0000"));
        piece.setPrixRevient(new BigDecimal("50.000"));
        piece.setPrixVente(prixVente == null ? null : new BigDecimal(prixVente));
        piece.setFournisseur("Higer Parts");
        piece.setCreatedAt(LocalDateTime.now());
        piece.setUpdatedAt(LocalDateTime.now());
        return piecesDuCatalogue.saveAndFlush(piece);
    }

    /** Le devis d'une ligne citée du catalogue, avec le prix que l'écran vient de poser. */
    private MaintenanceRequest devisAvecPiece(PieceRechange piece, String reference, String designation,
                                              double prix) {
        List<Vehicle> bus = vehicles.findAll().stream().toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus en base : rien à chiffrer.");
        String marque = UUID.randomUUID().toString().substring(0, 8);
        MaintenanceLineDto ligne = new MaintenanceLineDto(reference, designation, 1.0, prix, null,
                "part", "saphir", null, null, null, null, piece.getId());
        return new MaintenanceRequest(MaintenanceType.DEVIS, "DV-ESS-" + marque,
                bus.get(0).getId(), "", LocalDate.now(), MaintenanceStatus.DRAFT, null, null,
                "Khalil", "Pièce du catalogue", null, null, null, List.of(ligne), null, null);
    }

    @Test
    void une_ligne_de_catalogue_ecrit_sa_piece_dans_la_colonne() {
        PieceRechange piece = pieceAuCatalogue("86.500");
        MaintenanceRequest requete = devisAvecPiece(piece, piece.getReference(),
                piece.getDesignationFr(), 86.5);

        MaintenanceResponse devis = formulaire.create(requete, jeton(), role("ROLE_GESTIONNAIRE_SAV"));

        em.flush();
        String pieceId = base.queryForObject(
                "select piece_id from maintenance_details where maintenance_id = ?",
                String.class, devis.id());
        assertEquals(piece.getId(), pieceId, "la ligne cite la pièce dont elle est la copie");
    }

    @Test
    void un_prix_ajuste_se_refuse_a_l_atelier_et_passe_au_guichet() {
        PieceRechange piece = pieceAuCatalogue("86.500");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.create(devisAvecPiece(piece, piece.getReference(),
                        piece.getDesignationFr(), 79.0), jeton(), role("ROLE_CHEF_ATELIER")));

        assertTrue(refus.getReason().contains("administrateur"), refus.getReason());
        assertTrue(refus.getReason().contains("86.500"), refus.getReason());

        MaintenanceResponse devis = formulaire.create(devisAvecPiece(piece, piece.getReference(),
                piece.getDesignationFr(), 79.0), jeton(), role("ROLE_ADMIN"));

        em.flush();
        Double prix = base.queryForObject(
                "select unit_price from maintenance_details where maintenance_id = ?",
                Double.class, devis.id());
        assertEquals(79.0, prix, 0.0001, "un compte autorisé ajuste le prix, et la base le garde");
    }

    @Test
    void une_reference_retouchee_au_clavier_ne_s_ecrit_pas() {
        PieceRechange piece = pieceAuCatalogue("86.500");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.create(devisAvecPiece(piece, piece.getReference() + "-BIS",
                        piece.getDesignationFr(), 86.5), jeton(), role("ROLE_GESTIONNAIRE_SAV")));

        assertTrue(refus.getReason().contains("celles du catalogue"), refus.getReason());
    }

    @Test
    void une_piece_sans_prix_de_vente_arrete_le_devis_et_ne_laisse_rien() {
        PieceRechange piece = pieceAuCatalogue(null);
        int avant = compteDePapiers();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> formulaire.create(devisAvecPiece(piece, piece.getReference(),
                        piece.getDesignationFr(), 0.0), jeton(), role("ROLE_GESTIONNAIRE_SAV")));

        assertTrue(refus.getReason().contains("Chiffrez-la dans Gestion PR"), refus.getReason());
        assertEquals(avant, compteDePapiers(), "le refus n'a rien laissé derrière lui");
    }

    // ------------------------------------------------------------------ petites fabriques de requêtes

    /** La requête telle que l'écran l'enverrait, avec un prix changé dans les lignes. */
    private MaintenanceRequest requete(MaintenanceItem papier, MaintenanceStatus statusDemande, double nouveauPrix) {
        List<MaintenanceLineDto> lignes = new ArrayList<>();
        lignes.add(new MaintenanceLineDto("MO-1", "Main-d'œuvre de la visite", 1.0, nouveauPrix,
                null, "labor", null, null, null, null, null, null));
        return new MaintenanceRequest(papier.getType(), papier.getNumber(), papier.getVehicleId(),
                papier.getClientId(), papier.getDate(), statusDemande, null, null,
                papier.getTechnician(), papier.getDescription(), papier.getAnomalies(),
                papier.getSoldPlanId(), papier.getPlanStepId(), lignes,
                papier.getMileageAtDelivery(), papier.getPartsReturned());
    }

    /** Une requête de devis toute neuve, d'une ligne — dont on choisit les deux mots neufs. */
    private MaintenanceRequest requeteNeuve(MaintenanceStatus statusDemande, String nature, String fournisseur) {
        String marque = UUID.randomUUID().toString().substring(0, 8);
        return new MaintenanceRequest(MaintenanceType.DEVIS, "DV-ESS-" + marque,
                vehicles.findAll().get(0).getId(), "", LocalDate.now(), statusDemande, null, null,
                "Khalil", "Changement du filtre", null, null, null,
                List.of(new MaintenanceLineDto("F-10", "Filtre à huile", 1.0, 34.5, null,
                        nature, fournisseur, null, null, null, null, null)), null, null);
    }
}
