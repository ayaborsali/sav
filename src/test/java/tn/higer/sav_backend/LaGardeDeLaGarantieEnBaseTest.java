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

import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.MissionService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5, écrit dans la vraie base : un devis ne se dresse pas sur un bus garanti.
 *
 * La garde lit deux choses qu'un test à bouchons ne touche jamais — la colonne
 * {@code vehicles.warranty_end_date} et la colonne {@code sav_cases.claim_state}.
 * Les deux sont relues en SQL : si la date n'était pas dans la table, le service
 * laisserait passer le devis et l'essai passerait quand même, pour la mauvaise
 * raison. Ce n'est pas une preuve.
 *
 * Chaque essai pose sa date de garantie sur un bus qui existe, par la fiche
 * elle-même — jamais par un UPDATE SQL, qui laisserait dans le carnet d'Hibernate le
 * bus avec sa date d'avant et ferait lire à la garde une valeur que la base ne porte
 * plus. Tout tient dans une transaction que Spring annule : le bus ressort avec sa
 * garantie, aucune ligne d'essai ne reste sur le travail de personne.
 */
@SpringBootTest
@Transactional
class LaGardeDeLaGarantieEnBaseTest {

    @Autowired private MissionService demandes;
    @Autowired private SavCaseRepository savCases;
    @Autowired private MaintenanceRepository documents;
    @Autowired private VehicleRepository vehicles;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "sav@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /**
     * Un bus dont la garantie est réglée à la date de l'essai, et le dossier de panne
     * qui en découle. Le dossier est écrit par le dépôt, pas à la main en SQL : la
     * demande qui le suit doit lire le même objet que celui que le guichet verrait.
     */
    private SavCase unDossierDePanne(LocalDate finDeGarantie) {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à mettre en garantie.");
        Vehicle choisi = bus.get(0);
        choisi.setWarrantyEndDate(finDeGarantie);
        vehicles.saveAndFlush(choisi);

        SavCase neuf = new SavCase();
        neuf.setId("sav-essai-garantie");
        neuf.setCaseNumber("ESSAI-GAR-" + UUID.randomUUID().toString().substring(0, 8));
        neuf.setType(SavCaseType.RECLAMATIONS);
        neuf.setTitle("Panne sur un bus dont la garantie se discute");
        neuf.setVehicleId(choisi.getId());
        neuf.setClientId(choisi.getClientId());
        neuf.setStatus(SavCaseStatus.OPEN);
        neuf.setCreatedAt(LocalDate.now());
        return savCases.saveAndFlush(neuf);
    }

    private MissionResponse uneDemande(SavCase dossier) {
        return demandes.creer(new MissionRequest(dossier.getId(), null,
                "Le client appelle : le bus ne démarre plus"), jeton(), chef());
    }

    /** Le même dossier, mais dont la panne est datée d'un appel du client. */
    private SavCase unDossierDePanne(LocalDate finDeGarantie, LocalDate jourDeLAppel) {
        SavCase dossier = unDossierDePanne(finDeGarantie);
        dossier.setCalledAt(jourDeLAppel.atTime(8, 15));
        return savCases.saveAndFlush(dossier);
    }

    /** La date de l'appel relue dans la table : c'est elle que la garde doit voir. */
    private String appelLuDansLaBase(String savCaseId) {
        return base.queryForObject("select called_at::date::text from sav_cases where id = ?",
                String.class, savCaseId);
    }

    /** La date relue dans la table, pas dans l'objet : c'est elle que la garde doit voir. */
    private String dateLueDansLaBase(String vehicleId) {
        return base.queryForObject("select warranty_end_date::text from vehicles where id = ?",
                String.class, vehicleId);
    }

    /** Le nombre de papiers de l'atelier en base : un refus n'en ajoute pas. */
    private int papiers() {
        em.flush();
        Integer total = base.queryForObject("select count(*) from maintenance_items", Integer.class);
        assertNotNull(total);
        return total;
    }

