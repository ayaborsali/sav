package tn.higer.sav_backend.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La table des routes, telle que le serveur la dresse vraiment.
 *
 * Un test qui appelle {@code Access.readsFleetAndClients} prouve la règle ; il ne
 * prouve pas qu'elle est posée sur la route. Ce fichier passe donc par la chaîne de
 * filtres complète, sur l'URL que le portail appelle : {@code GET /api/vehicles} et
 * {@code GET /api/clients}. C'est là que le défaut tenait — la règle générique « tout
 * GET authentifié est permis » laissait n'importe quel compte connecté parcourir le
 * parc de bus et l'annuaire des clients.
 *
 * Les deux sens sont vérifiés : les trois rôles qui travaillent des fiches passent,
 * les deux qui n'en ont pas besoin butent sur un 403 — le technicien, qui reçoit le
 * bus qu'on lui désigne, et la consultation, le regard d'un seul client sur SES
 * dossiers. Le 401 sans jeton est testé pareil : une liste publique ne serait pas un
 * refus, ce serait une fuite qui n'a jamais été demandée.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FichesPortefeuilleRouteTest {

    @Autowired private MockMvc mvc;

    @BeforeAll
    static void exigerUneVraieBase() {
        // Le contexte printanier se monte sur PostgreSQL : sans base, rien ne démarre.
        Assumptions.assumeTrue(System.getenv("DB_PASSWORD") != null || System.getenv("CI") != null,
                "Skippé localement : ce test démarre l'application et sa chaîne de filtres.");
    }

    /** Le compte, avec son rôle écrit comme le convertisseur du jeton le produit. */
    private static RequestPostProcessor compte(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private RequestBuilder lis(String url, String role) {
        return get(url).with(compte(role));
    }

    @Test
    void leGuichetLitLeParc() throws Exception {
        mvc.perform(lis("/api/vehicles", "GESTIONNAIRE_SAV")).andExpect(status().isOk());
    }

    @Test
    void latelierLitLeParc() throws Exception {
        mvc.perform(lis("/api/vehicles", "CHEF_ATELIER")).andExpect(status().isOk());
        mvc.perform(lis("/api/clients", "CHEF_ATELIER")).andExpect(status().isOk());
    }

    @Test
    void ladminLitLeParcEtLesClients() throws Exception {
        mvc.perform(lis("/api/vehicles", "ADMIN")).andExpect(status().isOk());
        mvc.perform(lis("/api/clients", "ADMIN")).andExpect(status().isOk());
    }

    @Test
    void leTechnicienNeParcourtPasLeParc() throws Exception {
        mvc.perform(lis("/api/vehicles", "TECHNICIEN")).andExpect(status().isForbidden());
        mvc.perform(lis("/api/clients", "TECHNICIEN")).andExpect(status().isForbidden());
    }

    /**
     * Le regard du client : ses dossiers, pas l'inventaire de Saphir. La fiche d'un
     * bus, atteinte directement par son identifiant, est fermée pareil — la règle
     * porte sur la famille de routes, pas sur le bouton de la liste.
     */
    @Test
    void laConsultationNeVoitNiLesBusNiLesAutresClients() throws Exception {
        mvc.perform(lis("/api/vehicles", "CONSULTATION")).andExpect(status().isForbidden());
        mvc.perform(lis("/api/clients", "CONSULTATION")).andExpect(status().isForbidden());
        mvc.perform(lis("/api/vehicles/veh-1", "CONSULTATION")).andExpect(status().isForbidden());
    }

    @Test
    void sansJetonLaListeEstRefuseeAvantDeDemanderQuiOnEst() throws Exception {
        mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
    }
}
