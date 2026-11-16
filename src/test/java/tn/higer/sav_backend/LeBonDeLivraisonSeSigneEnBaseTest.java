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

import tn.higer.sav_backend.dto.BonLivraisonDtos.BonDto;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.ClotureRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.SignatureRequest;
import tn.higer.sav_backend.dto.OrdreDtos.FicheResponse;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPhoto;
import tn.higer.sav_backend.model.InterventionPurpose;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceAttachment;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.PhotoAngle;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPhotoRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.BonLivraisonService;
import tn.higer.sav_backend.service.ItemPartService;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceTotals;
import tn.higer.sav_backend.service.OrdreService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Le bon de livraison se signe dans la vraie base, et il y laisse ses preuves.
 *
 * V39 donne au BL ce qu'il lui manquait pour être le papier que le CLIENT tient en main :
 * un état « Signé » écrit par un clic, le nom et la qualité de qui reprend le bus, la date
 * du jour, les réserves dictées, la case « pièces remises », et le compteur relevé à la
 * restitution — celui-là même qui rejoint la fiche du bus par la porte unique. Ce que ces
 * essais vérifient en PostgreSQL, et qu'aucun test à mocks ne peut voir :
 *
 * — le mot nouveau passe par la base (la leçon de V30 : la suite était verte pendant qu'un
 *   500 attendait l'atelier) ;
 * — « Marquer signé » refuse sans nom, refuse sans scan, et le refus ne déplace aucune
 *   colonne ;
 * — une signature ne se pose pas deux fois, et le second refus nomme le premier ;
 * — le papier signé verrouille le BL ET l'ordre dont il est né : c'est le client qui a eu
 *   les deux en main ;
 * — le BL ne chiffre rien : ses totaux restent vides, et ceux d'avant V39 se relisent
 *   tels qu'ils ont été écrits ;
 * — le compteur saisi passe par {@code VehicleMileageService} : il monte la fiche du bus,
 *   et une baisse se refuse avant que le papier soit écrit ;
 * — les cases du BL refusent d'atterrir sur un ordre, et une pièce refuse d'être posée
 *   sur un BL ;
 * — la fiche du BL rend le travail de l'ordre (avec ses heures), les pièces que Saphir a
 *   fournies, l'homme et ses heures de sortie, le compteur des deux côtés, et ce qui manque
 *   pour signer ;
 * — ses bons de livraison d'avant, créés à la main, restent lisibles et ne cochent plus
 *   aucun palier.
 *
 * Chaque essai pose ses propres papiers marqués d'un UUID ; la transaction est annulée par
 * Spring, sa base retrouve ses dossiers tels qu'avant.
 */
@SpringBootTest
@Transactional
class LeBonDeLivraisonSeSigneEnBaseTest {

    @Autowired private BonLivraisonService bons;
    @Autowired private OrdreService ordre;
    @Autowired private MaintenanceChainService chaine;
    @Autowired private ItemPartService piecesPosees;
    @Autowired private MaintenanceRepository documents;
    @Autowired private MaintenanceAttachmentRepository piecesJointes;
    @Autowired private InterventionRepository sorties;
    @Autowired private InterventionPhotoRepository photos;
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

    private Vehicle bus() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à restituer.");
        return bus.get(0);
    }

    // -------------------------------------------------------------------------- les fabriques

    private static MaintenanceDetail ligne(String reference, String libelle, LineNature nature,
                                           PartSupplier fournisseur) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(libelle);
        d.setQuantity(1.0);
        d.setUnitPrice(0.0);
        d.setNature(nature);
        d.setSuppliedBy(fournisseur);
        d.setDone(false);
        return d;
    }

    /**
     * Un ordre terminé, avec son anomalie écrite à la clôture et sa sortie rentrée : l'état
     * dans lequel le bus arrive au guichet pour être rendu. Les lignes portent une pièce
     * fournie par Saphir et une main-d'œuvre — c'est ce partage que la fiche relit.
     */
    private MaintenanceItem ordreTermine() {
        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("maint-essai-" + marque());
        papier.setType(MaintenanceType.OPERATION);
        papier.setNumber("OR-ESS-" + marque());
        papier.setVehicleId(bus().getId());
        papier.setClientId(bus().getClientId());
        papier.setDate(LocalDate.now());
        papier.setStatus(MaintenanceStatus.DRAFT);
        papier.setDetails(new ArrayList<>(List.of(
                ligne("MO-1", "Remplacement de la pompe", LineNature.LABOR, null),
                ligne("F-21", "Pompe à carburant", LineNature.PART, PartSupplier.SAPHIR),
                ligne("CL-3", "Circuit de refroidissement", LineNature.PART, PartSupplier.CLIENT))));
        documents.saveAndFlush(papier);

        Intervention sortie = new Intervention();
        sortie.setId("int-essai-" + marque());
        sortie.setNumber("DP-ESS-" + marque());
        sortie.setStatus(InterventionStatus.RETURNED);
        sortie.setPurpose(InterventionPurpose.ENTRETIEN);
        sortie.setVehicleId(papier.getVehicleId());
        sortie.setClientId(papier.getClientId());
        sortie.setMaintenanceItemId(papier.getId());
        sortie.setDepartedAt(LocalDateTime.now().minusHours(5));
        sortie.setFinishedAt(LocalDateTime.now().minusHours(1));
        sortie.setReportedAt(LocalDateTime.now().minusHours(6));
        sortie.setCreatedAt(LocalDateTime.now().minusHours(6));
        sorties.saveAndFlush(sortie);

        InterventionPhoto photo = new InterventionPhoto();
        photo.setId("ph-essai-" + marque());
        photo.setInterventionId(sortie.getId());
        photo.setAngle(PhotoAngle.AFTER);
        photo.setFilePath("essai/" + sortie.getId() + "/apres.jpg");
        photo.setSha256("0".repeat(64));
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(2048L);
        photo.setUploadedAt(LocalDateTime.now());
        photo.setActorEmail("aya@higer.tn");
        photos.saveAndFlush(photo);

        papier.getDetails().forEach(ligne -> {
            ligne.setDone(true);
            ligne.setRealHours(ligne.getNature() == LineNature.LABOR ? 3.0 : null);
        });
        papier.setAnomalies("Durite de retour fissurée, à surveiller à la prochaine visite");
        documents.saveAndFlush(papier);
        ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton());
        // L'objet tenu par l'essai est une copie détachée : c'est dans la session que le
        // clic écrit la date. Relire le papier évite de mesurer ce qu'il n'a pas reçu.
        return documents.findById(papier.getId()).orElseThrow();
    }

    /** Le bon de livraison né de ce travail, tel que le clic du guichet le fait. */
    private MaintenanceItem bonDepuis(MaintenanceItem papier) {
        MaintenanceItem bl = chaine.bonLivraisonDepuisOrdre(papier.getId(), jeton());
        return documents.findById(bl.getId()).orElseThrow();
    }

    /** Le scan du bon que le client a signé et rendu : la seule preuve de la restitution. */
    private void joindreLeScan(MaintenanceItem bl) {
        MaintenanceAttachment piece = new MaintenanceAttachment();
        piece.setId("att-essai-" + marque());
        piece.setMaintenanceItemId(bl.getId());
        piece.setOriginalName("bon-signé-sntri.jpg");
        piece.setContentType("image/jpeg");
        piece.setSizeBytes(3072L);
        piece.setStoragePath("essai/" + bl.getId() + "/scan.jpg");
        piece.setSha256("0".repeat(64));
        piece.setUploadedBy("chef@higer.tn");
        piece.setUploadedAt(LocalDateTime.now());
        piecesJointes.saveAndFlush(piece);
    }

    // ---------------------------------------------------------------------------- relus en SQL

    /** La colonne décide, pas le DTO : sans flush, Hibernate n'aurait rien envoyé. */
    private Map<String, Object> relirePapier(String id) {
        em.flush();
        return base.queryForMap("select type, status, signed_on, signataire_nom, signataire_qualite, reserves,"
                + " parts_returned, mileage_at_delivery, sold_plan_id, plan_step_id, source_item_id,"
                + " totalht, totalttc from maintenance_items where id = ?", id);
    }

    private Map<String, Object> relireCompteurBus(String vehicleId) {
        em.flush();
        return base.queryForMap("select mileage, mileage_origin, mileage_recorded_at from vehicles where id = ?",
                vehicleId);
    }

    private boolean dateSigneeAujourdhui(String id) {
        em.flush();
        return Boolean.TRUE.equals(base.queryForObject(
                "select coalesce(signed_on = current_date, false) from maintenance_items where id = ?",
                Boolean.class, id));
    }

    private String detailDuJournal(String id, String morceau) {
        em.flush();
        List<String> lignes = base.queryForList(
                "select detail from audit_logs where entity_id = ? and detail like ?", String.class,
                id, "%" + morceau + "%");
        return lignes.isEmpty() ? null : lignes.get(0);
    }

    private int compteDuJournal(String id, String morceau) {
        em.flush();
        Integer n = base.queryForObject(
                "select count(*) from audit_logs where entity_id = ? and detail like ?", Integer.class,
                id, "%" + morceau + "%");
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------ les essais

    /**
     * Le chemin complet, tel que le guichet le jouera : l'ordre est clos, le bon est édité,
     * le scan rentre, le client reprend le bus. Le clic écrit l'état, la date du jour, le
     * nom et la qualité, les réserves — et le journal porte le tout.
     */
    @Test
    void la_signature_ecrit_l_etat_la_date_et_le_signataire_en_base() {
        MaintenanceItem bl = bonDepuis(ordreTermine());
        joindreLeScan(bl);

        MaintenanceItem signe = bons.signer(bl.getId(), new SignatureRequest(
                "M. Ben Salah", "Responsable dépôt SNTRI", "Réservoir à moitié plein, à reprendre"), jeton());

        assertEquals("SIGNED", signe.getStatus().name());
        Map<String, Object> lu = relirePapier(bl.getId());
        assertEquals("SIGNED", lu.get("status"), "le mot nouveau passe par la colonne");
        assertTrue(dateSigneeAujourdhui(bl.getId()), "la date signée est celle du clic, jamais une case tapée");
        assertEquals("M. Ben Salah", lu.get("signataire_nom"));
        assertEquals("Responsable dépôt SNTRI", lu.get("signataire_qualite"));
        assertEquals("Réservoir à moitié plein, à reprendre", lu.get("reserves"));
        assertNotNull(detailDuJournal(bl.getId(), "signé par"), "la restitution part au journal");
        assertTrue(detailDuJournal(bl.getId(), "signé par").contains("Ben Salah"));
        assertTrue(detailDuJournal(bl.getId(), "signé par").contains("Réservoir à moitié plein"),
                "les réserves du client se relisent six mois après");
    }

    /**
     * Pas de compte client au portail : le nom est la seule trace de qui a repris le bus.
     * Sans lui, une réserve contestée se retourne contre l'atelier — et le refus n'écrit
     * rien, ni état ni date.
     */
    @Test
    void signer_sans_nom_refuse_et_ne_deplace_aucune_colonne() {
        MaintenanceItem bl = bonDepuis(ordreTermine());
        joindreLeScan(bl);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> bons.signer(bl.getId(), new SignatureRequest("   ", null, null), jeton()));

        assertTrue(refus.getReason().contains("Écrivez le nom"), refus.getReason());
        assertTrue(refus.getReason().contains(bl.getNumber()), "le refus nomme le papier, pas un identifiant");
        assertEquals("DRAFT", relirePapier(bl.getId()).get("status"));
        assertNull(relirePapier(bl.getId()).get("signed_on"));
        assertNull(relirePapier(bl.getId()).get("signataire_nom"));
    }

    /**
     * Le scan est la deuxième porte : une signature que personne ne peut montrer n'est pas
     * une restitution, c'est une parole. Le refus dit ce qu'il faut joindre et où.
     */
    @Test
    void signer_sans_scan_joint_refuse_en_disant_quoi_apporter() {
        MaintenanceItem bl = bonDepuis(ordreTermine());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton()));

        assertTrue(refus.getReason().contains("Aucun papier joint"), refus.getReason());
        assertTrue(refus.getReason().contains(bl.getNumber()), refus.getReason());
        assertEquals("DRAFT", relirePapier(bl.getId()).get("status"));
    }

    /** Le bon est un papier : un devis et un ordre ne se signent pas par cette porte. */
    @Test
    void la_signature_ne_se_demande_pas_sur_un_autre_papier() {
        MaintenanceItem papier = ordreTermine();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> bons.signer(papier.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton()));

        assertTrue(refus.getReason().contains("n'est pas un bon de livraison"), refus.getReason());
        assertEquals("COMPLETED", relirePapier(papier.getId()).get("status"),
                "l'ordre reste clos : la porte ne l'a pas touché");
    }

    /**
     * Une signature ne se pose pas deux fois. Le second refus nomme la date et le signataire
     * du premier : ce qui s'est passé après se note sur un dossier, pas sur un papier que le
     * client a en main.
     */
    @Test
    void une_signature_ne_se_pose_pas_deux_fois() {
        MaintenanceItem bl = bonDepuis(ordreTermine());
        joindreLeScan(bl);
        bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton());

        ResponseStatusException second = assertThrows(ResponseStatusException.class,
                () -> bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton()));

        assertTrue(second.getReason().contains("déjà signé"), second.getReason());
        assertTrue(second.getReason().contains("Ben Salah"), "le refus nomme qui a signé la première fois");
        assertEquals(1, compteDuJournal(bl.getId(), "signé par"), "et le journal ne porte pas une seconde restitution");
    }

    /**
     * Un bon annulé n'attend plus de signature : la porte est fermée, et le renvoyer au
     * client se fait par un nouveau papier.
     */
    @Test
    void un_bon_annule_ne_se_signe_plus() {
        MaintenanceItem bl = bonDepuis(ordreTermine());
        joindreLeScan(bl);
        bl.setStatus(MaintenanceStatus.CANCELLED);
        documents.saveAndFlush(bl);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton()));

        assertTrue(refus.getReason().contains("est annulé"), refus.getReason());
    }

    /**
     * Le papier signé verrouille le papier signé, et l'ordre dont il est né avec lui. Le
     * client a tenu les deux en main : une ligne changée après coup n'est pas une correction,
     * c'est une autre marchandise — et l'« Annulé » qui libérait le palier n'est plus permis.
     */
    @Test
    void un_bon_signe_verrouille_le_bon_et_l_ordre_dont_il_est_ne() {
        MaintenanceItem papier = ordreTermine();
        MaintenanceItem bl = bonDepuis(papier);
        joindreLeScan(bl);
        bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton());

        ResponseStatusException retouche = assertThrows(ResponseStatusException.class,
                () -> bons.verifierModification(bl, null));
        assertTrue(retouche.getReason().contains("ne se réécrit plus"), retouche.getReason());

        ResponseStatusException annulation = assertThrows(ResponseStatusException.class,
                () -> ordre.verifierModification(papier, MaintenanceStatus.CANCELLED));
        assertTrue(annulation.getReason().contains("ne s'annule pas"), annulation.getReason());
        assertTrue(annulation.getReason().contains(bl.getNumber()),
                "le refus nomme le bon que le client a signé : " + annulation.getReason());
    }

    /**
     * Les trois gardes du formulaire : un BL ne naît pas signé, son état ne se tape pas
     * tant qu'il est brouillon, et il garde le verrou de l'ordre d'avant — un papier signé
     * est le seul qui ne se touche plus du tout.
     */
    @Test
    void un_bon_ne_nait_pas_signe_et_son_etat_ne_se_tape_pas() {
        bons.verifierCreation(MaintenanceType.BL, null);
        bons.verifierCreation(MaintenanceType.BL, MaintenanceStatus.DRAFT);
        ResponseStatusException naissance = assertThrows(ResponseStatusException.class,
                () -> bons.verifierCreation(MaintenanceType.BL, MaintenanceStatus.SIGNED));
        assertTrue(naissance.getReason().contains("ne naît pas"), naissance.getReason());

        MaintenanceItem bl = bonDepuis(ordreTermine());
        ResponseStatusException tape = assertThrows(ResponseStatusException.class,
                () -> bons.verifierModification(bl, MaintenanceStatus.SIGNED));
        assertTrue(tape.getReason().contains("ne se tape pas"), tape.getReason());
        bons.verifierModification(bl, null);
        bons.verifierModification(bl, MaintenanceStatus.DRAFT);
        // Un devis et un ordre restent gardés par leurs propres services, pas par celui-ci.
        bons.verifierModification(ordreTermine(), MaintenanceStatus.DELIVERED);
    }

    /**
     * Le BL ne chiffre pas (V39) : il rend le bus et les pièces. Les lignes qu'il reprend de
     * l'ordre gardent leurs montants d'avant travail, mais ses totaux à lui ne se calculent
     * plus — un même travail ne se défendrait pas à trois prix différents.
     */
    @Test
    void le_bon_ne_chiffre_rien_et_ses_lignes_restituent_le_temps_passe() {
        MaintenanceItem papier = ordreTermine();
        MaintenanceItem bl = bonDepuis(papier);

        assertEquals(3, bl.getDetails().size(), "le client voit les lignes du travail");
        assertEquals(Boolean.TRUE, bl.getDetails().get(0).getDone(), "fait, tel que l'ordre l'a coché");
        assertEquals(3.0, bl.getDetails().get(0).getRealHours(), 0.0001,
                "les heures suivent le papier : on ne les recompte pas sur un bon signé");

        MaintenanceTotals.appliquer(bl, 19);
        documents.saveAndFlush(bl);

        Map<String, Object> lu = relirePapier(bl.getId());
        assertNull(lu.get("totalht"), "aucun total sur un bon de livraison");
        assertNull(lu.get("totalttc"));
    }

    /**
     * Le compteur relevé à la restitution n'est pas une copie décorative : il monte la fiche
     * du bus par la porte unique, avec son origine — et c'est par là que le rappel du palier
     * suivant s'éteindra. Le refus de la porte laisse le papier à sa valeur d'avant.
     */
    @Test
    void le_compteur_du_bon_passe_par_la_porte_unique_du_bus() {
        Vehicle choisi = bus();
        long connu = choisi.getMileage() == null ? 0L : choisi.getMileage();
        long valeur = Math.min(connu + 5_000L, 999_999L);
        Assumptions.assumeTrue(valeur > connu, "compteur déjà au plafond : rien à remonter.");

        MaintenanceItem bl = bonDepuis(ordreTermine());
        bons.poserLesCases(bl, valeur, true, jeton());
        documents.saveAndFlush(bl);

        assertEquals(valeur, ((Number) relirePapier(bl.getId()).get("mileage_at_delivery")).longValue());
        assertEquals(Boolean.TRUE, relirePapier(bl.getId()).get("parts_returned"));
        Map<String, Object> fiche = relireCompteurBus(choisi.getId());
        assertEquals(valeur, ((Number) fiche.get("mileage")).longValue(), "la fiche du bus a suivi le bon");
        assertEquals("ATELIER", fiche.get("mileage_origin"));

        long reste = valeur;
        ResponseStatusException baisse = assertThrows(ResponseStatusException.class,
                () -> bons.poserLesCases(bl, reste - 1_000L, null, jeton()));
        assertTrue(baisse.getReason().contains("plus bas"), baisse.getReason());
        assertEquals(reste, ((Number) relirePapier(bl.getId()).get("mileage_at_delivery")).longValue(),
                "le refus laisse le bon à sa valeur d'avant");
        assertEquals(reste, ((Number) relireCompteurBus(choisi.getId()).get("mileage")).longValue());
    }

    /**
     * Une valeur qui ne change pas ne repousse pas le compteur : relire et renvoyer le même
     * papier ne doit pas réécrire le kilométrage du bus à chaque enregistrement.
     */
    @Test
    void la_meme_valeur_de_compteur_ne_reecrit_pas_la_fiche_du_bus() {
        Vehicle choisi = bus();
        long connu = choisi.getMileage() == null ? 0L : choisi.getMileage();
        long valeur = Math.min(connu + 2_000L, 999_999L);
        Assumptions.assumeTrue(valeur > connu, "compteur déjà au plafond : rien à remonter.");

        MaintenanceItem bl = bonDepuis(ordreTermine());
        bons.poserLesCases(bl, valeur, null, jeton());
        documents.saveAndFlush(bl);
        Object premiereHeure = relireCompteurBus(choisi.getId()).get("mileage_recorded_at");

        bons.poserLesCases(bl, valeur, false, jeton());
        documents.saveAndFlush(bl);

        assertEquals(premiereHeure, relireCompteurBus(choisi.getId()).get("mileage_recorded_at"),
                "la seconde passe ne touche pas le compteur du bus");
        assertEquals(Boolean.FALSE, relirePapier(bl.getId()).get("parts_returned"),
                "en revanche la case des pièces se corrige, tant que le bon n'est pas signé");
    }

    /**
     * Les deux cases sont celles du bon : sur un ordre ou un devis, elles se refusent et le
     * refus dit sur quel papier elles vivent.
     */
    @Test
    void les_cases_du_bon_refusent_d_atterrir_sur_un_ordre() {
        MaintenanceItem papier = ordreTermine();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> bons.poserLesCases(papier, 5_000L, null, jeton()));

        assertTrue(refus.getReason().contains("pas sur un « Ordre de travail »"), refus.getReason());
        assertNull(relirePapier(papier.getId()).get("mileage_at_delivery"));
        bons.poserLesCases(papier, null, null, jeton());
    }

    /**
     * Une pièce se pose sur l'ordre qui travaille, pas sur le papier qui rend : le bon
     * déclare ce qui est monté, il ne monte rien.
     */
    @Test
    void une_piece_ne_se_pose_pas_sur_un_bon_de_livraison() {
        MaintenanceItem bl = bonDepuis(ordreTermine());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> piecesPosees.ajouter(bl.getId(),
                        new ItemPartRequest("F-77", "Courroie", 1.0, LocalDate.now()), jeton()));

        assertTrue(refus.getReason().contains("est un bon de livraison"), refus.getReason());
        em.flush();
        assertEquals(0, base.queryForObject(
                "select count(*) from item_parts where maintenance_item_id = ?", Integer.class,
                bl.getId()).intValue());
    }

    /**
     * La fiche du bon rend ce que ses propres colonnes ne savent pas : le travail de l'ordre
     * avec son anomalie et sa date, les pièces que Saphir a fournies (pas celles du client),
     * l'homme et ses heures tels que la sortie les a écrits, le compteur des deux côtés, et
     * ce qui manque pour signer. Tout vient du serveur — l'écran ne devine rien.
     */
    @Test
    void la_fiche_du_bon_rend_le_travail_les_pieces_l_homme_et_le_compteur() {
        MaintenanceItem papier = ordreTermine();
        MaintenanceItem bl = bonDepuis(papier);
        Intervention sortie = sorties
                .findByMaintenanceItemIdOrderByReportedAtDesc(papier.getId()).get(0);

        FicheResponse fiche = ordre.fiche(bl.getId());
        BonDto bloc = fiche.bon();

        assertNotNull(bloc, "le bloc du bon est rendu sur la fiche du bon");
        assertNull(fiche.cloture(), "et l'état de clôture ne se lit que sur un ordre");
        assertEquals(papier.getNumber(), bloc.travail().numeroOrdre());
        assertEquals(papier.getClosedOn(), bloc.travail().dateDeCloture());
        assertEquals("Durite de retour fissurée, à surveiller à la prochaine visite",
                bloc.travail().anomalies(), "l'anomalie se lit à la restitution, sinon elle meurt sur l'ordre");
        assertEquals(1, bloc.piecesSaphir().size(), "seules les pièces fournies par Saphir se rendent");
        assertEquals("Pompe à carburant", bloc.piecesSaphir().get(0).designation());
        assertEquals(sortie.getNumber(), bloc.intervenant().numeroSortie());
        assertNotNull(bloc.intervenant().depart(), "l'heure vient de la sortie, elle n'est pas retapée");
        assertNotNull(bloc.compteur());
        assertEquals(bus().getMileage(), bloc.compteur().valeurFiche(),
                "le compteur d'en face est celui que la fiche du bus connaît");
        assertFalse(bloc.scanSigneJoint());
        assertFalse(bloc.signature().pret(), "aucun scan joint : le clic refuserait");
        assertTrue(bloc.signature().ceQuiManque().get(0).contains("papier joint"),
                bloc.signature().ceQuiManque().toString());
        assertEquals(bl.getNumber(), fiche.papier().number());
        assertEquals("Bon de livraison", fiche.papier().typeLabel());
        assertEquals("Brouillon", fiche.papier().statusLabel());
        assertNull(relirePapier(bl.getId()).get("sold_plan_id"), "le bon ne décompte pas le palier");
        assertNull(fiche.forfait());

        joindreLeScan(bl);
        assertTrue(ordre.fiche(bl.getId()).bon().signature().pret(),
                "le scan joint, le clic passera — l'écran colore, le serveur décide");
    }

    /** La fiche d'un ordre ne rend pas de bloc bon : chaque papier porte ce qu'il peut prouver. */
    @Test
    void la_fiche_d_un_ordre_ne_porte_pas_le_bloc_d_un_bon() {
        MaintenanceItem papier = ordreTermine();

        FicheResponse fiche = ordre.fiche(papier.getId());

        assertNull(fiche.bon());
        assertNotNull(fiche.cloture());
    }

    /**
     * Les noms des composantes sont les clés du JSON, donc le contrat de l'écran :
     * {@code sav-bus-higer-angular/…/src/app/core/types.ts}, interface {@code OrdreFiche}.
     * Renommer « bon » en « bloc » d'un seul côté ne casse ni le serveur (200 OK) ni la
     * compilation de l'Angular (son interface se suffit à elle-même) : la fiche s'affiche
     * vide chez l'atelier, et c'est exactement le défaut du 04/11 sur BL-2026-0094.
     */
    @Test
    void les_cases_de_la_fiche_portent_le_nom_que_l_ecran_lit() {
        assertEquals(List.of("papier", "origine", "forfait", "photosSortie", "piecesPosees", "cloture", "bon"),
                Arrays.stream(FicheResponse.class.getRecordComponents()).map(r -> r.getName()).toList(),
                        "les clés de la fiche sont celles que l'Angular lit, ni plus ni moins");
    }

    /**
     * La lecture du portail ne dépend pas de V39 : ses bons déjà créés à la main, sans
     * signataire ni compteur, se relisent tels qu'ils sont — sans que la fiche explose et
     * sans qu'aucun palier ne se coche derrière eux.
     */
    @Test
    void les_bons_d_avant_resterent_lisibles() {
        List<MaintenanceItem> sesBons = documents.findAll().stream()
                .filter(p -> p.getType() == MaintenanceType.BL).toList();
        Assumptions.assumeTrue(!sesBons.isEmpty(), "Aucun bon de livraison en base : rien à relire.");

        for (MaintenanceItem bl : sesBons) {
            MaintenanceResponse rendu = MaintenanceResponse.from(bl);
            FicheResponse fiche = ordre.fiche(bl.getId());
            assertNotNull(fiche.bon(), "le bloc se construit même sans lignes ni origine : " + bl.getNumber());
            assertNull(fiche.cloture());
            if (bl.getStatus() != MaintenanceStatus.SIGNED) {
                assertNull(relirePapier(bl.getId()).get("signed_on"), rendu.number() + " n'est pas signé");
            }
            assertEquals("BL", relirePapier(bl.getId()).get("type"));
        }
    }

    /**
     * Le compteur du papier est lu à la restitution, jamais laissé dans une case muette :
     * l'état « Signé » se relit en SQL sous son mot français, et c'est ce mot que l'écran
     * affiche — une pastille « signed » ne dit pas à l'atelier si le bus est parti.
     */
    @Test
    void le_mot_signe_se_lit_en_francais() {
        MaintenanceItem bl = bonDepuis(ordreTermine());
        joindreLeScan(bl);
        bons.signer(bl.getId(), new SignatureRequest("M. Ben Salah", null, null), jeton());

        assertEquals("Signé", MaintenanceStatus.SIGNED.label(MaintenanceType.BL));
        assertEquals("Signé", MaintenanceResponse.from(bl).statusLabel());
        assertTrue(dateSigneeAujourdhui(bl.getId()));
    }
}
