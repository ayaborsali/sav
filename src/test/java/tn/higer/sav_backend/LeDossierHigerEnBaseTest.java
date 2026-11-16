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

import tn.higer.sav_backend.dto.InterventionDtos.ClosureRequest;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPartRequest;
import tn.higer.sav_backend.model.InterventionPurpose;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.ItemPart;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPartRequestRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.ItemPartRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AgPaperAssemblyService;
import tn.higer.sav_backend.service.AgPaperDocumentService;
import tn.higer.sav_backend.service.AgPaperPdfService;
import tn.higer.sav_backend.service.InterventionService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Le dossier d'appel en garantie, écrit et relu dans la vraie base.
 *
 * La leçon de V30 tient en une ligne : une suite entièrement bouchonnée n'a jamais
 * envoyé un seul UPDATE à PostgreSQL, et le 500 de « j'arrive » est passé entre les
 * mailles de 546 tests verts. Ce fichier fait donc partir les écritures réelles —
 * la clôture d'une sortie qui réclame ses pièces au dossier — et relit les tables en
 * SQL, puis il laisse la lecture de Higer marcher sur ces lignes.
 *
 * Ce qu'il prouve ne se prouve nulle part ailleurs :
 *  - que {@code intervention_part_requests.sav_case_id} reçoit réellement le dossier
 *    à la clôture, dans la bonne table et la bonne colonne ;
 *  - qu'une pièce déjà réclamée par un autre dossier n'est pas déplacée ;
 *  - que les vingt-huit lignes de {@link AgPaperAssemblyService} ne se cassent pas sur
 *    un dossier dont le bus n'existe plus (le NPE du 26/10 est de la même famille) ;
 *  - que le mot « POSEE » et l'état d'une ligne passent la contrainte CHECK de V31 ;
 *  - que le compte de consultation est arrêté par la vraie garde de rôle, pas par un
 *    menu caché ;
 *  - que la feuille d'appel (P6e) se construit bien sur ces lignes — VIN, compteur,
 *    numéro de sortie — et que le vrai pont Python/reportlab en écrit un PDF complet.
 *
 * Tout se passe dans une transaction que Spring annule : aucune ligne d'essai ne reste
 * dans la base de travail, et aucun papier ne part chez le constructeur.
 */
@SpringBootTest
@Transactional
class LeDossierHigerEnBaseTest {

