package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tn.higer.sav_backend.controller.PieceCatalogController;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceFamilyOption;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportCommit;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportLine;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportSummary;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceRequest;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceResponse;
import tn.higer.sav_backend.service.PieceCatalogImportService;
import tn.higer.sav_backend.service.PieceCatalogService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le catalogue de pièces de rechange écrit et relu dans la vraie base (V40, V41 et V44).
 *
 * Ce que ces tests-ci vérifient, au-delà des tests du service : que PostgreSQL
 * accepte les colonnes que le Java déclare (c'est le trou de V30 — six des huit
 * états du dossier connus de la base, un 500 sur « j'arrive », et la suite verte
 * sans s'en apercevoir), que les prix dérivés sortent du calcul serveur au millime
 * près, qu'une référence ne se dédouble jamais, ni à la saisie ni à l'import, et que
 * la famille se range dans une colonne que la base garde remplie et fermée à sept
 * mots.
 *
 * Ce qui compte aussi, parce que c'est le métier : une pièce retirée du catalogue
 * reste relisible — un devis ou un ordre d'avant cite sa référence — et un compte
 * sans droit aux chiffres commerciaux ne reçoit pas les montants, même s'il lit la
 * ligne. La famille, elle, se lit : ce n'est pas un chiffre d'argent.
 *
 * Chaque pièce porte une référence marquée à part ; la transaction est annulée par
 * Spring, la base de l'atelier retrouve ses papiers tels qu'avant.
 */
@SpringBootTest
@Transactional
class LeCatalogueDePiecesEnBaseTest {

    @Autowired private PieceCatalogService catalogue;
    @Autowired private PieceCatalogImportService importeur;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;
    // Le nom est obligatoire : le contexte tient aussi le mapping des endpoints
    // d'actuator, et les deux se ressemblent.
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

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

    /** Une référence d'essai, unique à chaque test : la colonne est unique en base. */
    private static String ref() {
        return "ESSAI-PR-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Le coefficient de vente des pièces d'essai : 148,780 × 1,5000 = 223,170. */
    private static final String COEFFICIENT_VENTE = "1.5000";

    private static PieceRequest demande(String reference, String designation, String exWorks,
                                        String coefficientRevient) {
        return demande(reference, designation, "MECANIQUE", exWorks, coefficientRevient, COEFFICIENT_VENTE);
    }

    /**
     * Les deux coefficients se passent dans la demande comme le prix Ex Works : ce sont
     * des cases que l'atelier remplit. Aucun prix calculé n'y entre — ni le revient ni
     * la vente, que le serveur refait toujours. La famille, elle, ne connaît pas de
     * {@code null} : la colonne est gardée remplie en base.
     *
     * Depuis V47 le coefficient de vente est obligatoire ; {@code null} ici veut donc
     * dire « le coefficient d'essai », pas « pas de prix de vente » — cet état-là
     * n'existe plus pour une pièce écrite.
     */
    private static PieceRequest demande(String reference, String designation, String famille,
                                        String exWorks, String coefficientRevient, String coefficientVente) {
        return new PieceRequest(reference, designation, "Fuel filter", famille, new BigDecimal(exWorks),
                new BigDecimal(coefficientRevient),
                new BigDecimal(coefficientVente == null ? COEFFICIENT_VENTE : coefficientVente),
                "Higer Parts", "EUR");
    }

    /**
     * La demande telle que l'écran de saisie l'envoie depuis le lot « type » : la case
     * de nature est pleine ({@code Mecanique} ou {@code Electrique}), ou vide quand
     * l'atelier ne l'a pas encore classée.
     */
    private static PieceRequest demandeAvecType(String reference, String designation, String famille,
                                                String typePiece, String exWorks, String coefficientRevient) {
        return new PieceRequest(reference, designation, null, famille, typePiece, new BigDecimal(exWorks),
                new BigDecimal(coefficientRevient), new BigDecimal(COEFFICIENT_VENTE), "Higer Parts", "EUR");
    }

    private PieceResponse ajoute(String reference, String designation, String exWorks,
                                 String coefficientRevient) {
        return catalogue.create(demande(reference, designation, exWorks, coefficientRevient), jeton(), true);
    }

    private PieceResponse ajoute(String reference, String designation, String exWorks,
                                 String coefficientRevient, String coefficientVente) {
        return catalogue.create(demande(reference, designation, "MECANIQUE", exWorks, coefficientRevient,
                coefficientVente), jeton(), true);
    }

    private Map<String, Object> ligne(String reference) {
        em.flush();
        return base.queryForMap("select * from piece_rechanges where reference = ?", reference);
    }

    @Test
    void unePieceEnregistreeSortSonPrixDeRevientCalcule() {
        PieceResponse piece = ajoute(ref(), "Filtre à gasoil", "86.500", "1.7200");

        Map<String, Object> rangee = ligne(piece.reference());
        assertEquals("Filtre à gasoil", rangee.get("designation_fr"));
        assertEquals("EUR", rangee.get("devise"));
        assertEquals("MECANIQUE", rangee.get("famille"), "La famille demandée est la famille stockée.");
        // Les deux prix sont des colonnes, mais c'est le serveur qui les a écrites :
        // 86,500 × 1,7200 = 148,780 de revient, puis 148,780 × 1,5000 = 223,170 de
        // vente. Aucun des deux n'est venu du navigateur.
        assertEquals(new BigDecimal("148.780"), (BigDecimal) rangee.get("prix_revient"));
        assertEquals(new BigDecimal("1.5000"), (BigDecimal) rangee.get("coefficient_vente"));
        assertEquals(new BigDecimal("223.170"), (BigDecimal) rangee.get("prix_vente"));
        assertEquals(Boolean.TRUE, rangee.get("active"));
        assertNotNull(rangee.get("created_at"));
        assertNotNull(rangee.get("updated_at"));

        // Et la piste d'audit dit qui a ajouté quoi, avec le montant qui a servi.
        Integer trace = base.queryForObject(
                "select count(*) from audit_logs where entity_type = 'PIECE_RECHANGE' and entity_id = ? "
                        + "and action = 'CREATE' and actor_email = 'guichet@higer.tn'",
                Integer.class, piece.id());
        assertEquals(1, trace);
    }

    @Test
    void uneDeviseVideDevientEUREtUneDeviseInconnueEstRefusee() {
        String reference = ref();
        PieceResponse piece = catalogue.create(new PieceRequest(reference, "Filtre à gasoil", "Fuel filter",
                "MECANIQUE", new BigDecimal("86.500"), new BigDecimal("1.7200"), new BigDecimal("1.5000"),
                "Higer Parts", null),
                jeton(), true);

        assertEquals("EUR", piece.devise(), "Une case vide vaut EUR, jamais une pièce sans devise.");
        assertEquals("EUR", ligne(reference).get("devise"));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> catalogue.create(new PieceRequest(ref(), "Filtre à gasoil", null, "MECANIQUE",
                        new BigDecimal("86.500"), new BigDecimal("1.7200"), new BigDecimal("1.5000"),
                        "Higer Parts", "GBP"),
                        jeton(), true));
        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refus.getReason().contains("EUR"), refus.getReason());
    }

    /**
     * La famille est une colonne que le serveur écrit, pas une étiquette d'écran : ce
     * qui compte ici, c'est que « MECANIQUE » sorte de la demande, traverse le service
     * et se relise en SQL telle quelle — une famille perdue en route se verrait refusée
     * par la base (colonne NOT NULL), ou pire rangée autre part.
     */
    @Test
    void laFamilleSeRangeEnBaseEtSeRelitTelleQuelle() {
        String reference = ref();
        PieceResponse piece = catalogue.create(
                demande(reference, "Plaquette de frein AV", "FREINAGE", "124.380", "1.6800", null),
                jeton(), true);

        assertEquals("FREINAGE", piece.famille());
        assertEquals("FREINAGE", ligne(reference).get("famille"));

        // Remaniée à l'écran : la colonne suit la demande, l'ancienne valeur ne reste
        // pas cachée sous la nouvelle (le PUT remplace la ligne telle que la demande la dit).
        catalogue.update(piece.id(), demande(reference, "Plaquette de frein AV", "freinage",
                "124.380", "1.6800", null), jeton(), true);
        assertEquals("FREINAGE", ligne(reference).get("famille"),
                "Les accents et la casse du classeur ne fabricent pas une deuxième famille.");
    }

    /**
     * Une pièce sans famille ou avec un mot hors liste est refusée avant d'atteindre la
     * base, et le refus nomme les sept mots : l'atelier sait ce qu'il doit taper. Le
     * repli sur « Divers » n'existe pas, et ce test en est la preuve — « Carrosserie »
     * ne devient rien du tout.
     */
    @Test
    void uneFamilleVideOuHorsListeEstRefuseeSansRienEcrire() {
        String referenceVide = ref();
        String referenceInconnue = ref();
        ResponseStatusException sansFamille = assertThrows(ResponseStatusException.class,
                () -> catalogue.create(demande(referenceVide, "Filtre à gasoil", null, "86.500", "1.7200", null),
                        jeton(), true));
        assertEquals(HttpStatus.BAD_REQUEST, sansFamille.getStatusCode());
        assertTrue(sansFamille.getReason().contains("Mécanique"), sansFamille.getReason());

        ResponseStatusException motInconnu = assertThrows(ResponseStatusException.class,
                () -> catalogue.create(demande(referenceInconnue, "Filtre à gasoil", "Carrosserie",
                        "86.500", "1.7200", null), jeton(), true));
        assertTrue(motInconnu.getReason().contains("Carrosserie"), motInconnu.getReason());
        assertTrue(motInconnu.getReason().contains("Pont arrière"), motInconnu.getReason());

        // Les références de l'essai, pas la désignation : « Filtre à gasoil » est déjà
        // au catalogue de l'atelier, et un comptage qui la viserait mesurerait SES
        // lignes plutôt que le refus.
        assertEquals(0, nbLignes("select count(*) from piece_rechanges where reference in (?, ?)",
                referenceVide, referenceInconnue));
    }

    /**
     * La garde est en base, pas seulement dans le Java : une écriture qui contourne le
     * service — un sql à la main, un autre module — trouve le même refus. C'est la
     * leçon de V30, à l'envers : ici la base connaît bien la colonne et ses sept mots.
     */
    @Test
    void laBaseRefuseUnePieceSansFamille() {
        ResponseStatusException memeGarde = assertThrows(ResponseStatusException.class,
                () -> catalogue.create(demande(ref(), "Filtre", " ", "86.500", "1.7200", null), jeton(), true));
        assertTrue(memeGarde.getReason().contains("obligatoire"), memeGarde.getReason());

        // Une écriture refusée par PostgreSQL annule la transaction d'essai tout
        // entière : sans marque avant, la relise qui suit ne se jouerait pas et le
        // test échouerait sur « la transaction est annulée » au lieu de sa preuve.
        base.execute("savepoint refus_de_la_base");
        org.springframework.dao.DataIntegrityViolationException refus =
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> base.update("insert into piece_rechanges (id, reference, designation_fr, "
                                + "prix_ex_works, coefficient_revient, prix_revient, devise, active, "
                                + "created_at, updated_at) values (?, ?, ?, 1, 1.7, 1.7, 'EUR', true, now(), now())",
                                "prc-essai-sans-famille", ref(), "Filtre sans famille"));
        assertTrue(refus.getMostSpecificCause().getMessage().contains("famille"),
                "Le refus de la base doit nommer la colonne : " + refus.getMostSpecificCause().getMessage());
        base.execute("rollback to savepoint refus_de_la_base");
        assertEquals(0, nbLignes("select count(*) from piece_rechanges where id = ?",
                "prc-essai-sans-famille"));
    }

    /**
     * Le filtre par famille compte en base, pas sur la page affichée. Sans lui, l'écran
     * qui cherche « tout le Freinage » ne voit que 25 lignes et croit le catalogue
     * petit — alors que le même filtre sert à préparer une commande de pièces.
     */
    @Test
    void leFiltreParFamilleNeRendQueCetteFamille() {
        String marqueur = " essai " + UUID.randomUUID().toString().substring(0, 8);
        ajoute(ref(), "Plaquette AV" + marqueur, "10.000", "1.7000");
        catalogue.create(demande(ref(), "Plaquette AR" + marqueur, "FREINAGE", "11.000", "1.7000", null),
                jeton(), true);
        catalogue.create(demande(ref(), "Amortisseur" + marqueur, "SUSPENSION", "12.000", "1.7000", null),
                jeton(), true);

        assertEquals(1, catalogue.search(marqueur, "freinage", page(), true).getTotalElements(),
                "La famille se filtre sans accents et sans casse.");
        assertEquals(1, catalogue.search(marqueur, "SUSPENSION", page(), true).getTotalElements());
        assertEquals(3, catalogue.search(marqueur, null, page(), true).getTotalElements(),
                "Sans filtre, les trois lignes restent là.");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> catalogue.search(marqueur, "Pneumatique", page(), true));
        assertTrue(refus.getReason().contains("Pneumatique"), refus.getReason());
    }

    /**
     * La liste que l'écran met dans son select vient du serveur, et ses codes sont
     * exactement les mots que la base garde fermés. Un rayon ajouté par une migration
     * doit se proposer de lui-même : si l'écran recopiait les sept mots, il en aurait
     * six le jour du huitième, et une pièce rangeée ailleurs serait introuvable.
     */
    @Test
    void lesFamillesProposeesALeCranSontCellesQueLaBaseGarde() {
        assertEquals(List.of("MECANIQUE", "SUSPENSION", "ELECTRICITE", "FREINAGE",
                        "BOITE_VITESSE", "PONT_ARRIERE", "DIVERS"),
                catalogue.familles().stream().map(PieceFamilyOption::code).toList());
        assertEquals("Boîte de vitesse", catalogue.familles().get(4).label(),
                "Le code se stocke sans accent, le mot se montre avec.");

        // Chaque code rendu doit pouvoir lui être renvoyé comme filtre : c'est ce que
        // fait l'écran, select en place.
        for (PieceFamilyOption option : catalogue.familles()) {
            assertEquals(0, catalogue.search("aucune-piece-de-essai-" + UUID.randomUUID(),
                    option.code(), page(), true).getTotalElements(), option.code());
        }
    }

    /**
     * Le chemin que l'écran appelle, vérifié sur les vraies routes Spring et non sur le
     * service : « familles » doit se déposer devant le patron {@code /{id}}, sinon la
     * liste des sept mots se lirait comme l'identifiant d'une pièce — et l'écran, face
     * à un 200 vide, afficherait un select sans range. C'est le défaut du 04/11
     * ({@code bloc} contre {@code bon}) sous sa forme chemin.
     */
    @Test
    void laRouteDesFamillesDevanceCelleDeLaPiece() throws Exception {
        var demande = new MockHttpServletRequest("GET", "/api/pieces/familles");
        var chaine = mappings.getHandler(demande);

        assertTrue(chaine != null && chaine.getHandler() instanceof HandlerMethod,
                "/api/pieces/familles ne tombe sur aucun gestionnaire : l'écran n'aura rien à afficher.");
        var methode = (HandlerMethod) chaine.getHandler();
        assertEquals("familles", methode.getMethod().getName());
        assertEquals(PieceCatalogController.class, methode.getBeanType());
    }

    /**
     * Le « type » de l'atelier — les deux mots Mécanique / Électrique — traverse le
     * service jusqu'à la colonne, sous le nom canonique. L'écran renvoie le mot accentué
     * qu'il a choisi, le classeur d'à côté écrirait « electrique » : les deux doivent
     * ranger la même pièce dans la même nature, sinon la colonne ne veut plus rien dire.
     */
    @Test
    void leTypeSeRangeSousSonNomEtSeRelitAvecSonMot() {
        PieceResponse piece = catalogue.create(
                demandeAvecType(ref(), "Alternateur", "ELECTRICITE", "Électrique", "86.500", "1.7200"),
                jeton(), true);

        assertEquals("ELECTRIQUE", piece.typePiece(), "Le JSON rend le code, l'écran le traduit.");
        assertEquals("ELECTRIQUE", ligne(piece.reference()).get("type_piece"));

        // La casse et les accents du mot ne changent pas la range.
        PieceResponse autre = catalogue.create(
                demandeAvecType(ref(), "Boudin de cardan", "DIVERS", "ELECTRIQUE", "20.000", "1.9000"),
                jeton(), true);
        assertEquals("ELECTRIQUE", ligne(autre.reference()).get("type_piece"));
    }

    /**
     * Le type n'est pas la famille : la case peut rester vide, et le vide se lit
     * « à classer » à l'écran. Ce qui ne se fait pas, c'est un repli — une pièce sans
     * type ne devient pas « Mécanique » par défaut, le portail ne connaît pas sa
     * nature à sa place.
     */
    @Test
    void unePieceSansTypeResteAClasserSansRepli() {
        PieceResponse piece = catalogue.create(
                demandeAvecType(ref(), "Étrier de frein", "FREINAGE", null, "140.000", "1.6500"),
                jeton(), true);

        assertNull(piece.typePiece());
        assertNull(ligne(piece.reference()).get("type_piece"),
                "Une case vide s'écrit vide : « Mécanique » par défaut serait une nature inventée.");
    }

    /**
     * Un mot hors des deux se refuse, comme pour la famille, et le refus nomme les deux
     * mots du catalogue. « Hydraulique » ou « pneumatique » se discuterait en atelier et
     * passerait par une migration — pas par un classeur.
     */
    @Test
    void unTypeHorsDesDeuxMotsEstRefuse() {
        String reference = ref();
        ResponseStatusException refus = assertThrows(ResponseStatusException.class, () -> catalogue.create(
                demandeAvecType(reference, "Vérin", "PONT_ARRIERE", "hydraulique", "300.000", "1.5000"),
                jeton(), true));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refus.getReason().contains("hydraulique"), () -> refus.getReason());
        assertTrue(refus.getReason().contains("Mécanique") && refus.getReason().contains("Électrique"),
                () -> refus.getReason());
        assertEquals(0, nbLignes("select count(*) from piece_rechanges where reference = ?", reference));
    }

    /** Les deux mots que l'écran met dans son select viennent du serveur, pas d'un range du template. */
    @Test
    void lesTypesProposesALeCranSontCeuxQueLaBaseGarde() {
        assertEquals(List.of("MECANIQUE", "ELECTRIQUE"),
                catalogue.types().stream().map(PieceFamilyOption::code).toList());
        assertEquals("Électrique", catalogue.types().get(1).label());
    }

    /**
     * Le classeur d'import n'a pas de colonne « Type ». Il ne doit donc pas effacer ce
     * que l'atelier a classé à l'écran : une ligne remaniée garde sa nature, sinon
     * reimporter le fichier Higer du mois remettrait tout le catalogue à « à classer ».
     */
    @Test
    void leClasseurSansColonneTypeLaisseLeTypeDejaClasse() {
        String reference = ref();
        catalogue.create(demandeAvecType(reference, "Pompe à eau", "MECANIQUE", "Mécanique",
                "86.500", "1.7200"), jeton(), true);

        importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, demande(reference, "Pompe à eau", "MECANIQUE", "90.000", "1.7200", null),
                        false))), jeton());

        Map<String, Object> rangee = ligne(reference);
        assertEquals("MECANIQUE", rangee.get("type_piece"), "Le classeur ne dit rien du type : il ne l'efface pas.");
        assertEquals(new BigDecimal("154.800"), (BigDecimal) rangee.get("prix_revient"),
                "Les prix, eux, suivent bien le classeur.");
    }

    /**
     * Le chemin que l'écran appelle, vérifié sur les vraies routes Spring : « types »
     * doit se déposer devant le patron {@code /{id}}, sinon la liste des deux mots se
     * lirait comme l'identifiant d'une pièce et l'écran afficherait un select vide.
     */
    @Test
    void laRouteDesTypesDevanceCelleDeLaPiece() throws Exception {
        var demande = new MockHttpServletRequest("GET", "/api/pieces/types");
        var chaine = mappings.getHandler(demande);

        assertTrue(chaine != null && chaine.getHandler() instanceof HandlerMethod,
                "/api/pieces/types ne tombe sur aucun gestionnaire : l'écran n'aura rien à afficher.");
        var methode = (HandlerMethod) chaine.getHandler();
        assertEquals("types", methode.getMethod().getName());
        assertEquals(PieceCatalogController.class, methode.getBeanType());
    }

    /**
     * Une famille n'est pas un montant : elle se montre à un compte qui n'a pas droit
     * aux chiffres commerciaux. Si elle était masquée avec les prix, la liste du
     * guichet n'aurait plus de colonne de range.
     */
    @Test
    void laFamilleResteVisibleSansDroitAuxChiffres() {
        String reference = ref();
        PieceResponse visible = catalogue.create(
                demande(reference, "Filtre à gasoil", "MECANIQUE", "86.500", "1.7200", COEFFICIENT_VENTE),
                jeton(), true);

        PieceResponse cache = catalogue.get(visible.id(), false);
        assertEquals("MECANIQUE", cache.famille());
        assertNull(cache.prixVente());
    }

    @Test
    void deuxLignesPourLaMemeReferenceSontRefuseesEtLaBaseEnGardeUneSeule() {
        String reference = ref();
        ajoute(reference, "Filtre à gasoil", "86.500", "1.7200");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> ajoute(reference.toUpperCase(), "Doublon", "90.000", "1.7200"));
        assertTrue(refus.getReason().contains("déjà au catalogue"), refus.getReason());
        assertTrue(refus.getReason().toLowerCase().contains(reference.toLowerCase()),
                "Le refus doit nommer la référence en cause : « " + refus.getReason() + " ».");

        assertEquals(1, nbLignes("select count(*) from piece_rechanges where lower(reference) = ?",
                reference.toLowerCase()));
    }

    @Test
    void modifierRecalculeLeRevientEtLimportNeDoubleJamaisUneLigne() {
        String reference = ref();
        PieceResponse piece = ajoute(reference, "Filtre à gasoil", "86.500", "1.7200");

        catalogue.update(piece.id(), demande(reference, "Filtre à gasoil long", "100.000", "1.7200"),
                jeton(), true);
        assertEquals(new BigDecimal("172.000"), (BigDecimal) ligne(reference).get("prix_revient"));
        assertEquals(new BigDecimal("258.000"), (BigDecimal) ligne(reference).get("prix_vente"),
                "172,000 × 1,5000 = 258,000 : la vente suit la réévaluation du revient.");

        // Le même classeur représenté deux mois plus tard : la ligne est mise à jour,
        // elle n'est pas doublée. C'est la règle qui tient le catalogue propre.
        PieceImportSummary resume = importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, demande(reference, "Filtre à gasoil importé", "110.000", "1.7200"),
                        true))), jeton());
        assertEquals(0, resume.ajoutees());
        assertEquals(1, resume.misesAJour());
        assertEquals(1, nbLignes("select count(*) from piece_rechanges where reference = ?", reference));
        assertEquals("Filtre à gasoil importé", ligne(reference).get("designation_fr"));
        assertEquals(new BigDecimal("189.200"), (BigDecimal) ligne(reference).get("prix_revient"));
        assertEquals(new BigDecimal("283.800"), (BigDecimal) ligne(reference).get("prix_vente"));
    }

    @Test
    void unePieceRetireeResteRelisiblePourLesPapiersDavant() {
        String reference = ref();
        PieceResponse piece = ajoute(reference, "Plaquette de frein AV", "124.380", "1.6800");

        catalogue.deactivate(piece.id(), jeton());
        assertEquals(Boolean.FALSE, ligne(reference).get("active"));
        assertEquals(reference, catalogue.get(piece.id(), true).reference(), "Elle se relit toujours.");

        // Deux clics de retrait n'écrivent pas deux lignes au journal.
        int avant = nbLignes("select count(*) from audit_logs where entity_type = 'PIECE_RECHANGE' "
                + "and entity_id = ? and action = 'DEACTIVATE'", piece.id());
        catalogue.deactivate(piece.id(), jeton());
        assertEquals(avant, nbLignes("select count(*) from audit_logs where entity_type = 'PIECE_RECHANGE' "
                + "and entity_id = ? and action = 'DEACTIVATE'", piece.id()));

        // Et une import qui la redemande la remet en service : le fichier prouve
        // qu'elle se procure toujours.
        importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, demande(reference, "Plaquette de frein AV", "124.380", "1.6800"),
                        false))), jeton());
        assertEquals(Boolean.TRUE, ligne(reference).get("active"));
    }

    @Test
    void laRechercheTrouveParReferenceOuParDesignationSansRegarderLaCasse() {
        String reference = ref();
        // Une désignation qui n'appartient qu'à l'essai : le catalogue réel de
        // l'atelier a déjà ses « Filtre à gasoil », et un test qui compte les lignes
        // trouvées compterait celles du guichet.
        String designation = "Filtre à gasoil d'essai " + reference;
        ajoute(reference, designation, "86.500", "1.7200");

        // Une recherche en vrac, telle qu'on la tape au téléphone depuis l'atelier.
        assertEquals(1, catalogue.search(designation.toUpperCase(), null, page(), true).getTotalElements(),
                "La désignation se trouve sans se soucier de la casse tapée.");
        assertEquals(1, catalogue.search(reference.toLowerCase(), null, page(), true).getTotalElements());
    }

    @Test
    void unPourCentTapeParLatelierNEstPasUnJoker() {
        String reference = ref();
        ajoute(reference, "Filtre à gasoil", "86.500", "1.7200");

        // « % » tapé dans le champ de recherche est un caractère de référence : s'il
        // restait un joker, l'atelier verrait tout le catalogue défiler.
        assertEquals(0, catalogue.search("%", null, page(), true).getTotalElements());
    }

    @Test
    void unCompteSansDroitAuxChiffresLitLaPieceSansAucunMontant() {
        String reference = ref();
        PieceResponse visible = ajoute(reference, "Filtre à gasoil", "86.500", "1.7200", COEFFICIENT_VENTE);

        PieceResponse cache = catalogue.get(visible.id(), false);
        assertEquals(reference, cache.reference());
        assertEquals("Filtre à gasoil", cache.designationFr());
        // Absents du JSON, et non pas grisés à l'écran : la requête se lit dans
        // l'onglet réseau. Le coefficient et le prix de vente sont des chiffres comme
        // les autres.
        assertNull(cache.prixExWorks());
        assertNull(cache.coefficientRevient());
        assertNull(cache.coefficientVente());
        assertNull(cache.prixRevient());
        assertNull(cache.prixVente());
    }

    /**
     * Le prix de vente est un résultat, pas une saisie : le serveur le refait à chaque
     * écriture. 86,500 × 1,7200 donne 148,780 de revient, puis 148,780 × 1,5000 donne
     * 223,170 de vente. Si l'un des deux ne suivait pas sa case, l'écran montrerait un
     * chiffre que la base ne porte pas.
     */
    @Test
    void lePrixDeVenteSeCalculeSurLeRevientEtSuitSesDeuxCases() {
        String reference = ref();
        PieceResponse piece = ajoute(reference, "Filtre à gasoil", "86.500", "1.7200");

        Map<String, Object> rangee = ligne(reference);
        assertEquals(new BigDecimal("148.780"), (BigDecimal) rangee.get("prix_revient"));
        assertEquals(new BigDecimal("223.170"), (BigDecimal) rangee.get("prix_vente"));
        assertEquals(new BigDecimal("223.170"), piece.prixVente(), "L'écran relit ce que le serveur a calculé.");

        // Le coefficient de vente change : le prix de vente suit, l'Ex Works ne bouge pas.
        PieceResponse marginee = catalogue.update(piece.id(), demande(reference, "Filtre à gasoil",
                "MECANIQUE", "86.500", "1.7200", "1.7000"), jeton(), true);
        assertEquals(new BigDecimal("252.926"), marginee.prixVente(), "148,780 × 1,7000 = 252,926.");

        // Le prix Ex Works change : les deux prix suivent, dans l'ordre du calcul.
        PieceResponse rencherie = catalogue.update(piece.id(), demande(reference, "Filtre à gasoil",
                "MECANIQUE", "100.000", "1.7200", "1.7000"), jeton(), true);
        assertEquals(new BigDecimal("172.000"), rencherie.prixRevient());
        assertEquals(new BigDecimal("292.400"), rencherie.prixVente());
    }

    @Test
    void lePrixDeVenteGardeLeMillimeEtLeMemeArrondiQueLeRevient() {
        // 148,780 × 1,0005 = 148,85439 : arrondi au millime, 148,854 — jamais au
        // centime, sinon la ligne du devis s'écarterait du papier Higer.
        String reference = ref();
        PieceResponse piece = ajoute(reference, "Filtre à gasoil", "86.500", "1.7200", "1.0005");

        assertEquals(new BigDecimal("148.854"), (BigDecimal) ligne(reference).get("prix_vente"));
        assertEquals(new BigDecimal("148.854"), piece.prixVente());
    }

    /**
     * Les pièces entrées avant V47 n'ont pas de coefficient de vente : leur prix de
     * vente reste celui que V43 avait laissé taper, et il se lit tel quel. C'est au
     * premier enregistrement de la pièce que le calcul prend la main — c'est la
     * décision de l'atelier : on ne réécrit pas cent prix d'un coup, on les reprend
     * un par un.
     */
    @Test
    void unPrixDeVenteAnterieurTientJusquAuProchainEnregistrement() {
        String reference = ref();
        base.update("insert into piece_rechanges (id, reference, designation_fr, famille, devise, "
                        + "prix_ex_works, coefficient_revient, prix_revient, prix_vente, active, "
                        + "created_at, updated_at) values (?, ?, ?, 'MECANIQUE', 'EUR', 86.500, 1.7200, "
                        + "148.780, 249.999, true, now(), now())",
                UUID.randomUUID().toString(), reference, "Filtre à gasoil");

        PieceResponse avant = catalogue.search(reference, null, page(), true).getContent().get(0);
        assertNull(avant.coefficientVente(), "La pièce d'avant V47 ne s'invente pas un coefficient.");
        assertEquals(new BigDecimal("249.999"), avant.prixVente(), "Son ancien prix reste lisible tel quel.");

        // Remaniée à l'écran, elle reçoit son coefficient et son prix est recalculé.
        catalogue.update(avant.id(), demande(reference, "Filtre à gasoil", "86.500", "1.7200"), jeton(), true);
        Map<String, Object> rangee = ligne(reference);
        assertEquals(new BigDecimal("1.5000"), (BigDecimal) rangee.get("coefficient_vente"));
        assertEquals(new BigDecimal("223.170"), (BigDecimal) rangee.get("prix_vente"));
    }

    /**
     * Le classeur apporte un coefficient, jamais un prix : c'est le serveur qui monte
     * les deux prix, et la colonne de base porte le résultat. Un autre bureau, un autre
     * coefficient, un autre prix — sans qu'on retouche la ligne à la main.
     */
    @Test
    void limportCalculeLePrixDeVenteDepuisLeCoefficientDuClasseur() {
        String reference = ref();
        importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, demande(reference, "Filtre à gasoil", "MECANIQUE", "86.500", "1.7200",
                        "1.4000"), false))), jeton());

        assertEquals(new BigDecimal("148.780"), (BigDecimal) ligne(reference).get("prix_revient"));
        assertEquals(new BigDecimal("208.292"), (BigDecimal) ligne(reference).get("prix_vente"),
                "148,780 × 1,4000 = 208,292.");

        // Le classeur du mois d'après arrive avec un autre coefficient : le prix est
        // refait, il ne garde pas le taux de la première écriture.
        importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(3, demande(reference, "Filtre à huile", "MECANIQUE", "60.000", "1.6800",
                        "1.6000"), true))), jeton());
        assertEquals(new BigDecimal("161.280"), (BigDecimal) ligne(reference).get("prix_vente"));
    }

    /**
     * La famille du classeur traverse l'import jusqu'à la colonne, et c'est le nom
     * canonique qui s'y range : un fichier qui écrit « electricite » sans accents et
     * un autre qui écrit « ÉLECTRICITÉ » doivent laisser UNE seule famille en base,
     * sinon le filtre par famille rendrait la moitié des pièces.
     */
    @Test
    void limportRangeLaPieceDansSaFamilleEtRemplaceLAncienne() {
        String reference = ref();
        importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, demande(reference, "Capteur de pression", "electricite",
                        "36.200", "1.7200", null), false))), jeton());
        assertEquals("ELECTRICITE", ligne(reference).get("famille"));

        // Le classeur du mois d'après corrige le range : la colonne suit, elle ne
        // garde pas la famille d'avant sous une ligne remaniée.
        importeur.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, demande(reference, "Capteur de pression", "DIVERS",
                        "36.200", "1.7200", null), false))), jeton());
        assertEquals("DIVERS", ligne(reference).get("famille"));
    }

    private static Pageable page() {
        return PageRequest.of(0, 25);
    }

    /**
     * Compte des lignes relues en SQL. Le {@code em.flush()} n'est pas un détail :
     * une ligne d'audit part en base à la fin de la transaction, pas au moment du
     * {@code save()} — sans lui, le compteur dirait 0 et le test prendrait une
     * écriture manquée pour une écriture réussie.
     */
    private int nbLignes(String requete, Object... parametres) {
        em.flush();
        Integer n = base.queryForObject(requete, Integer.class, parametres);
        return n == null ? 0 : n;
    }
}
