package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import tn.higer.sav_backend.dto.MissionDtos.MissionEnvoiRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionReasonRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.service.MissionService;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le contrôleur des missions ne porte aucune règle. Ce qui est figé ici est le
 * face-à-face bouton / route : si « envoyer-un-homme » venait à appeler un autre
 * geste que celui que son nom annonce, l'écran écrirait autre chose que ce que
 * l'atelier a cliqué.
 */
@ExtendWith(MockitoExtension.class)
class MissionControllerTest {

    @Mock private MissionService missions;

    private static final String ID = "mss-1";

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    /** Une ligne d'écran : tout le reste est vide, la route seule est testée ici. */
    private static MissionResponse vue(String etat) {
        return new MissionResponse(ID, "MT-2026-001", etat, etat, null, null,
                "sav-1", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, List.of());
    }

    @Test
    void noter_une_demande_transmet_le_dossier_choisi() {
        MissionRequest requete = new MissionRequest("sav-1", null, "client rappelé deux fois");
        MissionResponse rendu = vue("a_traiter");
        when(missions.creer(any(), any(), any())).thenReturn(rendu);
        Jwt jeton = jeton();

        assertSame(rendu, new MissionController(missions).creer(requete, jeton, chef()));
        verify(missions).creer(requete, jeton, chef());
    }

    /**
     * L'origine d'une demande est soit un dossier, soit un rappel. Le contrôleur ne
     * tranche pas : il passe les deux cases telles qu'elles arrivent, et c'est le
     * service qui refuse les deux à la fois.
     */
    @Test
    void une_demandee_nee_d_un_rappel_passe_par_la_meme_route() {
        MissionRequest requete = new MissionRequest(null, "spa-7", null);
        when(missions.creer(any(), any(), any())).thenReturn(vue("a_traiter"));
        Jwt jeton = jeton();

        new MissionController(missions).creer(requete, jeton, chef());
        verify(missions).creer(requete, jeton, chef());
    }

    @Test
    void chaque_bouton_a_sa_route_et_n_appelle_que_lui() {
        MissionController controller = new MissionController(missions);
        Authentication auth = chef();
        Jwt jeton = jeton();
        MissionReasonRequest motif = new MissionReasonRequest("hors garantie constructeur");

        // Une demande née d'un dossier part sans homme désigné : le corps est vide.
        controller.envoyerUnHomme(ID, null, jeton, auth);
        verify(missions).envoyerUnHomme(ID, null, jeton, auth);

        controller.traiterAtelier(ID, jeton, auth);
        verify(missions).traiterAtelier(ID, jeton, auth);

        controller.etablirDevis(ID, jeton, auth);
        verify(missions).etablirDevis(ID, jeton, auth);

        controller.refuser(ID, motif, jeton, auth);
        verify(missions).refuser(ID, motif, jeton, auth);

        controller.reporter(ID, motif, jeton, auth);
        verify(missions).reporter(ID, motif, jeton, auth);

        controller.reprendre(ID, jeton, auth);
        verify(missions).reprendre(ID, jeton, auth);

        verify(missions, never()).creer(any(), any(), any());
    }

    /**
     * Une sortie d'entretien qui part d'un rappel voyage avec sa voiture et son
     * homme : si le contrôleur perdait ces deux cases, l'écran enverrait du monde
     * sans moyen de transport et le serveur le refuserait sans que personne sache
     * pourquoi.
     */
    @Test
    void la_voiture_et_l_homme_choisis_sur_la_ligne_arrivent_au_service() {
        MissionController controller = new MissionController(missions);
        MissionEnvoiRequest envoi = new MissionEnvoiRequest("vs-1",
                "3f1a0b2c-4d5e-6f70-8192-a3b4c5d6e7f8");
        when(missions.envoyerUnHomme(any(), any(), any(), any())).thenReturn(vue("en_cours"));
        Jwt jeton = jeton();

        controller.envoyerUnHomme(ID, envoi, jeton, chef());
        verify(missions).envoyerUnHomme(ID, envoi, jeton, chef());
    }

    @Test
    void la_lecture_passe_par_le_service_et_n_ecrit_rien() {
        MissionController controller = new MissionController(missions);
        MissionResponse rendu = vue("soldee");
        when(missions.list(any())).thenReturn(null);
        when(missions.get(ID)).thenReturn(rendu);

        controller.list(PageRequest.of(0, 20));
        verify(missions).list(PageRequest.of(0, 20));
        assertSame(rendu, controller.get(ID));
        verify(missions, never()).refuser(any(), any(), any(), any());
    }
}
