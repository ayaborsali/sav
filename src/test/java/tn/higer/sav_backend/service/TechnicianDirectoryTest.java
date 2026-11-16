package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianLookup;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les gens viennent de la table `users`, pas d'un champ de texte.
 *
 * Ce test vérifie les trois promesses du chantier : la liste de choix ne montre
 * aucun chiffre de paie, une personne sans dossier de paie en reçoit un tout neuf
 * à taux vide (et non un taux inventé), et un compte qui n'est pas un technicien
 * actif est refusé par un message lisible.
 */
@ExtendWith(MockitoExtension.class)
class TechnicianDirectoryTest {

    @Mock private TechnicianRepository technicians;
    @Mock private UserRepository users;
    @Mock private AuditService audit;

    private static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SALMA = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private TechnicianDirectory directory() {
        return new TechnicianDirectory(technicians, users, audit);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static AppUser account(UUID id, String name, String email, Role role, boolean active) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setName(name);
        u.setEmail(email);
        u.setRole(role);
        u.setActive(active);
        return u;
    }

    private static Technician fiche(String id, String name, UUID userId) {
        Technician t = new Technician();
        t.setId(id);
        t.setName(name);
        t.setUserId(userId);
        t.setActive(true);
        return t;
    }

    // ------------------------------------------------------------------ liste de choix

    @Test
    void laListeDeChoixNeMontreQueLeNomDEmailEtLaFiche() {
        when(users.findByRoleAndActiveTrueOrderByNameAsc(Role.TECHNICIEN))
                .thenReturn(List.of(
                        account(ALI, "Ali Trabelsi", "ali@saphir.tn", Role.TECHNICIEN, true),
                        account(SALMA, "Salma Ben Ali", "salma@saphir.tn", Role.TECHNICIEN, true)));
        when(technicians.findByUserIdIn(anyList()))
                .thenReturn(List.of(fiche("tch-1", "Ali Trabelsi", ALI)));

        List<TechnicianLookup> options = directory().options();

        assertEquals(2, options.size());
        assertEquals("ali@saphir.tn", options.get(0).email());
        assertEquals("tch-1", options.get(0).technicianId());
        // Salma n'a pas encore de dossier de paie : elle est choisissable quand même,
        // la fiche se créera au premier envoi.
        assertNull(options.get(1).technicianId());
        assertEquals(SALMA.toString(), options.get(1).userId());
    }

    @Test
    void aucunChiffreDePaieNeSortDeLaListeDeChoix() {
        // Le tamis est structurel : le DTO de choix ne peut pas porter de montant,
        // parce qu'il ne déclare que ces quatre colonnes.
        assertThatRecordPorteSeulement(TechnicianLookup.class, "userId", "name", "email", "technicianId");
    }

    @Test
    void sansCompteTechnicienLaListeEstVideEtNinterrogePersonne() {
        when(users.findByRoleAndActiveTrueOrderByNameAsc(Role.TECHNICIEN)).thenReturn(List.of());

        assertTrue(directory().options().isEmpty());
        verify(technicians, never()).findByUserIdIn(anyList());
    }

    // ------------------------------------------------------------- fiche créée ou reliée

    @Test
    void unePersonneSansFicheRecitUneFicheSansTaux() {
        AppUser ali = account(ALI, "Ali Trabelsi", "ali@saphir.tn", Role.TECHNICIEN, true);
        when(users.findById(ALI)).thenReturn(Optional.of(ali));
        when(technicians.findByUserId(ALI)).thenReturn(Optional.empty());
        when(technicians.findByNameIgnoreCase("Ali Trabelsi")).thenReturn(Optional.empty());

        Technician created = directory().ficheFor(ALI, jwt());

        assertEquals("Ali Trabelsi", created.getName());
        assertEquals(ALI, created.getUserId());
        assertTrue(created.isActive());
        // Rien n'est inventé à la place d'un taux que Saphir n'a pas encore communiqué :
        // la prime se signalera elle-même comme non calculable.
        assertNull(created.getHourlyRate());
        assertNull(created.getDispatchAllowance());
        verify(technicians).save(created);
        verify(audit).record(eq("CREATE"), eq("TECHNICIAN"), anyString(), anyString(), any(Jwt.class));
    }

    @Test
    void uneFicheDejaEcriteALaMainSeRelieAuCompteSansDoublon() {
        when(users.findById(ALI))
                .thenReturn(Optional.of(account(ALI, "Ali Trabelsi", "ali@saphir.tn", Role.TECHNICIEN, true)));
        when(technicians.findByUserId(ALI)).thenReturn(Optional.empty());
        Technician old = fiche("tch-ancienne", "ali trabelsi", null);
        old.setHourlyRate(new BigDecimal("12.500"));
        when(technicians.findByNameIgnoreCase("Ali Trabelsi")).thenReturn(Optional.of(old));

        Technician linked = directory().ficheFor(ALI, jwt());

        assertEquals("tch-ancienne", linked.getId());
        assertEquals(ALI, linked.getUserId());
        assertEquals(new BigDecimal("12.500"), linked.getHourlyRate());
        verify(audit).record(eq("UPDATE"), eq("TECHNICIAN"), eq("tch-ancienne"), anyString(), any(Jwt.class));
    }

    @Test
    void uneFichePriseEnMainParUnAutreCompteNeSeVolePas() {
        when(users.findById(ALI))
                .thenReturn(Optional.of(account(ALI, "Ali Trabelsi", "ali@saphir.tn", Role.TECHNICIEN, true)));
        when(technicians.findByUserId(ALI)).thenReturn(Optional.empty());
        when(technicians.findByNameIgnoreCase("Ali Trabelsi"))
                .thenReturn(Optional.of(fiche("tch-2", "Ali Trabelsi", SALMA)));

        Technician created = directory().ficheFor(ALI, jwt());

        assertNotEquals("tch-2", created.getId());
        assertEquals(ALI, created.getUserId());
    }

    // ------------------------------------------------------------------------ refus écrits

    @Test
    void unCompteAdminNeSortPasEnDeplacement() {
        when(users.findById(ALI))
                .thenReturn(Optional.of(account(ALI, "Administrateur", "admin@higerbus.tn", Role.ADMIN, true)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> directory().ficheFor(ALI, jwt()));
        assertTrue(e.getReason().contains("pas un compte technicien"), e.getReason());
        assertTrue(e.getReason().contains("admin@higerbus.tn"), e.getReason());
        verify(technicians, never()).save(any());
    }

    @Test
    void unCompteDesactiveEstRefuse() {
        when(users.findById(ALI))
                .thenReturn(Optional.of(account(ALI, "Ali Trabelsi", "ali@saphir.tn", Role.TECHNICIEN, false)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> directory().ficheFor(ALI, jwt()));
        assertTrue(e.getReason().contains("désactivé"), e.getReason());
    }

    @Test
    void unCompteSupprimeDuPortailEstRefuseParSonMessage() {
        when(users.findById(ALI)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> directory().ficheFor(ALI, jwt()));
        assertTrue(e.getReason().contains("n'existe plus"), e.getReason());
    }

    /** La liste de choix est la seule fenêtre ouverte sur les gens : elle ne doit pas déborder. */
    private static void assertThatRecordPorteSeulement(Class<?> record, String... columns) {
        assertTrue(record.isRecord(), record.getSimpleName() + " doit rester un record");
        String[] actual = java.util.Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new);
        assertArrayEquals(columns, actual,
                record.getSimpleName() + " ne doit exposer ni taux, ni forfait, ni montant de paie");
    }
}
