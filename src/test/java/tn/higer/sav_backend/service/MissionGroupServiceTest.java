package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusDiagnosisRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.BusLineRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.CocherRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.ConstatRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DepartRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierRequest;
import tn.higer.sav_backend.dto.MissionGroupDtos.DossierResponse;
import tn.higer.sav_backend.dto.MissionGroupDtos.HistoryRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.MyDossierRow;
import tn.higer.sav_backend.dto.MissionGroupDtos.RetourRequest;
import tn.higer.sav_backend.model.AppUser;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.MissionGroupBus;
import tn.higer.sav_backend.model.MissionGroupChecklistItem;
import tn.higer.sav_backend.model.MissionGroupDossier;
import tn.higer.sav_backend.model.MissionGroupPhotoAngle;
import tn.higer.sav_backend.model.Role;
import tn.higer.sav_backend.model.ServiceVehicle;
import tn.higer.sav_backend.model.ServiceVehicleStatus;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionGroupAttachmentRepository;
import tn.higer.sav_backend.repository.MissionGroupBusRepository;
import tn.higer.sav_backend.repository.MissionGroupChecklistItemRepository;
import tn.higer.sav_backend.repository.MissionGroupDossierRepository;
import tn.higer.sav_backend.repository.MissionGroupPhotoRepository;
import tn.higer.sav_backend.repository.ServiceVehicleRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.UserRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les deux temps du dossier groupe, sans base : l'atelier prépare depuis le
 * portefeuille (client, ses bus, homme, voiture, départ prévu), le technicien
 * affecté pointe le départ réel puis le retour. Le serveur tient le numéro, les
 * portefeuilles et les états ; les tests le vérifient geste par geste.
 */
@ExtendWith(MockitoExtension.class)
class MissionGroupServiceTest {

    private static final String TECH_ID = "6f0d1c1e-1111-4a2b-9c3d-000000000001";
    private static final String AUTRE_TECH = "7f0d1c1e-2222-4a2b-9c3d-000000000002";

    @Mock private MissionGroupDossierRepository dossiers;
    @Mock private MissionGroupBusRepository buses;
    @Mock private MissionGroupPhotoRepository photos;
    @Mock private MissionGroupChecklistItemRepository checklist;
    @Mock private MissionGroupAttachmentRepository attachments;
    @Mock private ClientRepository clients;
    @Mock private VehicleRepository vehicles;
    @Mock private UserRepository users;
    @Mock private ServiceVehicleRepository serviceVehicles;
    @Mock private InterventionRepository interventions;
    @Mock private TechnicianRepository technicians;
    @Mock private AuditService audit;
    @Mock private PushNotificationService push;

    private MissionGroupService service() {
        return new MissionGroupService(dossiers, buses, photos, checklist, attachments, clients,
                vehicles, users, serviceVehicles, interventions, technicians, audit, push);
    }

    private static Jwt jeton() {
        return jeton(TECH_ID);
    }

