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

import tn.higer.sav_backend.controller.MaintenanceController;
import tn.higer.sav_backend.dto.MaintenanceDtos.ClotureRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.SignatureRequest;
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
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceDocumentService;
import tn.higer.sav_backend.service.MaintenancePdfService;
import tn.higer.sav_backend.service.MaintenanceTotals;
import tn.higer.sav_backend.service.OrdreService;

import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le papier de l'atelier se fabrique dans la vraie base, et l'argent se trie à la source.
 *
 * Le bouton « Télécharger le PDF » ne sort plus une page d'écran : le serveur remet le papier
 * en A4, avec l'en-tête de Saphir, en relisant la fiche par le même chemin que l'écran
 * ({@code OrdreService.fiche}). Ce que ces essais vérifient en PostgreSQL, et qu'aucun test à
 * mocks ne peut voir :
 *
 * — le devis imprime EXACTEMENT les colonnes {@code totalht} / {@code totalttc} de la base, pas
 *   un calcul refait à l'écran ;
 * — l'ordre (V38) et le bon de livraison (V39) sortent sans la moindre ligne de prix : la clé
 *   « totals » est absente, et ni les colonnes ni les cellules de leurs tableaux ne nomment le
 *   dinar ;
 * — l'en-tête garde ses étiquettes même non configurées (« RC : — ») : rien d'inventé ;
 * — le nom que le bon imprime vient du clic en base, et le papier non signé laisse ses cases
 *   vides — sans redémarrer quoi que ce soit entre les deux ;
 * — la route {@code GET /{id}/document.pdf} rend des octets PDF et écrit dans le journal si de
 *   l'argent est sorti de la maison ;
 * — un papier sans nature en base se refuse en 409, il ne s'imprime pas « quand même ».
 *
 * Chaque essai pose ses propres papiers marqués d'un UUID ; la transaction est annulée par
 * Spring, sa base retrouve ses dossiers tels qu'avant.
 */
@SpringBootTest
@Transactional
class LePapierDeLAtelierSortEnPdfEnBaseTest {

