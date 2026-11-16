package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianRequest;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.TechnicianDirectory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relier un compte à une fiche technicien : c'est ce qui permet au technicien de
 * déclarer lui-même ses heures. Le chef d'atelier n'ayant pas accès à la page
 * Utilisateurs (réservée à l'ADMIN), la liaison se fait par e-mail.
 */
@ExtendWith(MockitoExtension.class)
class TechnicianLinkTest {

    @Mock
    private TechnicianRepository technicians;

    @Mock
    private UserRepository users;

    @Mock
    private AuditService audit;

    @Mock
    private TechnicianDirectory directory;

    private static final UUID ACCOUNT =
            UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private TechnicianController controller() {
        return new TechnicianController(technicians, users, directory, audit);
    }

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("usr-1", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static AppUser account(String email, Role role) {
        AppUser u = new AppUser();
        u.setId(ACCOUNT);
        u.setName("Sami");
        u.setEmail(email);
        u.setRole(role);
        return u;
    }

    /** Nom seul : la fiche existe, l'e-mail de liaison est facultatif. */
    private static TechnicianRequest request(String name, String email) {
        return new TechnicianRequest(name, null, null, email, null, null, null, null);
    }

    @Test
    void unEmailSuffitPourRelierLeCompte() {
        when(users.findByEmailIgnoreCase("sami@saphir.tn"))
                .thenReturn(Optional.of(account("sami@saphir.tn", Role.TECHNICIEN)));
        when(technicians.findByUserId(ACCOUNT)).thenReturn(Optional.empty());

        controller().create(request("Sami Ben Ali", " sami@saphir.tn "), jwt(), auth("CHEF_ATELIER"));

        ArgumentCaptor<Technician> saved = ArgumentCaptor.forClass(Technician.class);
        verify(technicians).save(saved.capture());
        assertEquals(ACCOUNT, saved.getValue().getUserId());
    }

    @Test
    void unCompteSansLeRoleTechnicienEstRefuse() {
        when(users.findByEmailIgnoreCase("sami@saphir.tn"))
                .thenReturn(Optional.of(account("sami@saphir.tn", Role.GESTIONNAIRE_SAV)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().create(request("Sami Ben Ali", "sami@saphir.tn"), jwt(), auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("rôle technicien"), e.getReason());
    }

    @Test
    void unCompteDejaRelieUneAutreFicheEstRefuse() {
        when(users.findByEmailIgnoreCase("sami@saphir.tn"))
                .thenReturn(Optional.of(account("sami@saphir.tn", Role.TECHNICIEN)));
        Technician other = mock(Technician.class);
        when(other.getId()).thenReturn("tch-2");
        when(other.getName()).thenReturn("Sami");
        when(technicians.findByUserId(ACCOUNT)).thenReturn(Optional.of(other));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().create(request("Sami Ben Ali", "sami@saphir.tn"), jwt(), auth("CHEF_ATELIER")));

        assertTrue(e.getReason().contains("déjà relié"), e.getReason());
    }

    @Test
    void unEmailInconnuDuPortailEstRefuse() {
        when(users.findByEmailIgnoreCase("personne@saphir.tn")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller().create(request("Sami Ben Ali", "personne@saphir.tn"), jwt(), auth("CHEF_ATELIER")));

        assertTrue(e.getReason().contains("Administration"), e.getReason());
    }

    @Test
    void laFicheSeCréeMêmeSansCompteRelie() {
        controller().create(request("Sami Ben Ali", ""), jwt(), auth("CHEF_ATELIER"));

        ArgumentCaptor<Technician> saved = ArgumentCaptor.forClass(Technician.class);
        verify(technicians).save(saved.capture());
        assertEquals(null, saved.getValue().getUserId());
        verify(audit).record(anyString(), anyString(), anyString(), anyString(), any());
    }
}
