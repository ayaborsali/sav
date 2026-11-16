package tn.higer.sav_backend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La route « déclarer en qualité », telle que le serveur la dresse vraiment.
 *
 * Le service se teste déjà en base ; ce fichier ne prouve pas la règle métier, il
 * prouve trois choses qu'un appel direct au service ne touche jamais :
 *   — l'URL que l'écran Dépannage va frapper existe, et sous ce nom-là ;
 *   — son corps est optionnel : un clic sans rien taper doit passer. Une case
 *     {@code @RequestBody} obligatoire rendrait un 400 au chef qui déclare une
 *     anomalie sans avoir écrit la criticité — et l'atelier appellerait ça un portail
 *     qui refuse ;
 *   — la garde d'accès est bien posée sur la route, pas seulement dans le service.
 *
 * Le 404 sur une sortie inconnue est le signe attendu : la requête a traversé la chaîne
 * de filtres, trouvé le contrôleur, et le service a répondu « Sortie introuvable ». Un
 * 405 ou un 415 à la place dirait que la route ou sa lecture de corps est mal branchée.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LaRouteDeDeclarationEnQualiteEnBaseTest {

    private static final String INCONNUE = "/api/interventions/itv-route-inconnue/declaration-qualite";

    @Autowired private MockMvc mvc;

    @BeforeAll
    static void exigerUneVraieBase() {
        // Le contexte printanier se monte sur PostgreSQL : sans base, rien ne démarre.
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test démarre l'application et sa chaîne de filtres.");
    }

    private static RequestPostProcessor compte(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** Le chef clique : sans corps, et avec un corps vide — les deux doivent être atteints. */
    @Test
    void la_route_est_la_et_son_corps_peut_rester_vide() throws Exception {
        mvc.perform(post(INCONNUE).with(compte("CHEF_ATELIER")))
                .andExpect(status().isNotFound());
        mvc.perform(post(INCONNUE).with(compte("CHEF_ATELIER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(INCONNUE).with(compte("ADMIN")))
                .andExpect(status().isNotFound());
    }

    /** Les cases de l'atelier, elles, se lisent depuis le corps. */
    @Test
    void le_corps_json_se_lit() throws Exception {
        mvc.perform(post(INCONNUE).with(compte("CHEF_ATELIER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"classification\":\"Freinage\",\"severity\":\"Majeure\","
                        + "\"correctiveActions\":\"Disque remplacé\",\"preventiveActions\":\"\"}"))
                .andExpect(status().isNotFound());
    }

    /** Le guichet ne déclare pas sur une ligne de sortie — la porte est fermée avant le service. */
    @Test
    void le_guichet_nouvre_pas_cette_route() throws Exception {
        mvc.perform(post(INCONNUE).with(compte("GESTIONNAIRE_SAV")))
                .andExpect(status().isForbidden());
    }

    /** Le terrain ne déclare pas non plus : une anomalie se signale de l'atelier. */
    @Test
    void le_technicien_nouvre_pas_cette_route() throws Exception {
        mvc.perform(post(INCONNUE).with(compte("TECHNICIEN")))
                .andExpect(status().isForbidden());
    }

    /** Sans jeton, rien : un geste qui écrit dans les dossiers du SAV ne se poste pas au monde. */
    @Test
    void sans_jeton_la_route_refuse() throws Exception {
        mvc.perform(post(INCONNUE)).andExpect(status().isUnauthorized());
    }
}