    @Autowired private AgPaperAssemblyService lecture;
    @Autowired private AgPaperDocumentService feuille;
    @Autowired private AgPaperPdfService miseEnPage;
    @Autowired private InterventionService sorties;
    @Autowired private SavCaseRepository dossiers;
    @Autowired private InterventionRepository interventions;
    @Autowired private InterventionPartRequestRepository pieces;
    @Autowired private ItemPartRepository montees;
    @Autowired private MaintenanceRepository papiers;
    @Autowired private VehicleRepository vehicles;
    @Autowired private TechnicianRepository techniciens;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    private static Authentication consultation() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSULTATION")));
    }

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "chef.atelier@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Un suffixe court, posé sur chaque identifiant d'essai : les numéros sont uniques. */
    private String marque() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Vehicle unBus() {
        List<Vehicle> bus = vehicles.findAll().stream().filter(v -> v.getId() != null).toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus en base : le dossier n'a rien à justifier.");
        return bus.get(0);
    }

    private Technician unHomme() {
        List<Technician> hommes = techniciens.findAll().stream()
                .filter(t -> t.getId() != null && t.isActive()).toList();
        Assumptions.assumeTrue(!hommes.isEmpty(), "Aucun technicien actif en base.");
        return hommes.get(0);
    }

    /** Un appel en garantie neuf, posé sur un bus qui existe. */
    private SavCase garantie(Vehicle bus, String m) {
        SavCase c = new SavCase();
        c.setId("sav-essai-" + m);
        c.setCaseNumber("SAV-WAR-ESSAI-" + m);
        c.setType(SavCaseType.GARANTIES);
        c.setTitle("Appel en garantie d'essai — " + m);
        c.setVehicleId(bus.getId());
        c.setClientId(bus.getClientId());
        c.setStatus(SavCaseStatus.OPEN);
        c.setCreatedAt(LocalDate.now());
        return dossiers.saveAndFlush(c);
    }

    /** La sortie rentrée de l'atelier, déjà rattachée au dossier, prête à clôturer. */
    private Intervention sortieRetournee(Vehicle bus, Technician homme, String dossierId, String m) {
        Intervention i = new Intervention();
        i.setId("itv-essai-" + m);
        i.setNumber("DP-ESSAI-" + m);
        i.setStatus(InterventionStatus.RETURNED);
        i.setPurpose(InterventionPurpose.PANNE);
        i.setVehicleId(bus.getId());
        i.setClientId(bus.getClientId());
        i.setTechnicianId(homme.getId());
        i.setSavCaseId(dossierId);
        i.setReportedAt(LocalDateTime.now().minusHours(6));
        i.setReturnedAt(LocalDateTime.now().minusHours(1));
        i.setBusMileage(148320L);
        i.setDescription("Panne relevée devant le bus, alternateur changé.");
        i.setCreatedAt(LocalDateTime.now().minusHours(6));
        return interventions.saveAndFlush(i);
    }

    /** La pièce vue dehors, avec ou sans dossier au dos. */
    private InterventionPartRequest pieceVue(Vehicle bus, String sortieId, String dossierId, String m) {
        InterventionPartRequest p = new InterventionPartRequest();
        p.setId("ipr-essai-" + m);
        p.setInterventionId(sortieId);
        p.setVehicleId(bus.getId());
        p.setSavCaseId(dossierId);
        p.setReference("37300-10300");
        p.setDesignation("Alternateur 28V 120A");
        p.setReason("Charge mesurée à 24 V, régulateur hors service");
        p.setQuantityOrdered(1);
        p.setState(PartRequestState.LIVREE);
        p.setQuantityReceived(1);
        p.setDemandedBy("technicien@higerbus.tn");
        p.setCreatedAt(LocalDateTime.now().minusHours(5));
        return pieces.saveAndFlush(p);
    }

    private String dossierAuDosDe(String pieceId) {
        em.flush();
        return base.queryForObject("select sav_case_id from intervention_part_requests where id = ?",
                String.class, pieceId);
    }

    // ---------------------------------------------------------------- P6a : l'écriture

    /**
     * La clôture de la sortie écrit le dossier au dos de la pièce. C'est la colonne
     * {@code sav_case_id} de {@code intervention_part_requests} qui décide si une
     * marchandise figure dans un papier AG : sans elle, la pièce est restée un papier
     * de technicien, et le constructeur ne rembourse rien.
     */
    @Test
    void laClotureDeLaSortieEcritLeDossierAuDosDeLaPiece() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);
        Intervention sortie = sortieRetournee(bus, unHomme(), dossier.getId(), m);
        InterventionPartRequest piece = pieceVue(bus, sortie.getId(), null, m);

        sorties.close(sortie.getId(),
                new ClosureRequest("Pris en charge garantie", null, null, null,
                        "clichés relevés au poste le lendemain"), jeton(), chef());

        assertEquals(dossier.getId(), dossierAuDosDe(piece.getId()),
                "la pièce vue devant le bus doit devenir la marchandise du dossier");
        Integer trace = base.queryForObject("select count(*) from intervention_events"
                + " where intervention_id = ? and note like '%réclamée%'", Integer.class, sortie.getId());
        assertEquals(1, trace, "le journal de la sortie doit dire ce que la clôture a rattaché");
    }

    /**
     * Une pièce déjà portée par un dossier n'est pas déplacée vers celui qui clôture
     * maintenant : deux appels ne peuvent pas réclamer la même marchandise, et le
     * premier dossier perdrait une pièce déjà engagée chez le constructeur.
     */
    @Test
    void unePieceDejaReclameeGardeLeDossierQuiLaDemandeeEnPremier() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase premier = garantie(bus, m + "a");
        SavCase second = garantie(bus, m + "b");
        Intervention sortie = sortieRetournee(bus, unHomme(), second.getId(), m);
        InterventionPartRequest piece = pieceVue(bus, sortie.getId(), premier.getId(), m);

        sorties.close(sortie.getId(), new ClosureRequest("ok", null, null, null,
                "clichés au poste"), jeton(), chef());

        assertEquals(premier.getId(), dossierAuDosDe(piece.getId()),
                "le rattachement ne se vole pas : la première demande tient la pièce");
    }

    /**
     * Le dossier au dos de la sortie n'existe plus : la clôture passe quand même. Une
     * écriture refusée à cause d'un lien cassé punirait l'homme qui rentre d'un papier
     * qu'un autre a supprimé — et la pièce resterait dehors.
     */
    @Test
    void laClotureRestePossibleQuandLeDossierARetEsupprime() {
        Vehicle bus = unBus();
        String m = marque();
        Intervention sortie = sortieRetournee(bus, unHomme(), "sav-inexistant-" + m, m);
        InterventionPartRequest piece = pieceVue(bus, sortie.getId(), null, m);

        sorties.close(sortie.getId(), new ClosureRequest("ok", null, null, null,
                "clichés au poste"), jeton(), chef());

        em.flush();
        assertNull(dossierAuDosDe(piece.getId()),
                "rien n'est rattaché à un dossier qui n'existe pas : rien n'est inventé");
        assertEquals("closed", base.queryForObject("select status from interventions where id = ?",
                String.class, sortie.getId()).toLowerCase());
    }

    // ---------------------------------------------------------------- P6b : la lecture

    /**
     * Le papier relit la sortie clôturée et la pièce que la clôture a rattachée : les
     * deux viennent des tables, aucun n'a été recopié à l'écran. L'état de la ligne
     * (« Livrée », donc pas encore montée) devient un manquant écrit en français.
     */
    @Test
    void lePapierRelitLaSortieEtLaPieceQueLaClotureAReclamees() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);
        Intervention sortie = sortieRetournee(bus, unHomme(), dossier.getId(), m);
        pieceVue(bus, sortie.getId(), null, m);
        sorties.close(sortie.getId(), new ClosureRequest("ok", null, null, null,
                "clichés au poste"), jeton(), chef());

        AgPapierJustifie lu = lecture.relire(dossier.getId(), chef());

        assertEquals(dossier.getCaseNumber(), lu.caseNumber());
        assertEquals(List.of("DP-ESSAI-" + m),
                lu.sorties().stream().map(t -> t.number()).toList());
        assertTrue(lu.sorties().get(0).terminee());
        assertEquals(148320L, lu.sorties().get(0).kmReleve());
        assertEquals(1, lu.pieces().size());
        assertEquals("livree", lu.pieces().get(0).etat());
        assertEquals("37300-10300", lu.pieces().get(0).reference());
        assertFalse(lu.pret(), "un papier dont la pièce n'est pas montée n'est pas prêt");
        assertTrue(lu.manquants().stream().anyMatch(x -> x.contains("pas encore montée")),
                lu.manquants().toString());
    }

    /**
     * La pièce montée sur un papier d'atelier : sa référence, sa date de montage et son
     * sort (repartie chez le constructeur ou gardée avec motif écrit) se lisent dans les
     * colonnes de V21, et le numéro du papier vient de {@code maintenance_items}.
     */
    @Test
    void laPieceMonteeSurPapierDAtelierPorteSonNumeroDePapierEtSonRetour() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);

        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("mti-essai-" + m);
        papier.setType(MaintenanceType.OPERATION);
        papier.setNumber("OR-ESSAI-" + m);
        papier.setVehicleId(bus.getId());
        papiers.saveAndFlush(papier);

        Intervention sortie = sortieRetournee(bus, unHomme(), dossier.getId(), m);
        sortie.setStatus(InterventionStatus.CLOSED);
        sortie.setMaintenanceItemId(papier.getId());
        interventions.saveAndFlush(sortie);

        ItemPart montee = new ItemPart();
        montee.setId("ipt-essai-" + m);
        montee.setMaintenanceItemId(papier.getId());
        montee.setVehicleId(bus.getId());
        montee.setSavCaseId(dossier.getId());
        montee.setReference("2115U");
        montee.setDesignation("Pompe à eau");
        montee.setQuantity(1.0);
        montee.setFittedOn(LocalDate.of(2026, 10, 12));
        montee.setReturned(false);
        montee.setReturnReason("Consignée au magasin, enlèvement par l'importateur à programmer");
        montees.saveAndFlush(montee);

        AgPapierJustifie lu = lecture.relire(dossier.getId(), chef());

        assertEquals("OR-ESSAI-" + m, lu.pieces().get(0).atelierPaperNumber());
        assertEquals("DP-ESSAI-" + m, lu.pieces().get(0).sortieNumber());
        assertEquals("montee", lu.pieces().get(0).etat());
        assertFalse(lu.pieces().get(0).retourEnvoye());
        assertTrue(lu.manquants().stream().anyMatch(x -> x.contains("n'est pas repartie")
                && x.contains("enlèvement")), lu.manquants().toString());
    }

    /**
     * Le bus du dossier a été supprimé du portail : la fiche n'existe plus, et la
     * lecture doit rendre quand même. C'est le défaut qui a produit le 500 du GET des
     * dossiers le 26/10 — une carte vide et une clef absente — et il se rejoue ici sur
     * la vraie base.
     */
    @Test
    void unDossierDontLeBusNaPlusDeFicheSeLitSans500() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);
        dossier.setVehicleId("veh-inexistant-" + m);
        dossiers.saveAndFlush(dossier);

        AgPapierJustifie lu = lecture.relire(dossier.getId(), chef());

        assertFalse(lu.pret());
        assertTrue(lu.manquants().stream().anyMatch(x -> x.contains("Aucune sortie")),
                lu.manquants().toString());
    }

    /**
     * La garde de rôle se lit par la vraie route, pas par un menu : un compte de
     * consultation ne relit pas le dossier d'un constructeur, même si la table lui est
     * autrement accessible.
     */
    @Test
    void unCompteDeConsultationArreteLaLectureDuPapier() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> lecture.relire(dossier.getId(), consultation()));

        assertEquals(403, e.getStatusCode().value());
        assertNotNull(lecture.relire(dossier.getId(), chef()),
                "le même papier se lit au chef d'atelier : c'est le rôle, pas la ligne d'essai");
    }

    /** Une visite, une réclamation, une commande : ces familles-là n'ont pas de justificatifs. */
    @Test
    void uneAutreFamilleDeDossierNaPasDePapierAG() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase visite = garantie(bus, m);
        visite.setType(SavCaseType.VISITES);
        dossiers.saveAndFlush(visite);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> lecture.relire(visite.getId(), chef()));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains(visite.getCaseNumber()), e.getReason());
    }

    /**
     * Le barème de remboursement de Higer n'a jamais été fourni : la liste des manques
     * nomme la case « montant demandé » et le dit, elle ne le calcule pas. Ce test est
     * là pour qu'un total fabriqué par le portail ne passe jamais inaperçu.
     */
    @Test
    void leMontantDemandeEstNommeMaisJamaisCalcule() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);

        AgPapierJustifie lu = lecture.relire(dossier.getId(), chef());

        assertTrue(lu.manquants().stream().anyMatch(x -> x.contains("montant demandé")
                && x.contains("barème")), lu.manquants().toString());
        Map<String, Object> ligne = base.queryForMap(
                "select claim_amount_claimed from sav_cases where id = ?", dossier.getId());
        assertNull(ligne.get("claim_amount_claimed"),
                "la case reste vide : le portail n'additionne pas à la place du guichet");
    }

    // ---------------------------------------------------------------- P6e : la feuille

    /**
     * La feuille d'appel se construit sur les lignes vraies de la base : le VIN vient de
     * {@code vehicles}, la sortie de {@code interventions}, la pièce des deux tables de
     * pièces. C'est la preuve que le document ne dépend pas d'un plan inventé pour le
     * test — et que rien de ce que le constructeur lira n'a été ressaisi pour lui.
     */
    @Test
    @SuppressWarnings("unchecked")
    void laFeuilleSeConstruitSurLesLignesDeLaBase() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);
        Intervention sortie = sortieRetournee(bus, unHomme(), dossier.getId(), m);
        pieceVue(bus, sortie.getId(), null, m);
        sorties.close(sortie.getId(), new ClosureRequest("ok", null, null, null,
                "clichés au poste"), jeton(), chef());

        dossier.setAgWriterName("Mounir Bouazizi");
        dossier.setAgRepairOrderNumber("OR-ESSAI-" + m);
        dossier.setClaimAmountClaimed(1200.0);
        dossiers.saveAndFlush(dossier);

        Map<String, Object> plan = feuille.payload(dossier.getId(), chef());

        assertEquals(dossier.getCaseNumber(), plan.get("number"));
        assertEquals("Mounir Bouazizi", valeurDe(plan, "Qui écrit le papier, et sur quel ordre",
                "Nom du rédacteur"));
        assertEquals("1 200", valeurDe(plan, "La demande", "Montant demandé"),
                "le montant saisi se recopie, il ne se transforme pas");
        assertEquals("non écrit", valeurDe(plan, "La demande", "Montant accordé"),
                "une case d'argent vide ne devient pas un zéro");
        assertEquals("—", valeurDe(plan, "Le bus chez Higer", "Importateur"),
                "le portail n'invente pas le code de l'importateur");

        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        List<List<String>> lignesDeSortie = (List<List<String>>) tables.get(0).get("rows");
        assertEquals("DP-ESSAI-" + m, lignesDeSortie.get(0).get(0));
        assertTrue(lignesDeSortie.get(0).get(3).contains("148 320 km"), lignesDeSortie.get(0).toString());

        List<String> manquants = (List<String>) plan.get("manquants");
        assertTrue(manquants.contains(lecture.relire(dossier.getId(), chef()).manquants().get(0)),
                "la liste imprimée est celle que la base rend, pas une liste d'à-côté");
    }

    /**
     * Le vrai pont jusqu'à reportlab, sur les lignes vraies de la base : le document
     * écrit chez le constructeur est bien un PDF complet. Un gabarit qui ne se teste que
     * sur des plans fabriqués de la main peut casser sur une valeur réelle — un numéro de
     * pièce avec une accolade, une cause de trois lignes, un compteur à six chiffres.
     */
    @Test
    void laVraieMiseEnPageEcritLePapierDeCeDossier() {
        Assumptions.assumeTrue(reportlabPresent(), "Python 3 ou reportlab absent de cette machine");
        Vehicle bus = unBus();
        String m = marque();
        SavCase dossier = garantie(bus, m);
        dossier.setAgCause("Défaut de fabrication du régulateur intégré, contrôlé au banc.");
        dossiers.saveAndFlush(dossier);

        byte[] pdf = miseEnPage.render(feuille.payload(dossier.getId(), chef()));

        assertTrue(pdf.length > 1000, "une feuille A4 remplie fait plusieurs kilo-octets : " + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        String queue = new String(pdf, pdf.length - 64, 64, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(queue.contains("%%EOF"), "le papier doit être terminé, pas tronqué : " + queue);
    }

    /** Une visite n'a pas de papier pour Higer : la route refuse avant la mise en page. */
    @Test
    void uneAutreFamilleNeRecoitPasDeFeuille() {
        Vehicle bus = unBus();
        String m = marque();
        SavCase visite = garantie(bus, m);
        visite.setType(SavCaseType.VISITES);
        dossiers.saveAndFlush(visite);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> feuille.payload(visite.getId(), chef()));

        assertEquals(400, e.getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    private static String valeurDe(Map<String, Object> plan, String rubrique, String libelle) {
        List<Map<String, Object>> boxes = (List<Map<String, Object>>) plan.get("boxes");
        return boxes.stream().filter(b -> rubrique.equals(b.get("heading"))).flatMap(b ->
                        ((List<List<String>>) b.get("rows")).stream())
                .filter(r -> libelle.equals(r.get(0)))
                .findFirst()
                .map(r -> r.get(1))
                .orElseThrow(() -> new AssertionError("case absente de la feuille : "
                        + rubrique + " / " + libelle));
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
