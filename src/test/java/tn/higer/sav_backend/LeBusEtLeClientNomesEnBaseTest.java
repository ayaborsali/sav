package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tn.higer.sav_backend.controller.SavCaseController;
import tn.higer.sav_backend.dto.SavCaseDtos.SavCaseResponse;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClientType;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Le bus et le client nommés par le serveur sur la fiche du dossier.
 *
 * Depuis la correction (b) — la lecture de {@code /api/vehicles} et {@code /api/clients}
 * réservée aux comptes qui écrivent — l'écran des Dossiers, ouvert à un compte de
 * consultation, ne recevait plus que des identifiants. Il affichait « N/A » et
 * « Client » à la place du bus et du client, alors que la ligne a le droit de les nommer :
 * un guichet qui ne dit pas de quel bus il s'agit ne sert à rien.
 *
 * La réponse porte donc les mots du bus et du client, fabriqués sur le serveur. Ce
 * fichier écrit et relit dans la vraie base, parce que les tests unitaires du
 * contrôleur, eux, ont des dépôts mockés : ils ne prouveront jamais qu'une fiche
 * existante arrive jusqu'à l'écran, ni qu'un dossier sans bus ne fait pas tomber la page.
 *
 * Les lignes d'essai sont posées dans la transaction, annulée par Spring à la fin.
 */
@SpringBootTest
@Transactional
class LeBusEtLeClientNomesEnBaseTest {

    @Autowired private SavCaseController controleur;
    @Autowired private SavCaseRepository savCases;
    @Autowired private VehicleRepository vehicles;
    @Autowired private ClientRepository clients;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    /** Un client d'essai, avec les trois cases obligatoires de la fiche. */
    private Client unClient(String nom) {
        String sel = UUID.randomUUID().toString().substring(0, 8);
        Client client = new Client();
        client.setId("cli-essai-noms-" + sel);
        client.setCode("CLI-" + sel);
        client.setName(nom);
        client.setType(ClientType.PRIVATE_OPERATOR);
        client.setCreatedAt(LocalDate.now());
        return clients.saveAndFlush(client);
    }

    /**
     * Un bus d'essai. L'écriture passe par la fiche elle-même, jamais par un UPDATE SQL :
     * Hibernate garderait dans son carnet une copie divergente de la base.
     */
    private Vehicle unBus(Client client, String vin, String plaque, String modele) {
        Vehicle bus = new Vehicle();
        bus.setId("veh-essai-noms-" + UUID.randomUUID().toString().substring(0, 8));
        bus.setVin(vin);
        bus.setRegistration(plaque);
        bus.setModel(modele);
        bus.setClientId(client.getId());
        return vehicles.saveAndFlush(bus);
    }

    private SavCase unDossier(Vehicle bus, Client client, String numero) {
        SavCase dossier = new SavCase();
        dossier.setId("sav-essai-noms-" + UUID.randomUUID().toString().substring(0, 8));
        dossier.setCaseNumber(numero);
        dossier.setType(SavCaseType.RECLAMATIONS);
        dossier.setTitle("Dossier posé pour l'essai des noms");
        dossier.setVehicleId(bus == null ? null : bus.getId());
        dossier.setClientId(client == null ? null : client.getId());
        dossier.setStatus(SavCaseStatus.OPEN);
        dossier.setCreatedAt(LocalDate.now());
        return savCases.saveAndFlush(dossier);
    }

    /**
     * Le cas du guichet : le dossier d'une panne, et la ligne qui doit dire le bus et
     * le client. Le VIN porte le nom — c'est lui qui est écrit sur le bus, la plaque
     * change avec la carte grise.
     */
    @Test
    void la_lecture_d_un_dossier_nomme_le_bus_et_le_client() {
        Client client = unClient("SNTRI Essai Noms");
        Vehicle bus = unBus(client, "VINESSAI0000001", "1234-567-26", "Higer 8,6 m Confort");
        SavCase dossier = unDossier(bus, client, "ESSAI-NOMS-" + UUID.randomUUID().toString().substring(0, 8));

        SavCaseResponse vue = controleur.get(dossier.getId());

        assertEquals(bus.getId(), vue.vehicleId(), "la vue porte toujours l'identifiant, pour les écritures");
        assertEquals("VINESSAI0000001", vue.vehicleLabel(), "le bus se nomme par son VIN");
        assertEquals("VINESSAI0000001", vue.vehicleVin());
        assertEquals("1234-567-26", vue.vehicleRegistration());
        assertEquals("Higer 8,6 m Confort", vue.vehicleModel());
        assertEquals(client.getId(), vue.clientId());
        assertEquals("SNTRI Essai Noms", vue.clientName());
        // La case n'a pas été remplie par l'écran : la base ne porte aucun nom de bus.
        assertEquals("VINESSAI0000001",
                base.queryForObject("select vin from vehicles where id = ?", String.class, bus.getId()));
    }

