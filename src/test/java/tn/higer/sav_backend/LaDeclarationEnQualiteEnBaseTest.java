package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tn.higer.sav_backend.dto.InterventionDtos.DeclarationQualiteRequest;
import tn.higer.sav_backend.dto.InterventionDtos.DeclarationQualiteResponse;
import tn.higer.sav_backend.dto.InterventionDtos.ProgressRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPurpose;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.InterventionService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * « Déclarer en qualité », écrit dans la vraie base.
 *
 * La feuille de l'atelier (28/10) promettait « un problème identifié ouvre un dossier
 * qualité ». La décision du 29/10 est qu'<b>un clic d'humain ouvre le dossier, et rien
 * d'autre</b>. Ce fichier prouve les deux moitiés de la phrase :
 *
 *  - le clic écrit bien une ligne dans `sav_cases` — famille QUALITE, numéro « QAL »,
 *    et surtout {@code declared_from_intervention_id}, la colonne de V34 que personne
 *    d'autre ne remplit. Ces colonnes ne se lisent que dans PostgreSQL : un test à
 *    bouchons laisserait passer un service qui ne sauvegarde rien ;
 *  - une sortie vécue jusqu'au diagnostic, elle, ne fait naître aucun dossier.
 *
 * Le préremplissage se vérifie pareil en base : le code défaut relevé devant le bus
 * entre dans le titre du dossier sans que personne le retape, et les quatre cases de
 * l'atelier (classification, criticité, actions) restent NULL si personne n'a écrit —
 * le portail ne choisit pas une gravité à la place d'un homme.
 *
 * Tout tient dans une transaction que Spring annule : la ligne d'essai ne reste pas
 * dans le registre de l'atelier.
 */
@SpringBootTest
@Transactional
class LaDeclarationEnQualiteEnBaseTest {

    private static final String SORTIE = "itv-essai-declaration";

    @Autowired private InterventionService sorties;
    @Autowired private InterventionRepository interventions;
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

