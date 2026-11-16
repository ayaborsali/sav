package tn.higer.sav_backend;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ce qui a été vu sur le bus se note sur l'ordre de travail, et cela passe par la route
 * que l'écran Maintenance frappe (V38).
 *
 * {@code LeOrdreSeClotEnBaseTest} prouve la clôture en appelant le service. Ce fichier
 * prend l'autre bout de la chaîne — celui que le guichet et l'atelier touchent :
 *   — la case « Anomalies constatées » arrive du corps JSON, s'écrit dans sa colonne, et
 *     se relit en SQL : un champ reçu et jeté en silence est un papier amnésique ;
 *   — une case vidée efface réellement ce qui était écrit. Le formulaire relit le papier
 *     avant d'envoyer, donc il renvoie la case telle quelle ; « vide » doit vouloir dire
 *     « vide » et non « on garde » ;
 *   — le coche d'une opération et ses heures réelles passent par la même route : c'est
 *     la matière de la clôture, et l'écran les envoie ligne par ligne ;
 *   — un devis ou un bon de livraison qui reçoit cette case se refuse, et le refus nomme
 *     le papier. Rien n'est écrit à côté : un 400 qui laisse des miettes en base se
 *     discovere six mois plus tard chez le client.
 *
 * Chaque essai pose son propre papier sous un identifiant d'UUID dans la vraie base, et
 * la transaction annule tout : les dossiers de l'atelier retrouvent leur état d'avant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LAnomalieSeNoteSurLOrdreEnBaseTest {

    @Autowired private MockMvc mvc;
    @Autowired private MaintenanceRepository documents;
    @Autowired private VehicleRepository vehicles;
    @Autowired private JdbcTemplate base;
    @Autowired private EntityManager em;

    @BeforeAll
    static void exigerUneVraieBase() {
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test écrit et relit des lignes dans PostgreSQL.");
    }

    private static RequestPostProcessor chef() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER"))
                .jwt(c -> c.subject("usr-1").claim("email", "chef@higer.tn"));
    }

    private static String marque() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String prop(String valeur) {
        return "\"" + valeur.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Le bus de l'essai, choisi parmi ceux qui ont un client. */
    private Vehicle bus() {
        List<Vehicle> bus = vehicles.findAll().stream()
                .filter(v -> v.getClientId() != null && !v.getClientId().isBlank())
                .toList();
        Assumptions.assumeTrue(!bus.isEmpty(), "Aucun bus avec un client en base : rien à écrire.");
        return bus.get(0);
    }

    /**
     * Un papier posé en base par l'essai : ordre de travail par défaut, devis quand on
     * veut prouver que la case ne lui appartient pas. Une ligne d'opération, rien de
     * coché, aucune anomalie — l'état d'un papier qu'on vient de créer.
     */
    private MaintenanceItem papier(MaintenanceType type, MaintenanceStatus etat) {
        MaintenanceItem m = new MaintenanceItem();
        m.setId("maint-essai-anom-" + marque());
        m.setType(type);
        m.setNumber((type == MaintenanceType.DEVIS ? "DV-ESS-" : "OR-ESS-") + marque());
        m.setVehicleId(bus().getId());
        m.setClientId(bus().getClientId());
        m.setDate(LocalDate.now());
        m.setStatus(etat);
        MaintenanceDetail ligne = new MaintenanceDetail();
        ligne.setReference("MO-1");
        ligne.setLabel("Vidange et contrôle des niveaux");
        ligne.setQuantity(1.0);
        ligne.setUnitPrice(0.0);
        ligne.setQuantityText("2,0 h");
        ligne.setNature(LineNature.LABOR);
        ligne.setDone(false);
        m.setDetails(new ArrayList<>(List.of(ligne)));
        return documents.saveAndFlush(m);
    }

    /**
     * Le corps que l'écran envoie : le papier tel qu'il est relu avant la saisie, avec la
     * case demandée. {@code details} suit la même forme — une ligne cochée, ses heures.
     */
    private String corps(MaintenanceType type, String numero, MaintenanceItem existant,
                         String anomalies, boolean coche, Double heures) {
        return "{"
                + "\"type\":" + prop(type.name().toLowerCase())
                + ",\"number\":" + prop(numero)
                + ",\"vehicleId\":" + prop(existant.getVehicleId())
                + ",\"clientId\":" + prop(existant.getClientId())
                + ",\"date\":" + prop(LocalDate.now().toString())
                + ",\"status\":" + prop(existant.getStatus().name().toLowerCase())
                + ",\"description\":" + prop("Papier d'essai.")
                + (anomalies == null ? "" : ",\"anomalies\":" + prop(anomalies))
                + ",\"details\":[{\"reference\":\"MO-1\",\"label\":\"Vidange et contrôle des niveaux\","
                + "\"quantity\":1,\"unitPrice\":0,\"quantityText\":\"2,0 h\",\"nature\":\"labor\","
                + "\"done\":" + coche
                + (heures == null ? ",\"realHours\":null" : ",\"realHours\":" + heures)
                + "}]}";
    }

    /**
     * La colonne décide, pas le DTO : sans flush, Hibernate n'aurait rien envoyé. Le
     * papier se relit avec sa ligne — le coche et les heures partagent la même écriture.
     */
    private Map<String, Object> relire(String id) {
        em.flush();
        Map<String, Object> papier = new HashMap<>(
                base.queryForMap("select status, anomalies from maintenance_items where id = ?", id));
        papier.putAll(base.queryForMap("select done as done_lu, real_hours as heures_lues"
                + " from maintenance_details where maintenance_id = ? limit 1", id));
        return papier;
    }

    // ------------------------------------------------------------------------------ les essais

    /**
     * L'atelier écrit son anomalie, la route l'accepte, et la colonne la garde. C'est le
     * seul endroit où le texte se retrouve quand le bus revient au portail.
     */
    @Test
    void les_anomalies_de_l_ordre_traversent_la_route_et_s_ecrivent_en_base() throws Exception {
        MaintenanceItem ordre = papier(MaintenanceType.OPERATION, MaintenanceStatus.IN_PROGRESS);

        String motive = "Durite de refroidissement suintante, à reprendre avec la prochaine vidange.";
        mvc.perform(put("/api/maintenance/" + ordre.getId()).with(chef())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(MaintenanceType.OPERATION, ordre.getNumber(), ordre, motive, true, 2.0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomalies").value(motive));

        assertEquals(motive, relire(ordre.getId()).get("anomalies"), "la colonne porte le texte de l'atelier");
        assertTrue(Boolean.TRUE.equals(relire(ordre.getId()).get("done_lu")),
                "le coche de la ligne part avec l'anomalie, sinon la clôture ne trouvera rien");
        assertEquals(2.0, ((Number) relire(ordre.getId()).get("heures_lues")).doubleValue(), 0.0001);
    }

    /**
     * L'atelier efface la case : le papier se relit sans anomalie. Un formulaire qui
     * « garde ce qui était écrit » sur une case vide ferait revivre une panne réglée.
     */
    @Test
    void une_case_vide_efface_ce_qui_etait_ecrit() throws Exception {
        MaintenanceItem ordre = papier(MaintenanceType.OPERATION, MaintenanceStatus.IN_PROGRESS);
        ordre.setAnomalies("Suintement constaté.");
        documents.saveAndFlush(ordre);
        assertEquals("Suintement constaté.", relire(ordre.getId()).get("anomalies"));

        mvc.perform(put("/api/maintenance/" + ordre.getId()).with(chef())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(MaintenanceType.OPERATION, ordre.getNumber(), ordre, "  ", true, 2.0)))
                .andExpect(status().isOk());

        assertNull(relire(ordre.getId()).get("anomalies"), "la case vide veut dire vide");
    }

    /**
     * Le devis n'est pas le papier du travail : qui y note une anomalie écrit dans une
     * case que l'écran ne relit pas, et que personne ne lira sur le bus. Le refus se fait
     * entendre, et rien d'autre ne bouge sur le papier.
     */
    @Test
    void un_devis_ne_recoit_pas_cette_case_et_le_dit_sans_rien_ecrire() throws Exception {
        MaintenanceItem devis = papier(MaintenanceType.DEVIS, MaintenanceStatus.DRAFT);

        mvc.perform(put("/api/maintenance/" + devis.getId()).with(chef())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(MaintenanceType.DEVIS, devis.getNumber(), devis,
                                "Anomalie notée sur le mauvais papier.", false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("ordre de travail")));

        assertNull(relire(devis.getId()).get("anomalies"), "le refus ne laisse pas de miette en base");
        assertEquals("DRAFT", relire(devis.getId()).get("status"));
    }

    /**
     * Un poste qui n'envoie pas la case — l'écran du devis, par exemple — enregistre
     * normalement : la garde ne vise que le texte, pas son absence.
     */
    @Test
    void labsence_de_cette_case_ne_bloque_rien() throws Exception {
        MaintenanceItem devis = papier(MaintenanceType.DEVIS, MaintenanceStatus.DRAFT);

        mvc.perform(put("/api/maintenance/" + devis.getId()).with(chef())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps(MaintenanceType.DEVIS, devis.getNumber(), devis, null, false, null)))
                .andExpect(status().isOk());

        assertNull(relire(devis.getId()).get("anomalies"));
    }
}