    /**
     * La fiche d'un bus d'occasion arrive parfois sans VIN, avec seule la plaque. Le
     * bus n'est pas pour autant « N/A » : il se nomme par ce que l'atelier lit dessus.
     * Une seule formule, dans {@code FicheLabel}, et c'est elle que les trois écrans
     * (Dossiers, Dépannage, Missions) emploient — le même bus porte le même mot.
     */
    @Test
    void un_bus_sans_vin_se_nomme_par_sa_plaque() {
        Client client = unClient("Transport Essai Plaque");
        Vehicle bus = unBus(client, "   ", "9999-111-26", null);
        SavCase dossier = unDossier(bus, client, "ESSAI-NOMS-" + UUID.randomUUID().toString().substring(0, 8));

        SavCaseResponse vue = controleur.get(dossier.getId());

        assertEquals("9999-111-26", vue.vehicleLabel(), "pas de VIN : c'est la plaque qui nomme le bus");
        assertNull(vue.vehicleVin(), "une case vide ne s'invente pas : le VIN reste absent");
        assertEquals("9999-111-26", vue.vehicleRegistration());
        assertNull(vue.vehicleModel(), "le modèle non plus ne s'invente pas");
    }

    /**
     * La liste du guichet, celle qui s'ouvre pendant que l'atelier travaille : chaque
     * ligne nomme son bus et son client, lus en une seule fois pour toute la page —
     * pas une lecture de fiche par dossier.
     */
    @Test
    void la_liste_des_dossiers_nomme_les_bus_de_la_page_en_une_seule_lecture_de_fiches() {
        Client client = unClient("Flotte Essai Page");
        Vehicle premier = unBus(client, "VINPAGE000000A", "1111-222-26", "Higer 12 m Standard");
        Vehicle second = unBus(client, "VINPAGE000000B", "3333-444-26", "Higer 8,6 m Confort");
        SavCase dossier1 = unDossier(premier, client, "ESSAI-PAGE-A");
        SavCase dossier2 = unDossier(second, client, "ESSAI-PAGE-B");

        Page<SavCaseResponse> page = controleur.list(PageRequest.of(0, 500));
        SavCaseResponse lu1 = surLaPage(page, dossier1.getId());
        SavCaseResponse lu2 = surLaPage(page, dossier2.getId());

        assertEquals("VINPAGE000000A", lu1.vehicleLabel());
        assertEquals("Flotte Essai Page", lu1.clientName());
        assertEquals("VINPAGE000000B", lu2.vehicleLabel(), "deux dossiers, deux bus : rien n'est mélangé");
        assertEquals("Higer 12 m Standard", lu1.vehicleModel());
        assertEquals("Higer 8,6 m Confort", lu2.vehicleModel());
    }

    /**
     * La borne qui avait tué l'écran le 26/10 : une case vide dans la ligne lue. Le bus
     * est obligatoire sur un dossier, le client ne l'est pas — le parent non plus.
     * Demander la clé d'un dossier sans client à la carte des clients levait un NPE et
     * vidait toute la page (le 500 du GET /api/sav-cases). Aujourd'hui la ligne passe,
     * avec la case du client vide — un vide assumé, pas un « Client » inventé.
     */
    @Test
    void un_dossier_sans_client_ni_parent_laisse_la_case_vide_et_ne_tue_pas_la_page() {
        Client client = unClient("Client Sans Dossier Client");
        Vehicle bus = unBus(client, "VINSANSCLE00001", "7777-888-26", null);
        SavCase sansClient = unDossier(bus, null, "ESSAI-NOMS-SANS-CLIENT");

        SavCaseResponse vue = controleur.get(sansClient.getId());

        assertNull(vue.clientId(), "la base autorise un dossier sans client");
        assertNull(vue.clientName(), "aucun nom n'est inventé à la place");
        assertNull(vue.parentCaseNumber(), "pas de parent non plus");
        assertEquals("VINSANSCLE00001", vue.vehicleLabel(), "le bus, lui, se nomme toujours");

        assertNotNull(surLaPage(controleur.list(PageRequest.of(0, 500)), sansClient.getId()),
                "la page se rend avec une ligne dont la clé client est vide");
    }

    /**
     * Un dossier dont le bus a été effacé de la base sans que la case du dossier soit
     * nettoyée — ce que V33 corrige pour l'avenir, mais que la lecture doit encaisser.
     * Le pointeur ne mène à rien : case vide, et surtout pas la ligne qui disparaît.
     */
    @Test
    void un_bus_efface_laisse_la_case_vide_sans_emporter_le_dossier() {
        Client client = unClient("Client Bus Efface");
        Vehicle bus = unBus(client, "VINEFFACE000001", "5555-666-26", null);
        SavCase dossier = unDossier(bus, client, "ESSAI-NOMS-BUS-EFFACE");

        // L'effacement passe par JPA : la ligne de bus quitte vraiment la base.
        vehicles.delete(bus);
        vehicles.flush();
        em.clear();

        SavCaseResponse vue = controleur.get(dossier.getId());

        assertEquals("ESSAI-NOMS-BUS-EFFACE", vue.caseNumber(), "le dossier se lit toujours");
        assertEquals(bus.getId(), vue.vehicleId(), "la case de rattachement reste écrite sur le dossier");
        assertNull(vue.vehicleLabel(), "le bus n'existe plus : la case est vide, personne ne l'invente");
    }

    private SavCaseResponse surLaPage(Page<SavCaseResponse> page, String id) {
        return page.getContent().stream().filter(v -> id.equals(v.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Le dossier d'essai n'est pas sur la page lue."));
    }
}