    /**
     * Le coeur de P5 : la garantie court, le devis se refuse, et le refus dit où
     * aller. Le numéro du dossier et le nom du bus se lisent dans le message — une
     * règle qu'on ne peut pas montrer du doigt ne s'applique pas au guichet.
     */
    @Test
    void un_bus_garanti_ne_se_chiffre_pas_chez_le_client() {
        SavCase dossier = unDossierDePanne(LocalDate.now().plusMonths(8));
        MissionResponse demande = uneDemande(dossier);
        int avant = papiers();
        assertEquals(LocalDate.now().plusMonths(8).toString(), dateLueDansLaBase(dossier.getVehicleId()),
                "la date que la garde lira est bien dans la table");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> demandes.etablirDevis(demande.id(), jeton(), chef()));

        assertTrue(refus.getReason().contains("sous garantie"), refus.getReason());
        assertTrue(refus.getReason().contains(dossier.getCaseNumber()), refus.getReason());
        assertTrue(refus.getReason().contains("appel en garantie"), refus.getReason());
        assertEquals(avant, papiers(), "le refus n'a laissé aucun papier derrière lui");
        assertNull(demandes.get(demande.id()).papierNumero(),
                "la demande attend toujours sa décision : rien n'a été engagé");
        assertEquals("A_TRAITER", base.queryForObject("select state from missions where id = ?",
                String.class, demande.id()));
    }

    /**
     * Le constructeur a répondu non : la prise en charge est fermée, la réparation
     * retombe sur le client, le devis a sa place. La porte de sortie se lit sur le
     * dossier — pas sur une case que le navigateur cocherait pour lui.
     */
    @Test
    void le_non_du_constructeur_rouvre_le_devis() {
        SavCase dossier = unDossierDePanne(LocalDate.now().plusMonths(8));
        dossier.setClaimState(ClaimState.REFUSE);
        dossier.setClaimDecision("Hors garantie constructeur : usure, pas défaut");
        savCases.saveAndFlush(dossier);
        assertEquals("REFUSE", base.queryForObject("select claim_state from sav_cases where id = ?",
                String.class, dossier.getId()), "la réponse lue par la garde est dans la table");
        MissionResponse demande = uneDemande(dossier);

        MissionResponse vue = demandes.etablirDevis(demande.id(), jeton(), chef());

        assertNotNull(vue.papierNumero());
        assertTrue(vue.papierNumero().startsWith("DV-"), vue.papierNumero());
        MaintenanceItem devis = documents.findById(vue.papierId()).orElseThrow();
        assertEquals(MaintenanceType.DEVIS, devis.getType());
    }

    /**
     * La décision du 29/10, jouée sur des dates relues dans la base : la garantie se
     * juge **au jour de la panne**, pas au jour où l'atelier clique. Le bus est sorti
     * de garantie avant-hier, la panne date de six semaines — l'appel se dépose encore
     * chez le constructeur, et un devis chiffrerait deux fois la même réparation.
     * Avant la correction, la garde comparait à « aujourd'hui » et laissait passer.
     */
    @Test
    void la_garantie_se_juge_au_jour_de_l_incident_et_pas_aujourdhui() {
        LocalDate jourDeLAppel = LocalDate.now().minusWeeks(6);
        LocalDate finDeGarantie = jourDeLAppel.plusDays(40);
        assertTrue(finDeGarantie.isBefore(LocalDate.now()),
                "l'essai ne prouve rien si la garantie court encore : " + finDeGarantie);

        SavCase dossier = unDossierDePanne(finDeGarantie, jourDeLAppel);
        assertEquals(finDeGarantie.toString(), dateLueDansLaBase(dossier.getVehicleId()));
        assertEquals(jourDeLAppel.toString(), appelLuDansLaBase(dossier.getId()),
                "la date que la garde lira est bien dans la table");

        MissionResponse demande = uneDemande(dossier);
        int avant = papiers();

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> demandes.etablirDevis(demande.id(), jeton(), chef()));

        assertTrue(refus.getReason().contains("au jour de la panne"), refus.getReason());
        assertTrue(refus.getReason().contains(jourDeLAppel.format(JOUR)), refus.getReason());
        assertTrue(refus.getReason().contains(finDeGarantie.format(JOUR)), refus.getReason());
        assertTrue(refus.getReason().contains("appel du client"),
                "le refus doit dire quelle case il a relue : " + refus.getReason());
        assertEquals(avant, papiers(), "le refus n'a laissé aucun papier derrière lui");
    }

    /** La garantie est passée : plus rien à appeler chez le constructeur, le devis se dresse. */
    @Test
    void une_garantie_passee_ne_retient_plus_personne() {
        SavCase dossier = unDossierDePanne(LocalDate.now().minusDays(3));
        MissionResponse demande = uneDemande(dossier);

        MissionResponse vue = demandes.etablirDevis(demande.id(), jeton(), chef());

        assertNotNull(vue.papierNumero());
        assertEquals(LocalDate.now().minusDays(3).toString(), dateLueDansLaBase(dossier.getVehicleId()));
    }

    /**
     * Un ordre de travail ne facture personne : il dit que l'atelier exécute, en
     * garantie comme après. La garde ne le touche pas — sinon un bus garanti ne
     * pourrait plus être réparé du tout.
     */
    @Test
    void l_atelier_reste_libre_de_reparer_un_bus_garanti() {
        SavCase dossier = unDossierDePanne(LocalDate.now().plusMonths(8));
        MissionResponse demande = uneDemande(dossier);

        MissionResponse vue = demandes.traiterAtelier(demande.id(), jeton(), chef());

        assertNotNull(vue.papierNumero());
        assertTrue(vue.papierNumero().startsWith("OR-"), vue.papierNumero());
        assertEquals(MaintenanceType.OPERATION, documents.findById(vue.papierId()).orElseThrow().getType());
    }

    /**
     * Une fiche de bus sans date de garantie : la garde n'a rien en main, elle ne
     * devine pas. Le devis passe — c'est la case que le portail laisse vide plutôt
     * que d'inventer une date pour refuser un papier.
     */
    @Test
    void une_garantie_inconnue_ne_bloque_rien() {
        SavCase dossier = unDossierDePanne(null);
        assertNull(dateLueDansLaBase(dossier.getVehicleId()));
        MissionResponse demande = uneDemande(dossier);

        assertNotNull(demandes.etablirDevis(demande.id(), jeton(), chef()).papierNumero());
    }

    /**
     * Une visite du forfait sur un bus encore couvert : le constructeur ne rembourse
     * pas une visite périodique, donc la garde n'a rien à exiger de lui. Décision du
     * 29/10 — le rejeu avait découvert qu'un devis sur une visite se refusait comme sur
     * une panne, ce qui condamnait l'atelier à ouvrir un appel en garantie inventé.
     */
    @Test
    void une_visite_de_bus_garanti_se_chiffre_sans_appel_en_garantie() {
        SavCase dossier = unDossierDePanne(LocalDate.now().plusMonths(8));
        dossier.setType(SavCaseType.VISITES);
        dossier.setTitle("Visite périodique des 60 000 km");
        savCases.saveAndFlush(dossier);
        MissionResponse demande = uneDemande(dossier);
        int avant = papiers();

        MissionResponse vue = demandes.etablirDevis(demande.id(), jeton(), chef());

        assertEquals("devis", vue.issue());
        assertTrue(vue.papierNumero().startsWith("DV-"), vue.papierNumero());
        assertEquals(MaintenanceType.DEVIS, documents.findById(vue.papierId()).orElseThrow().getType());
        assertEquals(avant + 1, papiers(), "un seul papier chiffré naît du devis");
    }
}