    private static Jwt jeton(String subject) {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static Authentication chef() {
        return auth("ROLE_CHEF_ATELIER", "usr-chef");
    }

    private static Authentication technicien() {
        return auth("ROLE_TECHNICIEN", TECH_ID);
    }

    private static Authentication auth(String role, String name) {
        return new UsernamePasswordAuthenticationToken(name, null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    private static Client client() {
        Client c = new Client();
        c.setId("cli-1");
        c.setName("SRT Sousse");
        return c;
    }

    private static Vehicle busDuClient() {
        Vehicle v = new Vehicle();
        v.setId("veh-1");
        v.setRegistration("230 TU 3342");
        v.setClientId("cli-1");
        return v;
    }

    private static AppUser homme() {
        AppUser u = new AppUser();
        u.setId(UUID.fromString(TECH_ID));
        u.setName("Moez");
        u.setRole(Role.TECHNICIEN);
        u.setActive(true);
        return u;
    }

    private static ServiceVehicle voiture() {
        ServiceVehicle v = new ServiceVehicle();
        v.setId("sv-1");
        v.setRegistration("TU 1234");
        v.setActive(true);
        v.setStatus(ServiceVehicleStatus.AVAILABLE);
        return v;
    }

    /** La fiche de paie de l'homme, derrière le compte du portail. */
    private static Technician ficheTechnicien() {
        Technician t = new Technician();
        t.setId("tec-9");
        t.setName("Moez");
        t.setUserId(UUID.fromString(TECH_ID));
        return t;
    }

    /** Une sortie de dépannage qui n'est pas encore clôturée : l'homme et la voiture sont dehors. */
    private static Intervention sortieOuverte(String numero, InterventionStatus statut) {
        Intervention i = new Intervention();
        i.setId("itv-" + numero);
        i.setNumber(numero);
        i.setTechnicianId("tec-9");
        i.setServiceVehicleId("sv-1");
        i.setStatus(statut);
        return i;
    }

    private static DossierRequest demande(String numero, String clientId) {
        return new DossierRequest(numero, clientId,
                LocalDateTime.of(2026, 11, 11, 8, 0), TECH_ID, "sv-1",
                List.of(new BusLineRequest("veh-1", "problème bielle", null)),
                List.of("Changer la bielle", "Essayer le bus"),
                "telephone", LocalDateTime.of(2026, 11, 11, 18, 0), 45_120L, 45_500L);
    }

    /** Les deux photos de la voiture à chaque bout du voyage, telles que versées avant le pointage. */
    private void preuvesDeLaVoiture() {
        lenient().when(photos.existsByDossierIdAndAngle("mgd-1", MissionGroupPhotoAngle.DEPART))
                .thenReturn(true);
        lenient().when(photos.existsByDossierIdAndAngle("mgd-1", MissionGroupPhotoAngle.COMPTEUR_DEPART))
                .thenReturn(true);
        lenient().when(photos.existsByDossierIdAndAngle("mgd-1", MissionGroupPhotoAngle.RETOUR))
                .thenReturn(true);
        lenient().when(photos.existsByDossierIdAndAngle("mgd-1", MissionGroupPhotoAngle.COMPTEUR_RETOUR))
                .thenReturn(true);
    }

    private void portefeuilleComplet() {
        portefeuilleSansBus();
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(busDuClient()));
    }

    /** La période et le canal se contrôlent avant de toucher aux bus. */
    private void portefeuilleSansBus() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(users.findById(UUID.fromString(TECH_ID))).thenReturn(Optional.of(homme()));
        when(serviceVehicles.findById("sv-1")).thenReturn(Optional.of(voiture()));
    }

    private static MissionGroupDossier dossierPrepare() {
        MissionGroupDossier d = new MissionGroupDossier();
        d.setId("mgd-1");
        d.setNumber("1740");
        d.setClientId("cli-1");
        d.setClientName("SRT Sousse");
        d.setPlannedDepartureAt(LocalDateTime.of(2026, 11, 11, 8, 0));
        d.setTechnicianUserId(TECH_ID);
        d.setTechnicianName("Moez");
        d.setServiceVehicleId("sv-1");
        d.setServiceVehicleRegistration("TU 1234");
        d.setCreatedAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        return d;
    }

    private static MissionGroupDossier dossierParti() {
        MissionGroupDossier d = dossierPrepare();
        d.setDepartedAt(LocalDateTime.of(2026, 11, 11, 8, 15));
        d.setDepartureMileage(45_120L);
        return d;
    }

    private static MissionGroupBus ligne() {
        MissionGroupBus b = new MissionGroupBus();
        b.setId("mgb-1");
        b.setDossierId("mgd-1");
        b.setPosition(1);
        b.setVehicleId("veh-1");
        b.setRegistration("230 TU 3342");
        b.setRegistrationKey(MissionGroupService.cleImmatriculation("230 TU 3342"));
        b.setDescription("problème bielle");
        return b;
    }

    /** Le bus tel qu'il revient : le constat écrit sur place ne manque plus. */
    private static MissionGroupBus ligneConstatee() {
        MissionGroupBus b = ligne();
        b.setDefectCode("P0087");
        b.setCausingPart("filtre à gasoil");
        b.setObservations("Filtre changé, purge faite, essai concluant.");
        return b;
    }

    private void lectureDossier(MissionGroupDossier dossier) {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(ligne()));
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of());
    }

    private static MissionGroupChecklistItem ligneFiche(String id, String label, boolean done) {
        MissionGroupChecklistItem item = new MissionGroupChecklistItem();
        item.setId(id);
        item.setDossierId("mgd-1");
        item.setPosition(1);
        item.setLabel(label);
        item.setDone(done);
        return item;
    }

    // ------------------------------------------------------------------- numéro

    @Test
    void le_premier_numero_propose_est_1740() {
        when(dossiers.findAllNumbers()).thenReturn(List.of());
        assertEquals("1740", service().prochainNumero());
    }

    @Test
    void le_numero_suit_le_plus_grand_et_non_le_nombre_de_lignes() {
        when(dossiers.findAllNumbers()).thenReturn(List.of("1740", "1742"));
        assertEquals("1743", service().prochainNumero());
    }

    // -------------------------------------------------------------- préparation

    @Test
    void l_atelier_prepare_le_dossier_depuis_le_portefeuille() {
        portefeuilleComplet();
        when(dossiers.existsByNumberIgnoreCase("1740")).thenReturn(false);

        service().creer(demande("1740", "cli-1"), jeton("usr-chef"), chef());

        ArgumentCaptor<MissionGroupDossier> dossierEcrit =
                ArgumentCaptor.forClass(MissionGroupDossier.class);
        verify(dossiers).save(dossierEcrit.capture());
        MissionGroupDossier ecrit = dossierEcrit.getValue();
        assertEquals("SRT Sousse", ecrit.getClientName());
        assertEquals("Moez", ecrit.getTechnicianName());
        assertEquals("TU 1234", ecrit.getServiceVehicleRegistration());
        assertNull(ecrit.getDepartedAt());
        assertNull(ecrit.getDepartureMileage());

        ArgumentCaptor<MissionGroupBus> busEcrit = ArgumentCaptor.forClass(MissionGroupBus.class);
        verify(buses).save(busEcrit.capture());
        // La plaque vient de la fiche du bus : l'écran ne l'a pas retapée.
        assertEquals("230 TU 3342", busEcrit.getValue().getRegistration());
        assertEquals("veh-1", busEcrit.getValue().getVehicleId());

        // La fiche atelier s'écrit à la préparation, dans l'ordre de saisie.
        ArgumentCaptor<MissionGroupChecklistItem> fiche =
                ArgumentCaptor.forClass(MissionGroupChecklistItem.class);
        verify(checklist, org.mockito.Mockito.times(2)).save(fiche.capture());
        assertEquals("Changer la bielle", fiche.getAllValues().get(0).getLabel());
        assertEquals(1, fiche.getAllValues().get(0).getPosition());
    }

    @Test
    void le_dossier_prepare_annonce_au_telephone_de_l_homme_affecte() {
        portefeuilleComplet();
        when(dossiers.existsByNumberIgnoreCase("1740")).thenReturn(false);

        service().creer(demande("1740", "cli-1"), jeton("usr-chef"), chef());

        ArgumentCaptor<PushNotificationService.Message> message =
                ArgumentCaptor.forClass(PushNotificationService.Message.class);
        verify(push).annoncer(org.mockito.ArgumentMatchers.eq(TECH_ID), message.capture());
        assertEquals("Nouveau dossier 1740", message.getValue().title());
        assertTrue(message.getValue().url().startsWith("/?dossier="));
        assertTrue(message.getValue().body().contains("SRT Sousse"));
        assertTrue(message.getValue().body().contains("départ prévu"));
    }

    @Test
    void le_numero_est_propose_quand_la_case_est_vide() {
        portefeuilleComplet();
        when(dossiers.findAllNumbers()).thenReturn(List.of("1739"));

        service().creer(demande("", "cli-1"), jeton("usr-chef"), chef());

        ArgumentCaptor<MissionGroupDossier> captor = ArgumentCaptor.forClass(MissionGroupDossier.class);
        verify(dossiers).save(captor.capture());
        assertEquals("1740", captor.getValue().getNumber());
    }

    @Test
    void le_numero_du_papier_est_garde_tel_quel() {
        portefeuilleComplet();
        when(dossiers.existsByNumberIgnoreCase("1737")).thenReturn(false);

        service().creer(demande("1737", "cli-1"), jeton("usr-chef"), chef());

        ArgumentCaptor<MissionGroupDossier> captor = ArgumentCaptor.forClass(MissionGroupDossier.class);
        verify(dossiers).save(captor.capture());
        assertEquals("1737", captor.getValue().getNumber());
    }

    @Test
    void un_numero_deja_pris_est_refuse() {
        portefeuilleComplet();
        when(dossiers.existsByNumberIgnoreCase("1740")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(demande("1740", "cli-1"), jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("déjà porté"));
        verify(dossiers, never()).save(any());
    }

    @Test
    void un_dossier_sans_bus_est_refuse() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(users.findById(UUID.fromString(TECH_ID))).thenReturn(Optional.of(homme()));
        when(serviceVehicles.findById("sv-1")).thenReturn(Optional.of(voiture()));
        DossierRequest sansBus = new DossierRequest(null, "cli-1",
                LocalDateTime.of(2026, 11, 11, 8, 0), TECH_ID, "sv-1", List.of(), List.of(),
                null, null, null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(sansBus, jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("au moins un bus"));
    }

    @Test
    void un_bus_d_un_autre_client_est_refuse() {
        Vehicle autre = busDuClient();
        autre.setClientId("cli-2");
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(autre));
        when(users.findById(UUID.fromString(TECH_ID))).thenReturn(Optional.of(homme()));
        when(serviceVehicles.findById("sv-1")).thenReturn(Optional.of(voiture()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(demande(null, "cli-1"), jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("n'appartient pas à ce client"));
        verify(dossiers, never()).save(any());
    }

    @Test
    void l_homme_affecte_doit_etre_un_technicien_actif() {
        AppUser parti = homme();
        parti.setActive(false);
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(users.findById(UUID.fromString(TECH_ID))).thenReturn(Optional.of(parti));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(demande(null, "cli-1"), jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("technicien actif"));
    }

    @Test
    void un_compte_sans_droit_ne_peut_pas_creer() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(demande(null, "cli-1"), jeton(), technicien()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(dossiers, never()).save(any());
    }

    // ------------------------------------------------------------------ pointage

    @Test
    void le_technicien_affecte_pointe_le_depart_reel() {
        MissionGroupDossier dossier = dossierPrepare();
        lectureDossier(dossier);
        preuvesDeLaVoiture();
        when(dossiers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DossierResponse rendu = service().depart("mgd-1",
                new DepartRequest(LocalDateTime.of(2026, 11, 11, 8, 15), 45_120L),
                jeton(), technicien());

        assertEquals("en_deplacement", rendu.state());
        assertEquals(LocalDateTime.of(2026, 11, 11, 8, 15), dossier.getDepartedAt());
        assertEquals(45_120L, rendu.departureMileage());
    }

    @Test
    void un_autre_technicien_ne_pointe_pas_le_depart() {
        MissionGroupDossier dossier = dossierPrepare();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().depart("mgd-1",
                        new DepartRequest(LocalDateTime.of(2026, 11, 11, 9, 0), 45_130L),
                        jeton(AUTRE_TECH), technicien()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void le_depart_ne_se_pointe_pas_deux_fois() {
        MissionGroupDossier dossier = dossierParti();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().depart("mgd-1",
                        new DepartRequest(LocalDateTime.of(2026, 11, 11, 9, 0), 45_130L),
                        jeton(), technicien()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("déjà pointé"));
    }

    @Test
    void le_retour_ne_se_pointe_pas_avant_le_depart() {
        MissionGroupDossier dossier = dossierPrepare();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().retour("mgd-1",
                        new RetourRequest(LocalDateTime.of(2026, 11, 11, 16, 0), 45_240L, List.of()),
                        jeton(), technicien()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("avant le départ"));
    }

    @Test
    void un_kilometrage_de_retour_inferieur_au_depart_est_refuse() {
        MissionGroupDossier dossier = dossierParti();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        preuvesDeLaVoiture();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().retour("mgd-1",
                        new RetourRequest(LocalDateTime.of(2026, 11, 11, 16, 0), 45_000L, List.of()),
                        jeton(), technicien()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("compteur ne recule pas"));
    }

    @Test
    void le_retour_ecrit_le_diagnostic_de_chaque_bus_et_calcule_les_kilometres() {
        MissionGroupDossier dossier = dossierParti();
        MissionGroupBus b = ligneConstatee();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(b));
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of());
        preuvesDeLaVoiture();
        when(dossiers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DossierResponse rendu = service().retour("mgd-1",
                new RetourRequest(LocalDateTime.of(2026, 11, 11, 16, 0), 45_240L,
                        List.of(new BusDiagnosisRequest("mgb-1", null, "bielle remplacée"))),
                jeton(), technicien());

        assertEquals("rentre", rendu.state());
        assertEquals(120L, rendu.traveledKm());
        assertEquals("bielle remplacée", rendu.buses().get(0).diagnosis());
        assertEquals(LocalDateTime.of(2026, 11, 11, 16, 0), dossier.getReturnedAt());
        verify(buses).save(b);
    }

    @Test
    void le_retour_attend_le_constat_ecrit_de_chaque_bus() {
        MissionGroupDossier dossier = dossierParti();
        MissionGroupBus b = ligne();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(b));
        preuvesDeLaVoiture();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().retour("mgd-1",
                        new RetourRequest(LocalDateTime.of(2026, 11, 11, 16, 0), 45_240L, List.of()),
                        jeton(), technicien()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("attend le constat de chaque bus"));
        assertTrue(ex.getReason().contains("230 TU 3342"));
        assertTrue(ex.getReason().contains("le code d'erreur"));
        assertTrue(ex.getReason().contains("la pièce en cause"));
        assertTrue(ex.getReason().contains("les observations"));
        assertNull(dossier.getReturnedAt(), "un retour refusé ne pointe rien");
    }

    @Test
    void un_retour_ne_s_ecrit_pas_deux_fois() {
        MissionGroupDossier dossier = dossierParti();
        dossier.setReturnedAt(LocalDateTime.of(2026, 11, 11, 16, 0));
        dossier.setReturnMileage(45_240L);
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().retour("mgd-1",
                        new RetourRequest(LocalDateTime.of(2026, 11, 11, 17, 0), 45_300L, List.of()),
                        jeton(), technicien()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("déjà rentré"));
    }

    @Test
    void le_depart_sans_les_photos_de_la_voiture_est_refuse() {
        MissionGroupDossier dossier = dossierPrepare();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(photos.existsByDossierIdAndAngle("mgd-1", MissionGroupPhotoAngle.DEPART))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().depart("mgd-1",
                        new DepartRequest(LocalDateTime.of(2026, 11, 11, 8, 15), null),
                        jeton(), technicien()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("deux photos"));
        assertTrue(ex.getReason().contains("la voiture"));
        assertNull(dossier.getDepartedAt());
        verify(dossiers, never()).save(any());
    }

    @Test
    void le_depart_sans_chiffre_est_accepte_et_la_photo_fait_foi() {
        MissionGroupDossier dossier = dossierPrepare();
        lectureDossier(dossier);
        preuvesDeLaVoiture();
        when(dossiers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DossierResponse rendu = service().depart("mgd-1",
                new DepartRequest(LocalDateTime.of(2026, 11, 11, 8, 15), null),
                jeton(), technicien());

        assertEquals("en_deplacement", rendu.state());
        assertNull(rendu.departureMileage());
        assertNull(rendu.traveledKm());
    }

    @Test
    void le_constat_ecrit_le_code_la_piece_et_les_observations() {
        MissionGroupDossier dossier = dossierParti();
        MissionGroupBus b = ligne();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(b));
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of());
        when(photos.existsByDossierIdAndBusIdAndAngle("mgd-1", "mgb-1", MissionGroupPhotoAngle.BUS_AVANT))
                .thenReturn(true);
        when(photos.existsByDossierIdAndBusIdAndAngle("mgd-1", "mgb-1", MissionGroupPhotoAngle.BUS_APRES))
                .thenReturn(true);

        DossierResponse rendu = service().constat("mgd-1", "mgb-1",
                new ConstatRequest("P0234", "turbocompresseur", "Fuite d'huile côté échappement."),
                jeton(), technicien());

        assertEquals("P0234", b.getDefectCode());
        assertEquals("turbocompresseur", b.getCausingPart());
        assertEquals("Fuite d'huile côté échappement.", b.getObservations());
        assertEquals("P0234", rendu.buses().get(0).defectCode());
        verify(buses).save(b);
    }

    @Test
    void un_constat_sans_les_photos_du_bus_est_refuse() {
        MissionGroupDossier dossier = dossierParti();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(ligne()));
        when(photos.existsByDossierIdAndBusIdAndAngle("mgd-1", "mgb-1", MissionGroupPhotoAngle.BUS_AVANT))
                .thenReturn(true);
        when(photos.existsByDossierIdAndBusIdAndAngle("mgd-1", "mgb-1", MissionGroupPhotoAngle.BUS_APRES))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().constat("mgd-1", "mgb-1",
                        new ConstatRequest("P0234", null, "vu"),
                        jeton(), technicien()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("bus après"));
        verify(buses, never()).save(any());
    }

    @Test
    void la_fiche_atelier_se_coche_et_une_ligne_oubliee_reste_visible() {
        MissionGroupDossier dossier = dossierParti();
        MissionGroupChecklistItem change = ligneFiche("mgc-1", "Changer la bielle", false);
        MissionGroupChecklistItem essai = ligneFiche("mgc-2", "Essayer le bus", false);
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(ligne()));
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(change, essai));

        DossierResponse apres = service().cocher("mgd-1", "mgc-1", new CocherRequest(true),
                jeton(), technicien());

        assertTrue(change.isDone());
        // L'autre ligne reste ouverte : le retour n'est pas bloqué, mais l'atelier la voit.
        assertTrue(apres.checklistIncomplete());
        assertEquals(2, apres.checklist().size());
    }

    // ------------------------------------------------------------------ lecture

    @Test
    void l_historique_normalise_la_plaque_et_rend_le_dossier_parti() {
        MissionGroupBus b = ligne();
        when(buses.findByRegistrationKeyOrderByCreatedAtDesc("230TU3342"))
                .thenReturn(List.of(b));
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossierParti()));

        List<HistoryRow> rows = service().historique("230-TU-3342", chef());

        assertEquals(1, rows.size());
        assertEquals("1740", rows.get(0).number());
        assertEquals("230 TU 3342", rows.get(0).registration());
    }

    @Test
    void l_historique_ignore_un_dossier_pas_encore_parti() {
        MissionGroupBus b = ligne();
        when(buses.findByRegistrationKeyOrderByCreatedAtDesc("230TU3342"))
                .thenReturn(List.of(b));
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossierPrepare()));

        assertTrue(service().historique("230-TU-3342", chef()).isEmpty());
    }

    @Test
    void le_technicien_ne_voit_que_ses_dossiers_dans_la_liste() {
        Pageable pageable = PageRequest.of(0, 20);
        when(dossiers.findByTechnicianUserIdOrderByCreatedAtDesc(TECH_ID, pageable))
                .thenReturn(Page.empty(pageable));

        assertTrue(service().list(pageable, jeton(), technicien()).isEmpty());
        verify(dossiers, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void l_atelier_voit_toute_la_liste() {
        Pageable pageable = PageRequest.of(0, 20);
        when(dossiers.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(Page.empty(pageable));

        assertTrue(service().list(pageable, jeton("usr-chef"), chef()).isEmpty());
        verify(dossiers, never()).findByTechnicianUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void le_suivi_du_telephone_rend_ses_dossiers_en_cours_en_lignes_legeres() {
        when(dossiers.findByTechnicianUserIdAndReturnedAtIsNullOrderByCreatedAtDesc(TECH_ID))
                .thenReturn(List.of(dossierPrepare()));

        List<MyDossierRow> rows = service().mesDossiers(jeton(), technicien());

        assertEquals(1, rows.size());
        assertEquals("1740", rows.get(0).number());
        assertEquals("SRT Sousse", rows.get(0).clientName());
        assertEquals("2026-11-11T08:00", rows.get(0).plannedDepartureAt());
        assertNull(rows.get(0).departedAt());
        verify(dossiers, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void le_suivi_du_telephone_est_ferme_a_l_atelier() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().mesDossiers(jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void un_technicien_ne_lit_pas_le_dossier_d_un_autre() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossierPrepare()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().get("mgd-1", jeton(AUTRE_TECH), technicien()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------------------------------------------------- période estimée et statuts

    @Test
    void le_retour_estime_doit_venir_apres_le_depart_estime() {
        portefeuilleSansBus();
        DossierRequest incoherente = new DossierRequest("1740", "cli-1",
                LocalDateTime.of(2026, 11, 11, 18, 0), TECH_ID, "sv-1",
                List.of(new BusLineRequest("veh-1", "problème bielle", null)), List.of(),
                "telephone", LocalDateTime.of(2026, 11, 11, 8, 0), null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(incoherente, jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("après le départ estimé"), ex.getReason());
        verify(dossiers, never()).save(any());
    }

    @Test
    void le_compteur_estime_ne_recule_pas() {
        portefeuilleSansBus();
        DossierRequest incoherente = new DossierRequest("1740", "cli-1",
                LocalDateTime.of(2026, 11, 11, 8, 0), TECH_ID, "sv-1",
                List.of(new BusLineRequest("veh-1", "problème bielle", null)), List.of(),
                "telephone", LocalDateTime.of(2026, 11, 11, 18, 0), 50_000L, 49_900L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(incoherente, jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("une prévision ne recule pas"), ex.getReason());
    }

    @Test
    void un_canal_d_appel_inconnu_est_refuse() {
        portefeuilleSansBus();
        DossierRequest inconnue = new DossierRequest("1740", "cli-1",
                LocalDateTime.of(2026, 11, 11, 8, 0), TECH_ID, "sv-1",
                List.of(new BusLineRequest("veh-1", "problème bielle", null)), List.of(),
                "pigeon voyageur", LocalDateTime.of(2026, 11, 11, 18, 0), null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().creer(inconnue, jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Canal d'appel inconnu"), ex.getReason());
    }

    @Test
    void le_statut_suit_les_quatre_etapes_du_papier() {
        MissionGroupDossier prepare = dossierPrepare();
        prepare.setPlannedReturnAt(LocalDateTime.of(2026, 11, 11, 18, 0));
        lectureDossier(prepare);
        DossierResponse planifiee = service().get("mgd-1", jeton("usr-chef"), chef());
        assertEquals("planifiee", planifiee.status());
        assertEquals("Planifiée", planifiee.statusLabel());

        MissionGroupDossier parti = dossierParti();
        lectureDossier(parti);
        DossierResponse enCours = service().get("mgd-1", jeton("usr-chef"), chef());
        assertEquals("en_cours", enCours.status(), "le constat n'est pas encore écrit");
        assertEquals("En cours", enCours.statusLabel());

        when(buses.findByDossierIdOrderByPositionAsc("mgd-1"))
                .thenReturn(List.of(ligneConstatee()));
        DossierResponse constat = service().get("mgd-1", jeton("usr-chef"), chef());
        assertEquals("constat", constat.status());
        assertEquals("Constat renseigné", constat.statusLabel());

        parti.setReturnedAt(LocalDateTime.of(2026, 11, 11, 17, 5));
        DossierResponse cloturee = service().get("mgd-1", jeton("usr-chef"), chef());
        assertEquals("cloturee", cloturee.status());
        assertEquals("Clôturée", cloturee.statusLabel());
    }

    // ------------------------------------------------------------- disponibilités

    private static MissionGroupDossier dossierSurLaPeriode(String id, String homme, String voiture,
                                                           LocalDateTime depart, LocalDateTime retour) {
        MissionGroupDossier d = new MissionGroupDossier();
        d.setId(id);
        d.setNumber("1740");
        d.setClientId("cli-1");
        d.setClientName("SRT Sousse");
        d.setTechnicianUserId(homme);
        d.setServiceVehicleId(voiture);
        d.setPlannedDepartureAt(depart);
        d.setPlannedReturnAt(retour);
        return d;
    }

    @Test
    void les_disponibilites_rendent_les_hommes_et_voitures_de_la_periode() {
        when(dossiers.findAll()).thenReturn(List.of(
                dossierSurLaPeriode("mgd-1", TECH_ID, "sv-1",
                        LocalDateTime.of(2026, 11, 11, 8, 0),
                        LocalDateTime.of(2026, 11, 11, 18, 0))));

        var dispo = service().disponibilites(
                LocalDateTime.of(2026, 11, 11, 10, 0),
                LocalDateTime.of(2026, 11, 11, 12, 0), null, chef());

        assertEquals(List.of(TECH_ID), dispo.busyTechnicianUserIds());
        assertEquals(List.of("sv-1"), dispo.busyServiceVehicleIds());
    }

    @Test
    void une_periode_qui_ne_touche_pas_la_mission_laisse_l_homme_libre() {
        when(dossiers.findAll()).thenReturn(List.of(
                dossierSurLaPeriode("mgd-1", TECH_ID, "sv-1",
                        LocalDateTime.of(2026, 11, 11, 8, 0),
                        LocalDateTime.of(2026, 11, 11, 18, 0))));

        var dispo = service().disponibilites(
                LocalDateTime.of(2026, 11, 11, 18, 0),
                LocalDateTime.of(2026, 11, 11, 20, 0), null, chef());

        assertTrue(dispo.busyTechnicianUserIds().isEmpty());
        assertTrue(dispo.busyServiceVehicleIds().isEmpty());
    }

    @Test
    void un_dossier_rentre_ou_le_dossier_modifie_ne_bloquent_plus() {
        MissionGroupDossier rentre = dossierSurLaPeriode("mgd-1", TECH_ID, "sv-1",
                LocalDateTime.of(2026, 11, 11, 8, 0), LocalDateTime.of(2026, 11, 11, 18, 0));
        rentre.setReturnedAt(LocalDateTime.of(2026, 11, 11, 17, 0));
        MissionGroupDossier modifie = dossierSurLaPeriode("mgd-2", AUTRE_TECH, "sv-2",
                LocalDateTime.of(2026, 11, 11, 8, 0), LocalDateTime.of(2026, 11, 11, 18, 0));
        when(dossiers.findAll()).thenReturn(List.of(rentre, modifie));

        var dispo = service().disponibilites(
                LocalDateTime.of(2026, 11, 11, 10, 0),
                LocalDateTime.of(2026, 11, 11, 12, 0), "mgd-2", chef());

        assertTrue(dispo.busyTechnicianUserIds().isEmpty());
        assertTrue(dispo.busyServiceVehicleIds().isEmpty());
    }

    @Test
    void une_mission_en_cours_engage_son_homme_meme_sans_fin_estimee() {
        MissionGroupDossier parti = dossierSurLaPeriode("mgd-1", TECH_ID, "sv-1",
                LocalDateTime.of(2026, 11, 1, 7, 0), null);
        parti.setDepartedAt(LocalDateTime.of(2026, 11, 1, 7, 10));
        when(dossiers.findAll()).thenReturn(List.of(parti));

        var dispo = service().disponibilites(
                LocalDateTime.of(2026, 11, 11, 10, 0),
                LocalDateTime.of(2026, 11, 11, 12, 0), null, chef());

        assertEquals(List.of(TECH_ID), dispo.busyTechnicianUserIds());
        assertEquals(List.of("sv-1"), dispo.busyServiceVehicleIds());
    }

    @Test
    void une_periode_incomplete_ne_calcule_rien() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().disponibilites(
                        LocalDateTime.of(2026, 11, 11, 10, 0), null, null, chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void une_sortie_ouverte_tient_son_homme_et_sa_voiture_pour_toutes_les_dates() {
        when(interventions.findByStatusInOrderByReportedAtDesc(InterventionStatus.OPEN))
                .thenReturn(List.of(sortieOuverte("DP-2026-012", InterventionStatus.DISPATCHED)));
        when(technicians.findAllById(List.of("tec-9"))).thenReturn(List.of(ficheTechnicien()));

        var dispo = service().disponibilites(
                LocalDateTime.of(2027, 2, 1, 8, 0),
                LocalDateTime.of(2027, 2, 1, 18, 0), null, chef());

        assertEquals(List.of(TECH_ID), dispo.busyTechnicianUserIds());
        assertEquals(List.of("sv-1"), dispo.busyServiceVehicleIds());
    }

    @Test
    void une_sortie_retournee_rend_la_voiture_mais_garde_l_homme_jusqu_a_la_cloture() {
        when(interventions.findByStatusInOrderByReportedAtDesc(InterventionStatus.OPEN))
                .thenReturn(List.of(sortieOuverte("DP-2026-012", InterventionStatus.RETURNED)));
        when(technicians.findAllById(List.of("tec-9"))).thenReturn(List.of(ficheTechnicien()));

        var dispo = service().disponibilites(
                LocalDateTime.of(2027, 2, 1, 8, 0),
                LocalDateTime.of(2027, 2, 1, 18, 0), null, chef());

        assertEquals(List.of(TECH_ID), dispo.busyTechnicianUserIds());
        assertTrue(dispo.busyServiceVehicleIds().isEmpty());
    }

    @Test
    void enregistrer_refuse_un_homme_dehors_sur_une_sortie() {
        portefeuilleSansBus();
        when(interventions.findByStatusInOrderByReportedAtDesc(InterventionStatus.OPEN))
                .thenReturn(List.of(sortieOuverte("DP-2026-012", InterventionStatus.DISPATCHED)));
        when(technicians.findAllById(List.of("tec-9"))).thenReturn(List.of(ficheTechnicien()));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().creer(demande("1740", "cli-1"), jeton("usr-chef"), chef()));

        assertEquals(HttpStatus.CONFLICT, refus.getStatusCode());
        assertTrue(refus.getReason().contains("DP-2026-012"));
        verify(dossiers, never()).save(any());
    }

    @Test
    void enregistrer_refuse_une_voiture_que_l_atelier_garde_ou_qui_est_en_panne() {
        portefeuilleSansBus();
        ServiceVehicle aLAtelier = voiture();
        aLAtelier.setStatus(ServiceVehicleStatus.AT_WORKSHOP);
        when(serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(any()))
                .thenReturn(List.of(aLAtelier));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().creer(demande("1740", "cli-1"), jeton("usr-chef"), chef()));

        assertEquals(HttpStatus.CONFLICT, refus.getStatusCode());
        assertTrue(refus.getReason().contains("à l'atelier"));
        verify(dossiers, never()).save(any());
    }

    @Test
    void modifier_un_dossier_ne_se_bloque_pas_lui_meme() {
        portefeuilleComplet();
        MissionGroupDossier dossier = dossierPrepare();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(dossiers.findAll()).thenReturn(List.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of());
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of());
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());

        service().modifier("mgd-1", demande("1740", "cli-1"), jeton("usr-chef"), chef());

        verify(dossiers).save(dossier);
    }

    // ----------------------------------------------------------- modification

    @Test
    void l_atelier_modifie_un_dossier_qui_n_est_pas_parti() {
        portefeuilleComplet();
        MissionGroupDossier dossier = dossierPrepare();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(ligne()));
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1"))
                .thenReturn(List.of(ligneFiche("mgc-1", "Ancienne ligne", false)));
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());
        when(dossiers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DossierResponse rendu = service().modifier("mgd-1", demande("1741", "cli-1"),
                jeton("usr-chef"), chef());

        assertEquals("1741", dossier.getNumber());
        assertEquals(LocalDateTime.of(2026, 11, 11, 18, 0), dossier.getPlannedReturnAt());
        assertEquals("Téléphone", rendu.callChannelLabel(), "le canal se relit en toutes lettres");
        verify(buses).deleteAll(any());
        verify(checklist).deleteAll(any());
    }

    @Test
    void un_dossier_parti_ne_se_modifie_plus() {
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossierParti()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().modifier("mgd-1", demande("1741", "cli-1"),
                        jeton("usr-chef"), chef()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("déjà parti"), ex.getReason());
    }

    @Test
    void la_modification_garde_le_numero_s_il_n_est_pas_change() {
        portefeuilleComplet();
        MissionGroupDossier dossier = dossierPrepare();
        when(dossiers.findById("mgd-1")).thenReturn(Optional.of(dossier));
        when(buses.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of(ligne()));
        when(checklist.findByDossierIdOrderByPositionAsc("mgd-1")).thenReturn(List.of());
        when(photos.findByDossierIdOrderByUploadedAtAsc("mgd-1")).thenReturn(List.of());
        when(dossiers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DossierResponse rendu = service().modifier("mgd-1", demande(null, "cli-1"),
                jeton("usr-chef"), chef());

        assertEquals("1740", rendu.number(), "un numéro vide garde celui du dossier");
        verify(dossiers, never()).existsByNumberIgnoreCase(any());
    }
}