    @Autowired private MaintenanceDocumentService documentService;
    @Autowired private MaintenancePdfService miseEnPage;
    @Autowired private MaintenanceController controller;
    @Autowired private BonLivraisonService bons;
    @Autowired private OrdreService ordre;
    @Autowired private MaintenanceChainService chaine;
    @Autowired private MaintenanceRepository documents;
    @Autowired private MaintenanceAttachmentRepository piecesJointes;
    @Autowired private InterventionRepository sorties;
    @Autowired private InterventionPhotoRepository photos;
    @Autowired private VehicleRepository vehicles;
    @Autowired private tn.higer.sav_backend.service.VehicleMileageService compteur;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    /** Les mots qui trahissent un montant : là où ils n'ont rien à faire, ils doivent être absents. */
    private static final List<String> MOTS_D_ARGENT =
            List.of("HT", "TTC", "P.U", "DT", "PRIX", "MONTANT", "DINAR");

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
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à imprimer.");
        return bus.get(0);
    }

    // -------------------------------------------------------------------------- les fabriques

    private static MaintenanceDetail ligne(String reference, String libelle, double quantite,
                                           double prix, LineNature nature, PartSupplier fournisseur) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(libelle);
        d.setQuantity(quantite);
        d.setUnitPrice(prix);
        d.setNature(nature);
        d.setSuppliedBy(fournisseur);
        d.setDone(false);
        return d;
    }

    /**
     * Un devis chiffré, posé comme le serveur le pose : les totaux calculés depuis les lignes
     * par {@code MaintenanceTotals}, puis écrits dans leurs colonnes. C'est ce que le papier
     * doit retrouver, centime pour centime.
     */
    private MaintenanceItem devisChiffre() {
        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("maint-essai-" + marque());
        papier.setType(MaintenanceType.DEVIS);
        papier.setNumber("DV-ESS-" + marque());
        papier.setVehicleId(bus().getId());
        papier.setClientId(bus().getClientId());
        papier.setDate(LocalDate.now());
        papier.setStatus(MaintenanceStatus.DRAFT);
        papier.setTechnician("ayaborsali");
        papier.setDescription("Remplacement de la pompe à carburant, pièce fournie par Saphir.");
        papier.setDetails(new ArrayList<>(List.of(
                ligne("F-21", "Pompe à carburant", 2.0, 45.0, LineNature.PART, PartSupplier.SAPHIR),
                ligne("MO-1", "Main-d'œuvre de pose", 1.0, 30.0, LineNature.LABOR, null))));
        MaintenanceTotals.appliquer(papier, 19);
        documents.saveAndFlush(papier);
        return documents.findById(papier.getId()).orElseThrow();
    }

    /** Un ordre en cours, lignes déjà typées : le papier interne, qui ne chiffre pas (V38). */
    private MaintenanceItem ordreEnCours() {
        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("maint-essai-" + marque());
        papier.setType(MaintenanceType.OPERATION);
        papier.setNumber("OR-ESS-" + marque());
        papier.setVehicleId(bus().getId());
        papier.setClientId(bus().getClientId());
        papier.setDate(LocalDate.now());
        papier.setStatus(MaintenanceStatus.IN_PROGRESS);
        papier.setTechnician("ayaborsali");
        papier.setDescription("Visite des 10 000 km.");
        papier.setDetails(new ArrayList<>(List.of(
                ligne("MO-1", "Vidange moteur", 1.0, 30.0, LineNature.LABOR, null),
                ligne("10107", "Filtre à huile", 1.0, 45.0, LineNature.PART, PartSupplier.SAPHIR))));
        documents.saveAndFlush(papier);
        return documents.findById(papier.getId()).orElseThrow();
    }

    /**
     * Un ordre clos, avec sa sortie rentrée : le seul état depuis lequel le bus se rend. La
     * clôture est un clic du serveur, pas une case — un bon imprimé sur un ordre « À faire »
     * rendrait un travail fini qui ne l'est pas.
     */
    private MaintenanceItem ordreClos() {
        MaintenanceItem papier = ordreEnCours();

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

        papier.getDetails().forEach(l -> {
            l.setDone(true);
            l.setRealHours(l.getNature() == LineNature.LABOR ? 2.5 : null);
        });
        papier.setAnomalies("Durite de retour fissurée, à surveiller à la prochaine visite");
        documents.saveAndFlush(papier);
        ordre.cloturer(papier.getId(), new ClotureRequest(null), jeton());
        return documents.findById(papier.getId()).orElseThrow();
    }

    /** Le bon de livraison né de ce travail clos, tel que le clic du guichet le fait. */
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
    private Map<String, Object> relireTotaux(String id) {
        em.flush();
        return base.queryForMap("select totalht, totalttc from maintenance_items where id = ?", id);
    }

    private String detailDuJournal(String id, String morceau) {
        em.flush();
        List<String> lignes = base.queryForList(
                "select detail from audit_logs where entity_id = ? and detail like ?", String.class,
                id, "%" + morceau + "%");
        return lignes.isEmpty() ? null : lignes.get(0);
    }

    // ------------------------------------------------------------------ les lectures du plan

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("tables");
    }

    @SuppressWarnings("unchecked")
    private static List<String> colonnes(Map<String, Object> table) {
        return (List<String>) table.get("columns");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> lignesDeTable(Map<String, Object> table) {
        return (List<List<String>>) table.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> totals(Map<String, Object> plan) {
        return (List<List<String>>) plan.get("totals");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> signatures(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("signatures");
    }

    @SuppressWarnings("unchecked")
    private static List<String> entete(Map<String, Object> plan) {
        return (List<String>) plan.get("brand_lines");
    }

    /** Une valeur d'argent se reconnaît à ses chiffres, pas à son séparateur de millier. */
    private static String chiffres(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static String chiffresAttendus(double value) {
        return String.valueOf(Math.round(Math.abs(value) * 1000));
    }

    private static String celluleDesTotaux(Map<String, Object> plan, String debutDeLibelle) {
        for (List<String> rangee : totals(plan)) {
            if (rangee.get(0).startsWith(debutDeLibelle)) {
                return rangee.get(1);
            }
        }
        throw new AssertionError("Le devis n'a pas de ligne « " + debutDeLibelle + " » : " + totals(plan));
    }

    /** Rien ne doit nommer le dinar dans les tableaux d'un papier qui ne chiffre pas. */
    private static void aucunMotDArgentDansLesTableaux(Map<String, Object> plan, String numero) {
        for (Map<String, Object> table : tables(plan)) {
            for (String colonne : colonnes(table)) {
                for (String mot : MOTS_D_ARGENT) {
                    assertFalse(colonne.toUpperCase().contains(mot),
                            numero + " : colonne « " + colonne + " » parle d'argent (« " + mot + " »)");
                }
            }
            for (List<String> rangee : lignesDeTable(table)) {
                for (String cellule : rangee) {
                    for (String mot : MOTS_D_ARGENT) {
                        assertFalse(cellule.toUpperCase().contains(mot),
                                numero + " : une cellule du tableau « " + table.get("heading")
                                        + " » parle d'argent (« " + mot + " ») : " + rangee);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ les essais

    /**
     * Le devis est le seul papier qui envoie ses totaux, et il les envoie tels que la base les
     * porte : six mois après, l'atelier doit pouvoir coller le PDF à côté de la ligne SQL.
     */
    @Test
    void le_devis_imprime_les_montants_de_sa_ligne_en_base() {
        MaintenanceItem devis = devisChiffre();
        Map<String, Object> plan = documentService.payload(devis.getId());
        Map<String, Object> colonnes = relireTotaux(devis.getId());
        double ht = ((Number) colonnes.get("totalht")).doubleValue();
        double ttc = ((Number) colonnes.get("totalttc")).doubleValue();

        assertEquals("devis", plan.get("type"));
        assertNotNull(plan.get("totals"), "le devis est le seul papier à porter ses totaux");
        assertEquals(120.0, ht, 0.001, "la base porte bien 2 × 45 + 30");
        assertEquals(142.8, ttc, 0.001, "et son TTC à 19 %");

        assertEquals(chiffresAttendus(ht), chiffres(celluleDesTotaux(plan, "Total HT")),
                "le HT imprimé est celui de la colonne");
        assertEquals(chiffresAttendus(ttc), chiffres(celluleDesTotaux(plan, "Total TTC")),
                "le TTC imprimé est celui de la colonne");
        assertTrue(String.valueOf(plan.get("footer")).contains("dinars tunisiens"),
                "le pied de page dit dans quelle monnaie lire la feuille");
    }

    /**
     * L'ordre décrit le travail et ne le facture pas (V38) : le prix hérité reste en base pour
     * alimenter le devis, sur CE papier plus une seule case d'argent.
     */
    @Test
    void lordre_de_travail_sort_sans_le_moins_d_un_montant() {
        MaintenanceItem ordre = ordreEnCours();
        Map<String, Object> plan = documentService.payload(ordre.getId());

        assertEquals("operation", plan.get("type"));
        assertNull(plan.get("totals"), "la clé « totals » est absente d'un ordre");
        aucunMotDArgentDansLesTableaux(plan, ordre.getNumber());
        assertTrue(String.valueOf(plan.get("badge")).contains("ne le facture pas"),
                "le badge dit à l'atelier pourquoi il n'y a pas de prix");
    }

    /**
     * Le bon de livraison est le papier que le CLIENT tient en main (V39) : le prix est sur le
     * devis qu'il a signé. Un montant à la restitution serait une seconde facture.
     */
    @Test
    void le_bon_de_livraison_sort_sans_le_moins_d_un_montant() {
        MaintenanceItem bl = bonDepuis(ordreClos());
        Map<String, Object> plan = documentService.payload(bl.getId());

        assertEquals("bl", plan.get("type"));
        assertNull(plan.get("totals"), "la clé « totals » est absente d'un bon de livraison");
        aucunMotDArgentDansLesTableaux(plan, bl.getNumber());
        assertTrue(String.valueOf(plan.get("footer")).contains("aucun montant"),
                "le pied de page le dit sur chaque feuille");
    }

    /**
     * Rien d'inventé : une identité que la configuration ne donne pas s'imprime avec son
     * étiquette et un tiret. Un « RC : 0000 » fabriqué pour remplir la page vaudrait pire
     * qu'une case vide.
     */
    @Test
    void len_tete_garde_ses_etiquettes_meme_non_configures() {
        Map<String, Object> plan = documentService.payload(ordreEnCours().getId());
        List<String> entete = entete(plan);

        assertTrue(entete.stream().anyMatch(l -> l.startsWith("RC :")), entete.toString());
        assertTrue(entete.stream().anyMatch(l -> l.startsWith("Matricule fiscal :")), entete.toString());
        assertNotNull(plan.get("brand"), "le nom de l'atelier vient de la configuration, jamais de l'écran");
        assertTrue(String.valueOf(plan.get("subtitle")).contains("l'atelier exécute"),
                String.valueOf(plan.get("subtitle")));
    }

    /**
     * Le nom que le papier porte vient du clic, pas d'une case tapée sur la feuille : avant le
     * clic les cases sont vides, après le clic la relure du même plan imprime le signataire —
     * sans redémarrer quoi que ce soit entre les deux.
     */
    @Test
    void le_nom_imprime_sur_le_bon_vient_du_clic_en_base() {
        MaintenanceItem bl = bonDepuis(ordreClos());
        joindreLeScan(bl);

        Map<String, Object> avant = documentService.payload(bl.getId());
        assertTrue(String.valueOf(avant.get("badge")).contains("NON signé"), String.valueOf(avant.get("badge")));
        assertTrue(signatures(avant).stream().allMatch(c -> String.valueOf(c.get("name")).isBlank()),
                "aucune case n'est préremplie : une signature que l'atelier aurait écrite n'en est pas une");
        assertNull(relireSignataire(bl.getId()), "la colonne est vide avant le clic");

        bons.signer(bl.getId(), new SignatureRequest("Moez Ben Salah", "Directeur d'exploitation", null), jeton());

        Map<String, Object> apres = documentService.payload(bl.getId());
        assertEquals("Moez Ben Salah", relireSignataire(bl.getId()));
        assertTrue(String.valueOf(apres.get("badge")).contains("Bon de livraison signé"),
                String.valueOf(apres.get("badge")));
        assertEquals("État : Signé", apres.get("status_label"));
        assertTrue(signatures(apres).stream().anyMatch(c -> "Moez Ben Salah".equals(c.get("name"))),
                "le client qui a repris le bus se nomme sur la feuille");
        // Le bon signé ne se resigne pas : le serveur répond donc « pret : false » avec le motif
        // du second clic. La feuille d'archive ne doit pas la répéter sous « Bon de livraison
        // signé » — vu sur son BL-2026-0094, où le bandeau imprimait « Signature refusée ».
        assertFalse(String.valueOf(apres.get("badge")).contains("refusée"),
                String.valueOf(apres.get("badge")));
        assertFalse(apres.toString().contains("Pour que la signature passe"),
                "un papier signé n'a plus rien à attendre avant de signer");
    }

    private Object relireSignataire(String id) {
        em.flush();
        return base.queryForMap("select signataire_nom from maintenance_items where id = ?", id)
                .get("signataire_nom");
    }

    /**
     * La route, jouée en base : des octets PDF, un nom de fichier qui suit la NATURE du papier,
     * et le journal qui dit si de l'argent est sorti de la maison. C'est la trace que l'on
     * cherche six mois plus tard, quand une facture se discute.
     */
    @Test
    void la_route_du_document_rend_un_pdf_et_le_journal_nomme_l_argent() {
        MaintenanceItem bl = bonDepuis(ordreClos());
        MaintenanceItem devis = devisChiffre();

        byte[] octetsBon;
        byte[] octetsDevis;
        try {
            octetsBon = miseEnPage.render(documentService.payload(bl.getId()));
            octetsDevis = miseEnPage.render(documentService.payload(devis.getId()));
        } catch (ResponseStatusException indisponible) {
            // Le poste qui lance la suite n'a pas toujours la mise en page (Python ou reportlab
            // absent) : c'est un 503 français qui se lit chez lui, pas un échec de l'essai.
            Assumptions.abort("Mise en page indisponible sur ce poste : " + indisponible.getReason());
            return;
        }

        assertTrue(new String(octetsBon, 0, 5, StandardCharsets.UTF_8).equals("%PDF-"),
                "la tête des octets dit un PDF");
        assertTrue(octetsBon.length > 1024, "et ce n'est pas une page vide");
        assertTrue(new String(octetsDevis, 0, 5, StandardCharsets.UTF_8).equals("%PDF-"));

        var reponseBon = controller.document(bl.getId(), jeton());
        var reponseDevis = controller.document(devis.getId(), jeton());

        assertTrue(reponseBon.getStatusCode().is2xxSuccessful(), reponseBon.toString());
        String disposition = String.valueOf(reponseBon.getHeaders().getFirst("Content-Disposition"));
        assertTrue(disposition.contains(bl.getNumber()), disposition);
        assertTrue(disposition.contains("bon-de-livraison"),
                "le nom du fichier suit la nature du papier, pas son numéro : " + disposition);

        assertNotNull(detailDuJournal(bl.getId(), "Édition du document"), "la sortie d'impression se sait");
        assertTrue(detailDuJournal(bl.getId(), "Édition du document").contains("aucun montant"),
                detailDuJournal(bl.getId(), "Édition du document"));
        assertTrue(detailDuJournal(devis.getId(), "Édition du document").contains("avec les montants"),
                detailDuJournal(devis.getId(), "Édition du document"));
    }

    /**
     * Un papier sans nature en base ne s'imprime pas « quand même » : retomber sur le gabarit du
     * devis enverrait des prix sur un papier dont personne ne sait ce qu'il rend. Ce que la base
     * dit ici, et qu'un test à mocks tait : la colonne {@code type} est NOT NULL, donc une feuille
     * sans nature ne peut même pas exister — le 409 du service est le garde-fou du rangé d'avant,
     * pas une porte de sortie pour l'atelier.
     */
    @Test
    void la_base_ne_peut_porter_un_papier_sans_nature() {
        em.flush();
        Boolean obligatoire = base.queryForObject(
                "select is_nullable = 'NO' from information_schema.columns "
                        + "where table_name = 'maintenance_items' and column_name = 'type'",
                Boolean.class);

        assertEquals(Boolean.TRUE, obligatoire,
                "la colonne « type » doit être NOT NULL : sans elle un papier s'imprimerait au hasard d'un gabarit");
    }

    /**
     * Le compteur que le client discute à la restitution vient de la fiche du bus, et le papier
     * le relit à l'impression : c'est la preuve que le PDF n'est pas une photo de l'écran. Il
     * passe par la porte unique du compteur ({@code VehicleMileageService}) — une écriture en SQL
     * contournant la porte ne prouverait que SQL.
     */
    @Test
    void le_bon_imprime_le_compteur_de_la_fiche_du_bus() {
        MaintenanceItem bl = bonDepuis(ordreClos());
        Vehicle bus = vehicles.findById(bl.getVehicleId()).orElseThrow();
        long nouveau = (bus.getMileage() == null ? 0L : bus.getMileage()) + 5000L;
        compteur.saisieAtelier(bus, nouveau, "Relève contradictoire réglée à l'atelier", jeton());
        em.flush();
        em.clear();

        String papierImprime = documentService.payload(bl.getId()).toString();

        assertTrue(papierImprime.contains(nouveau + " km"),
                "le compteur monté par la porte unique se relit sur le papier : " + papierImprime);
    }
}
