package tn.higer.sav_backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests unitaires de la protection anti-force-brute : fenêtre glissante par IP
 * (10 login/min, 5 reset/min) et verrouillage après 5 échecs d'identification.
 * Filtre instancié directement, servlet mocks Spring, aucun port ouvert.
 */
class RateLimitFilterTest {

    private static final String LOGIN = "/api/auth/login";
    private static final String RESET = "/api/auth/request-reset";
    private static final String IP = "203.0.113.9";

    private RateLimitFilter filtre() {
        // enabled, loginMax=10, resetMax=5, window=60s, maxFailures=5, lockout=15min
        return new RateLimitFilter(true, 10, 5, 60, 5, 15);
    }

    /** Chaîne qui laisse la réponse au statut voulu et compte les passages. */
    private static FilterChain chaine(AtomicInteger passages, int statut) {
        return (request, response) -> {
            passages.incrementAndGet();
            ((MockHttpServletResponse) response).setStatus(statut);
        };
    }

    private static MockHttpServletResponse requetePost(RateLimitFilter filtre, String uri, FilterChain chaine)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtre.doFilter(request, response, chaine);
        return response;
    }

    @Test
    void dixAutorisationsPuisQuaranteDeuxSurLogin() throws Exception {
        RateLimitFilter filtre = filtre();
        AtomicInteger passages = new AtomicInteger();
        FilterChain chaine = chaine(passages, 200);

        for (int i = 1; i <= 10; i++) {
            MockHttpServletResponse response = requetePost(filtre, LOGIN, chaine);
            assertEquals(200, response.getStatus(), "Les 10 premières connexions sont autorisées");
        }

        MockHttpServletResponse refusee = requetePost(filtre, LOGIN, chaine);
        assertEquals(429, refusee.getStatus(), "La 11e connexion dépasse la fenêtre glissante");
        assertNotNull(refusee.getHeader("Retry-After"), "La 429 doit indiquer Retry-After");
        assertEquals(10, passages.get(), "La 11e requête ne doit pas atteindre la chaîne");
    }

    @Test
    void cinqAutorisationsPuisQuaranteDeuxSurReset() throws Exception {
        RateLimitFilter filtre = filtre();
        AtomicInteger passages = new AtomicInteger();
        FilterChain chaine = chaine(passages, 200);

        for (int i = 1; i <= 5; i++) {
            MockHttpServletResponse response = requetePost(filtre, RESET, chaine);
            assertEquals(200, response.getStatus(), "Les 5 premières demandes de reset sont autorisées");
        }

        MockHttpServletResponse refusee = requetePost(filtre, RESET, chaine);
        assertEquals(429, refusee.getStatus(), "La 6e demande de reset dépasse la fenêtre glissante");
        assertEquals(5, passages.get());
    }

    @Test
    void verrouillageApresCinqReponses401() throws Exception {
        RateLimitFilter filtre = filtre();
        AtomicInteger passages = new AtomicInteger();
        FilterChain echec = chaine(passages, 401);

        for (int i = 1; i <= 5; i++) {
            MockHttpServletResponse response = requetePost(filtre, LOGIN, echec);
            assertEquals(401, response.getStatus(), "Les tentatives échouées passent par la chaîne");
        }

        // IP verrouillée : la requête suivante est bloquée avant même la fenêtre glissante.
        MockHttpServletResponse bloque = requetePost(filtre, LOGIN, echec);
        assertEquals(429, bloque.getStatus(), "Le verrouillage s'applique après 5 échecs");
        assertNotNull(bloque.getHeader("Retry-After"));
        assertEquals(5, passages.get(), "La requête verrouillée ne doit pas atteindre la chaîne");
    }

    @Test
    void autreIpResteIndependante() throws Exception {
        RateLimitFilter filtre = filtre();
        AtomicInteger passages = new AtomicInteger();
        FilterChain chaine = chaine(passages, 200);

        // On sature la fenêtre pour l'IP de test.
        for (int i = 0; i < 11; i++) {
            requetePost(filtre, LOGIN, chaine);
        }

        // Une autre IP n'est pas affectée par le quota de la première.
        MockHttpServletRequest requeteAutre = new MockHttpServletRequest("POST", LOGIN);
        requeteAutre.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse responseAutre = new MockHttpServletResponse();
        filtre.doFilter(requeteAutre, responseAutre, chaine);
        assertEquals(200, responseAutre.getStatus(), "Le quota est compté par IP");
        assertNull(responseAutre.getHeader("Retry-After"));
    }
}