    private static Authentication guichet() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_GESTIONNAIRE_SAV")));
    }

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "sav@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /**
     * Une sortie posée pour l'essai, telle que l'atelier la trouve : le bus d'un vrai
     * client, la panne signalée, le code défaut relevé devant le bus. Le bus se prend
     * dans la base parce que le dossier en hérite — un bus inventé rendrait un dossier
     * dont personne ne retrouverait la fiche.
     */
    private Intervention uneSortie() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à déclarer.");
        Vehicle choisi = bus.get(0);

        Intervention s = new Intervention();
        s.setId(SORTIE);
        s.setNumber("ESSAI-QAL-" + UUID.randomUUID().toString().substring(0, 8));
        s.setStatus(InterventionStatus.ON_SITE);
        s.setPurpose(InterventionPurpose.PANNE);
        s.setVehicleId(choisi.getId());
        s.setClientId(choisi.getClientId());
        s.setLocationCity("Ben Arous");
        s.setDescription("Le client signale un bruit au freinage");
        s.setDefectCode("2115U");
        s.setReportedAt(LocalDateTime.now().minusHours(2));
        s.setCreatedAt(LocalDateTime.now().minusHours(2));
        return interventions.saveAndFlush(s);
    }

    /**
     * Le dossier tel que la table le porte. Le flush n'est pas une formalité : sans lui,
     * Hibernate garderait l'écriture dans son carnet et SQL lirait une ligne qui n'est
     * pas encore partie — un faux « ça n'a pas été écrit ».
     */
    private Map<String, Object> dossierLuEnBase(String dossierId) {
        em.flush();
        return base.queryForMap("select case_number, type, status, title, description, vehicle_id, client_id,"
                + " declaration_date::text as declaration_date, classification, severity,"
                + " corrective_actions, preventive_actions, declared_from_intervention_id"
                + " from sav_cases where id = ?", dossierId);
    }

    /** Combien de dossiers cette sortie a déjà déclarés — comptés dans la table. */
    private int declarationsEnBase() {
        em.flush();
        Integer n = base.queryForObject(
                "select count(*) from sav_cases where declared_from_intervention_id = ?",
                Integer.class, SORTIE);
        assertNotNull(n);
        return n;
    }

    /** La sortie relue dans sa table : ni son statut ni son dossier ne doivent bouger. */
    private Map<String, Object> sortieLueEnBase() {
        em.flush();
        return base.queryForMap("select status, sav_case_id, defect_code from interventions where id = ?", SORTIE);
    }

    /**
     * Le geste attendu : un clic du chef sur la ligne, et une ligne dans `sav_cases`
     * portant le lien vers la sortie. Ce que la sortie sait déjà est repris par le
     * serveur — code défaut dans le titre, bus et client sur la fiche. Ce qu'elle ne
     * sait pas reste vide.
     */
    @Test
    void un_clic_ecrit_le_dossier_qualite_jusque_dans_la_base() {
        Intervention sortie = uneSortie();
        assertEquals(0, declarationsEnBase(), "avant le clic, rien n'est déclaré");

        DeclarationQualiteResponse vue = sorties.declarerEnQualite(SORTIE,
                new DeclarationQualiteRequest(null, null, null, null), jeton(), chef());

        assertTrue(vue.dossierNumber().startsWith("SAV-QAL-"), vue.dossierNumber());
        assertEquals(sortie.getNumber(), vue.sortieNumber());
        assertEquals(LocalDate.now().toString(), vue.declaredAt());

        Map<String, Object> dossier = dossierLuEnBase(vue.dossierId());
        assertEquals("QUALITE", dossier.get("type"), "la famille se lit dans la table");
        assertEquals("OPEN", dossier.get("status"), "déclarer ouvre un dossier, cela ne le clôt pas");
        assertEquals(SORTIE, dossier.get("declared_from_intervention_id"),
                "la colonne de V34 porte le lien, sinon rien ne prouve qu'un second clic serait refusé");
        assertEquals(LocalDate.now().toString(), dossier.get("declaration_date"));
        assertTrue(String.valueOf(dossier.get("title")).contains("2115U"), String.valueOf(dossier.get("title")));
        assertTrue(String.valueOf(dossier.get("title")).contains(sortie.getNumber()),
                String.valueOf(dossier.get("title")));
        assertEquals(sortie.getVehicleId(), dossier.get("vehicle_id"), "le bus n'est pas à retaper");
        assertEquals(sortie.getClientId(), dossier.get("client_id"));
        assertNull(dossier.get("classification"), "le portail n'invente pas une classification");
        assertNull(dossier.get("severity"), "le portail n'invente pas une criticité");
        assertNull(dossier.get("corrective_actions"));
        assertNull(dossier.get("preventive_actions"));

        // La sortie ne change pas de vie pour autant : déclarer n'est pas clôturer, et
        // le dossier qualité n'est pas le dossier du dépannage.
        Map<String, Object> apres = sortieLueEnBase();
        assertEquals("ON_SITE", apres.get("status"));
        assertNull(apres.get("sav_case_id"), "le dossier qualité ne remplace pas le dossier de la sortie");

        List<String> notes = base.queryForList(
                "select note from intervention_events where intervention_id = ? and event_type = 'CASE'"
                        + " order by recorded_at", String.class, SORTIE);
        assertTrue(notes.stream().anyMatch(n -> n.contains("Déclarée en qualité")
                && n.contains(vue.dossierNumber())), notes.toString());
        assertEquals(1, declarationsEnBase());
    }

    /**
     * Le préremplissage, prouvé là où il se joue : le code défaut est écrit sur la
     * sortie par le geste du technicien, et le clic du chef le relit — il n'y a pas
     * deux saisies du même code.
     */
    @Test
    void le_code_defaut_releve_devant_le_bus_entre_dans_le_titre_sans_retape() {
        Intervention sortie = uneSortie();
        sorties.progress(SORTIE, new ProgressRequest("2165J", "Disque rayé, garniture hors cote",
                "Disque de frein AV gauche"), jeton(), chef());
        assertEquals("2165J", sortieLueEnBase().get("defect_code"), "le code est bien dans la table");
        assertEquals(0, declarationsEnBase(), "un problème identifié n'ouvre rien tout seul");

        DeclarationQualiteResponse vue = sorties.declarerEnQualite(SORTIE, null, jeton(), chef());

        String titre = String.valueOf(dossierLuEnBase(vue.dossierId()).get("title"));
        assertTrue(titre.contains("2165J"), titre);
        assertTrue(titre.contains(sortie.getNumber()), titre);
        assertTrue(!titre.contains("2115U"), "c'est le code relevé sur place qui compte : " + titre);
        String description = String.valueOf(dossierLuEnBase(vue.dossierId()).get("description"));
        assertTrue(description.contains("Disque rayé"), description);
        assertTrue(description.contains("Disque de frein AV gauche"), description);
        assertEquals(1, declarationsEnBase());
    }

    /** Une sortie sans code défaut se déclare quand même : la case reste vide, le titre le dit. */
    @Test
    void une_sortie_sans_code_defaut_se_declare_sans_en_inventer_un() {
        Intervention sortie = uneSortie();
        sortie.setDefectCode(null);
        interventions.saveAndFlush(sortie);

        DeclarationQualiteResponse vue = sorties.declarerEnQualite(SORTIE, null, jeton(), chef());

        String titre = String.valueOf(dossierLuEnBase(vue.dossierId()).get("title"));
        assertTrue(titre.contains("sans code défaut"), titre);
        assertTrue(!titre.contains("null"), titre);
    }

    /**
     * Les quatre cases de l'atelier arrivent telles qu'elles sont écrites : c'est le
     * seul endroit du portail où une criticité se déclare, et elle vient d'un humain.
     */
    @Test
    void les_cases_de_latelier_arrivent_dans_la_table() {
        uneSortie();

        DeclarationQualiteResponse vue = sorties.declarerEnQualite(SORTIE,
                new DeclarationQualiteRequest("Freinage", "Majeure",
                        "Disque et garniture remplacés, essai routier fait",
                        "Contrôle du freinage ajouté au palier des 60 000 km"), jeton(), chef());

        Map<String, Object> dossier = dossierLuEnBase(vue.dossierId());
        assertEquals("Freinage", dossier.get("classification"));
        assertEquals("Majeure", dossier.get("severity"));
        assertTrue(String.valueOf(dossier.get("corrective_actions")).contains("essai routier"));
        assertTrue(String.valueOf(dossier.get("preventive_actions")).contains("60 000 km"));
    }

    /**
     * Le second clic refuse et nomme le dossier déjà ouvert. Deux lignes pour la même
     * anomalie vue devant le même bus, c'est le défaut que l'atelier reproche au
     * portail : le refus doit donc dire où chercher, pas seulement dire non.
     */
    @Test
    void un_second_clic_refuse_et_nomme_le_dossier_deja_ouvert() {
        uneSortie();
        DeclarationQualiteResponse premier = sorties.declarerEnQualite(SORTIE, null, jeton(), chef());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> sorties.declarerEnQualite(SORTIE, null, jeton(), chef()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        String raison = refus.getReason() == null ? "" : refus.getReason();
        assertTrue(raison.contains(premier.dossierNumber()), raison);
        assertTrue(raison.contains("déjà déclarée"), raison);
        assertEquals(1, declarationsEnBase(), "le refus n'a pas ajouté de jumeau");
    }

    /**
     * La sortie vécue jusqu'au diagnostic, rejouée sans clic : aucun dossier qualité ne
     * naît. C'est la moitié « pas de création automatique » de la décision du 29/10, et
     * elle ne se prouve qu'en relisant la table.
     */
    @Test
    void la_sortie_vecue_jusqua_diagnostic_nouvre_aucun_dossier_seule() {
        uneSortie();
        sorties.progress(SORTIE, new ProgressRequest("10107", "Pompe à eau qui fuit", "Pompe à eau"),
                jeton(), chef());
        em.flush();

        Integer total = base.queryForObject(
                "select count(*) from sav_cases where type = 'QUALITE' and declared_from_intervention_id = ?",
                Integer.class, SORTIE);
        assertEquals(0, total, "un diagnostic écrit un dossier qualité seulement si quelqu'un le demande");
    }

    /**
     * Déclarer est un acte de l'atelier, comme la note, la rectification et la clôture.
     * Le guichet crée des dossiers depuis son propre écran, mais pas sur une ligne de
     * sortie — la garde est la même que partout ailleurs dans le module.
     */
    @Test
    void le_guichet_ne_declare_pas_sur_une_sortie() {
        uneSortie();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> sorties.declarerEnQualite(SORTIE, null, jeton(), guichet()));

        assertEquals(HttpStatus.FORBIDDEN, refus.getStatusCode());
        assertEquals(0, declarationsEnBase(), "le refus n'a rien écrit");
    }

    /**
     * Le ménage d'après V33, côté sortie : effacer une sortie avant tout horodatage
     * laisse le dossier qualité debout — le problème vu devant le bus reste déclaré —
     * et ne détache que le lien. Sans lui, la fiche du dossier pointerait une sortie qui
     * n'existe plus.
     */
    @Test
    void la_sortie_effacee_laisse_le_dossier_vivant_et_detache() {
        uneSortie();
        DeclarationQualiteResponse vue = sorties.declarerEnQualite(SORTIE, null, jeton(), chef());

        sorties.delete(SORTIE, jeton(), chef());

        Map<String, Object> dossier = dossierLuEnBase(vue.dossierId());
        assertEquals("QUALITE", dossier.get("type"), "le dossier survit à la sortie");
        assertNull(dossier.get("declared_from_intervention_id"), "seul le lien s'éteint");
        Integer sortiesRestantes = base.queryForObject("select count(*) from interventions where id = ?",
                Integer.class, SORTIE);
        assertEquals(0, sortiesRestantes);
    }
}
