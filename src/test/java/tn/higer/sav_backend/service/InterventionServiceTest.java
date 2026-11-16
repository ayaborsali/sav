package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.*;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianLookup;
import tn.higer.sav_backend.dto.TechnicianDtos.TechnicianResponse;
import tn.higer.sav_backend.model.*;
import tn.higer.sav_backend.repository.*;
import tn.higer.sav_backend.security.Access;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * La machine à états du dépannage est le contrôle voulu par le chef d'atelier :
 * elle est donc testée ici, pas seulement à l'écran. Chaque refus attendu est
 * vérifié avec sa raison en français, parce qu'un refus muet n'est pas compris
 * sur un poste de travail.
 */
@ExtendWith(MockitoExtension.class)
class InterventionServiceTest {

    @Mock private InterventionRepository interventions;
    @Mock private InterventionEventRepository events;
    @Mock private TechnicianRepository technicians;
    @Mock private ServiceVehicleRepository serviceVehicles;
    @Mock private VehicleRepository vehicles;
    @Mock private ClientRepository clients;
    @Mock private SavCaseRepository savCases;
    @Mock private SavCaseService savCaseService;
    @Mock private TechnicianDirectory directory;
    @Mock private InterventionPhotoRepository photos;
    @Mock private AuditService audit;
    @Mock private VehicleMileageService compteur;
    /** Le forfait que la sortie vient honorer : ses propres règles ont leur fichier de tests. */
    @Mock private SoldPlanService forfaits;
    @Mock private SoldPlanAlertService rappels;
    /** La demande qui a fait sortir la voiture, relue dans le sens inverse. */
    @Mock private MissionRepository demandes;
    /** Les pièces demandées dehors : la clôture d'une sortie de pose les marque « Posées ». */
    @Mock private InterventionPartRequestRepository pieces;
    /** La fiche atelier : écrite au départ, cochée par l'homme. */
    @Mock private InterventionChecklistItemRepository checklist;

    private static final String TECH_ID = "tch-1";
    private static final UUID TECH_USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String BUS_ID = "veh-1";
    private static final String SV_ID = "sv-1";

    private InterventionService service() {
        return new InterventionService(interventions, events, technicians, serviceVehicles,
                vehicles, clients, savCases, savCaseService, directory, photos, audit, compteur,
                forfaits, rappels, demandes, pieces, checklist, 8);
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt(UUID userId, String email) {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject(userId.toString())
                .claim("email", email).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private Technician technician() {
        Technician t = new Technician();
        t.setId(TECH_ID);
        t.setName("Ali Trabelsi");
        t.setUserId(TECH_USER);
        t.setActive(true);
        return t;
    }

    private Vehicle bus() {
        Vehicle v = new Vehicle();
        v.setId(BUS_ID);
        v.setVin("VF631E132LD002323");
        v.setClientId("cli-1");
        return v;
    }

    private ServiceVehicle truck() {
        ServiceVehicle v = new ServiceVehicle();
        v.setId(SV_ID);
        v.setRegistration("12345678");
        v.setKind("Dépanneuse");
        v.setLabel("Dépanneuse 1");
        v.setStatus(ServiceVehicleStatus.AVAILABLE);
        v.setActive(true);
        return v;
    }

    /** Un client déclaré : la sortie née du dossier reprend son adresse et sa ville. */
    private Client client() {
        Client c = new Client();
        c.setId("cli-1");
        c.setName("Société des transports du Sahel");
        c.setCity("Sousse");
        c.setAddress("Nouvelle Zone Industrielle, Kalaébira");
        return c;
    }

    /** Le dossier appelable : un bus, un client, une panne écrite, un homme déjà dehors. */
    private SavCase dossierAvecBus() {
        SavCase c = savCase("sav-9", "SAV-REC-2026-004");
        c.setVehicleId(BUS_ID);
        c.setClientId("cli-1");
        c.setTitle("Panne climatisation");
        c.setDescription("Le bus est appelable depuis mardi, la clim ne fonctionne plus.");
        c.setTechnicianUserId(TECH_USER);
        return c;
    }

    private static SavCase savCase(String id, String number) {
        SavCase c = new SavCase();
        c.setId(id);
        c.setCaseNumber(number);
        return c;
    }

    /**
     * Les deux clichés de preuve sont versés. Les tests de rattachement au dossier
     * SAV appellent ceci pour tester la liaison, sans se faire arrêt par la porte
     * photo — la porte est testée pour elle-même plus bas.
     */
    private void proofsPresent() {
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.AFTER)).thenReturn(true);
    }

    /** Les deux photos de la voiture, versées avant le pointage du départ ou du retour. */
    private void photosDeLaVoiture() {
        lenient().when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.DEPART)).thenReturn(true);
        lenient().when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.COMPTEUR_DEPART)).thenReturn(true);
        lenient().when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.RETOUR)).thenReturn(true);
        lenient().when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.COMPTEUR_RETOUR)).thenReturn(true);
    }

    private Intervention at(InterventionStatus status) {
        Intervention i = new Intervention();
        i.setId("itv-1");
        i.setNumber("DP-2026-001");
        i.setStatus(status);
        i.setVehicleId(BUS_ID);
        i.setClientId("cli-1");
        i.setTechnicianId(TECH_ID);
        i.setReportedAt(LocalDateTime.now().minusHours(2));
        i.setCreatedAt(LocalDateTime.now().minusHours(2));
        return i;
    }

    private InterventionRequest createRequest() {
        return new InterventionRequest(BUS_ID, TECH_USER, null, null, "Responsable atelier",
                "Zone industrielle", "Sousse", "Bus en panne de freins", SavPriority.HIGH, null, null);
    }

    /** Une pièce demandée dehors sur la sortie {@code itv-1}, avec ou sans dossier au dos. */
    private static InterventionPartRequest piece(String id, String savCaseId) {
        InterventionPartRequest p = new InterventionPartRequest();
        p.setId(id);
        p.setInterventionId("itv-1");
        p.setSavCaseId(savCaseId);
        p.setDesignation("Alternateur 28V 120A");
        p.setReference("37300-10300");
        p.setQuantityOrdered(1);
        p.setState(PartRequestState.LIVREE);
        p.setCreatedAt(LocalDateTime.now().minusHours(1));
        return p;
    }

    /** Le chef choisit un compte : le serveur en sort la fiche de paie, existante ou neuve. */
    private Technician ficheFromAccount() {
        Technician tech = technician();
        when(directory.ficheFor(eq(TECH_USER), any())).thenReturn(tech);
        return tech;
    }

    // -------------------------------------------------------------------- création

    @Test
    void leNumeroEstDonneParLeServeurEtJamaisSaisi() {
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        ficheFromAccount();
        when(interventions.findNumbersByPrefix(anyString())).thenReturn(List.of());
        when(clients.findById("cli-1")).thenReturn(Optional.empty());

        InterventionResponse response = service().create(createRequest(),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertNotNull(response.number());
        assertTrue(response.number().matches("DP-\\d{4}-001"), "numéro obtenu : " + response.number());
        assertEquals("ali trabelsi", response.technicianName().toLowerCase());
        verify(interventions).save(any(Intervention.class));
    }

    @Test
    void unTechnicienDejaDehorsRefuseUneSecondeSortie() {
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        ficheFromAccount();
        when(interventions.existsByTechnicianIdAndStatusIn(eq(TECH_ID), anyList())).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(createRequest(), jwt(UUID.randomUUID(), "chef@saphir.tn"),
                        auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("déjà une sortie en cours"), e.getReason());
        verify(interventions, never()).save(any());
    }

    @Test
    void uneSortiePeutNaîtreSansHommeDésigné() {
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(interventions.findNumbersByPrefix(anyString())).thenReturn(List.of());

        service().create(new InterventionRequest(BUS_ID, null, null, null, "Standard",
                        "Zone industrielle", "Sousse", "Bus bloqué au dépôt", null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        ArgumentCaptor<Intervention> saved = ArgumentCaptor.forClass(Intervention.class);
        verify(interventions).save(saved.capture());
        assertNull(saved.getValue().getTechnicianId(),
                "la sortie existe avant qu'on sache qui y va : c'est l'affectation qui nomme");
        assertEquals(InterventionStatus.REPORTED, saved.getValue().getStatus());
        assertEquals(InterventionPurpose.PANNE, saved.getValue().getPurpose(),
                "une sortie déclarée par l'atelier est une panne : le motif entretien vient du rappel");
        verify(directory, never()).ficheFor(any(), any());
    }

    @Test
    void unTechnicienNouvrePasUneSortie() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(createRequest(), jwt(TECH_USER, "a@saphir.tn"),
                        auth("TECHNICIEN")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("chef d'atelier"), e.getReason());
    }

    @Test
    void laConsultationNentrePasDansLeModule() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().list(null, null, auth("CONSULTATION"),
                        jwt(UUID.randomUUID(), "client@higer.tn")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("interne"), e.getReason());
        verify(interventions, never()).findAll();
    }

    // --------------------------------------------------------------------- étapes

    @Test
    void lAffectationRefuseUneVoitureDejaEngagee() {
        Intervention i = at(InterventionStatus.REPORTED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        ServiceVehicle engaged = truck();
        engaged.setStatus(ServiceVehicleStatus.DISPATCHED);
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(engaged));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().dispatch("itv-1", new DispatchRequest(SV_ID, null, TECH_USER, List.of()),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("pas disponible"), e.getReason());
    }

    @Test
    void lAffectationReprendLHommeDéjàPortéParLaSortie() {
        Intervention i = at(InterventionStatus.REPORTED);
        ServiceVehicle t = truck();
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(t));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));

        InterventionResponse response = service().dispatch("itv-1",
                new DispatchRequest(SV_ID, null, null, List.of()),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("dispatched", response.status());
        assertEquals("ali trabelsi", response.technicianName().toLowerCase());
        assertEquals(ServiceVehicleStatus.DISPATCHED, t.getStatus(),
                "la voiture engagée cesse d'être proposable dès l'affectation");
        verify(directory, never()).ficheFor(any(), any());
    }

    @Test
    void lAffectationRefuseUnHommeDéjàDehorsSurUneAutreSortie() {
        Intervention i = at(InterventionStatus.REPORTED);
        i.setTechnicianId(null);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(truck()));
        when(directory.ficheFor(eq(TECH_USER), any())).thenReturn(technician());
        Intervention autre = at(InterventionStatus.ON_SITE);
        autre.setId("itv-9");
        when(interventions.findByTechnicianIdAndStatusInOrderByReportedAtDesc(eq(TECH_ID), anyList()))
                .thenReturn(List.of(autre));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().dispatch("itv-1", new DispatchRequest(SV_ID, null, TECH_USER, List.of()),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("déjà dehors"), e.getReason());
        verify(interventions, never()).save(any());
    }

    @Test
    void lAffectationNeProposeQueLesHommesQuiNeSontPasDehors() {
        Intervention sortie = at(InterventionStatus.ON_SITE);
        when(interventions.findByStatusInOrderByReportedAtDesc(anyList())).thenReturn(List.of(sortie));
        when(directory.options()).thenReturn(List.of(
                new TechnicianLookup(TECH_USER.toString(), "Ali Trabelsi", "a@saphir.tn", TECH_ID),
                new TechnicianLookup("9f0e0418-0000-0000-0000-0000000000b2", "Mohammed Ben Salah",
                        "m@saphir.tn", "tch-2")));

        CrewResponse crew = service().crew(auth("CHEF_ATELIER"));

        assertEquals(1, crew.free().size(), "un seul homme encore envoyable");
        assertEquals("Mohammed Ben Salah", crew.free().get(0).name());
        assertEquals(1, crew.out().size(), "Ali est dehors : il ne se propose pas");
        assertEquals("DP-2026-001", crew.out().get(0).sortieNumber());
        assertEquals("on_site", crew.out().get(0).sortieStatus());
    }

    @Test
    void leDepartEstRefuseAvantLAffectationDuVehicule() {
        Intervention i = at(InterventionStatus.REPORTED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().depart("itv-1", new DepartRequest(12000L),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("statut « reported »"), e.getReason());
    }

    @Test
    void leCompteurDeDepartInferieurAuDernierReleveEstRefuse() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        ServiceVehicle v = truck();
        v.setCurrentMileage(50000L);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(v));
        photosDeLaVoiture();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().depart("itv-1", new DepartRequest(49000L),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("inférieur au dernier relevé"), e.getReason());
    }

    @Test
    void lArriveeEstRefuseeSansDepartEnregistre() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().arrive("itv-1", new ArrivalRequest(null, null),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("enregistrer le départ"), e.getReason());
    }

    @Test
    void lIssueDInterventionEstRepareeOuRemorque() {
        Intervention i = at(InterventionStatus.IN_PROGRESS);
        i.setArrivedAt(LocalDateTime.now().minusMinutes(40));
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().finish("itv-1", new FinishRequest("fixed", null, null),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("repaired"), e.getReason());
    }

    /**
     * Le compteur du bus à la fin entre dans la même étape que le résultat : c'est le
     * seul moment où l'homme est encore devant le bus pour le lire.
     */
    @Test
    void laFinRelitLeCompteurDuBus() {
        Intervention i = at(InterventionStatus.IN_PROGRESS);
        i.setArrivedAt(LocalDateTime.now().minusMinutes(40));
        i.setBusMileage(98_400L);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));

        InterventionResponse response = service().finish("itv-1",
                new FinishRequest("repaired", "Contacteur remplacé", 98_412L),
                jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN"));

        assertEquals(Long.valueOf(98_412L), response.mileages().busMileageEnd());
        assertEquals(Long.valueOf(98_400L), response.mileages().busMileage());
    }

    /**
     * Phase G : la sortie ne garde pas le compteur pour elle. La valeur relue à la fin
     * est portée à la porte unique, qui l'aligne sur la fiche du bus — sinon l'écran
     * Véhicules et le rappel des paliers continuent de parler de deux chiffres.
     */
    @Test
    void laFinDuneSortiePasseLeCompteurALaPorteDuBus() {
        Intervention i = at(InterventionStatus.IN_PROGRESS);
        i.setArrivedAt(LocalDateTime.now().minusMinutes(40));
        i.setBusMileage(98_400L);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));

        service().finish("itv-1", new FinishRequest("repaired", "Contacteur remplacé", 98_412L),
                jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN"));

        verify(compteur).releveTechnicien(BUS_ID, 98_412L, "Ali Trabelsi");
    }

    @Test
    void leDepartSansLesPhotosDeLaVoitureEstRefuse() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(truck()));
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.DEPART)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.COMPTEUR_DEPART)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().depart("itv-1", new DepartRequest(null),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("deux photos"), e.getReason());
        assertNull(i.getDepartedAt());
        verify(interventions, never()).save(i);
    }

    @Test
    void leDepartSansChiffreEstAccepteAvecLesPhotos() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(truck()));
        photosDeLaVoiture();

        InterventionResponse response = service().depart("itv-1", new DepartRequest(null),
                jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN"));

        assertEquals("dispatched", response.status());
        assertNotNull(i.getDepartedAt());
        assertNull(response.mileages().startMileage());
        assertNull(response.mileages().distanceKm());
    }

    @Test
    void lAffectationEcritLaFicheAtelierDansLOrdre() {
        Intervention i = at(InterventionStatus.REPORTED);
        i.setTechnicianId(TECH_ID);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(truck()));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));

        service().dispatch("itv-1",
                new DispatchRequest(SV_ID, null, null,
                        List.of("Contrôler la batterie", " ", "Essayer le bus")),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        ArgumentCaptor<InterventionChecklistItem> captor =
                ArgumentCaptor.forClass(InterventionChecklistItem.class);
        verify(checklist, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals("Contrôler la batterie", captor.getAllValues().get(0).getLabel());
        assertEquals(1, captor.getAllValues().get(0).getPosition());
        assertEquals("Essayer le bus", captor.getAllValues().get(1).getLabel());
        assertEquals(2, captor.getAllValues().get(1).getPosition());
    }

    @Test
    void une_ligne_de_fiche_se_coche_et_la_fiche_incomplete_se_voit() {
        Intervention i = at(InterventionStatus.ON_SITE);
        InterventionChecklistItem item = new InterventionChecklistItem();
        item.setId("ick-1");
        item.setInterventionId("itv-1");
        item.setPosition(1);
        item.setLabel("Contrôler la batterie");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        when(checklist.findByInterventionIdOrderByPositionAsc("itv-1")).thenReturn(List.of(item));

        InterventionResponse response = service().cocher("itv-1", "ick-1",
                new CocherRequest(true), jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN"));

        assertTrue(item.isDone());
        assertNotNull(item.getDoneAt());
        assertFalse(response.checklistIncomplete());
    }

    @Test
    void leCompteurDuBusNePeutPasReculer() {        Intervention i = at(InterventionStatus.IN_PROGRESS);
        i.setArrivedAt(LocalDateTime.now().minusMinutes(40));
        i.setBusMileage(98_400L);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().finish("itv-1", new FinishRequest("repaired", null, 98_300L),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertTrue(e.getReason().contains("plus petit"), e.getReason());
        assertEquals(InterventionStatus.IN_PROGRESS, i.getStatus(),
                "le refus ne doit pas avancer la sortie");
    }

    @Test
    void leRetourRendLaVoitureALAtelierEtNoteLeCompteur() {
        Intervention i = at(InterventionStatus.REPAIRED);
        i.setServiceVehicleId(SV_ID);
        i.setDepartedAt(LocalDateTime.now().minusHours(3));
        i.setArrivedAt(LocalDateTime.now().minusHours(2));
        i.setFinishedAt(LocalDateTime.now().minusHours(1));
        i.setLeftSiteAt(LocalDateTime.now().minusMinutes(30));
        i.setStartMileage(50000L);
        i.setDefectCode("P0087");
        i.setCausingPart("filtre à gasoil");
        i.setDiagnosis("Filtre changé, purge faite, essai concluant.");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        ServiceVehicle v = truck();
        v.setStatus(ServiceVehicleStatus.DISPATCHED);
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(v));
        photosDeLaVoiture();

        InterventionResponse response = service().returnToWorkshop("itv-1",
                new ReturnRequest(50087L, "Khaled", null),
                jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN"));

        assertEquals("returned", response.status());
        assertEquals(Long.valueOf(87L), response.mileages().distanceKm());
        assertEquals(ServiceVehicleStatus.AVAILABLE, v.getStatus());
        assertEquals(Long.valueOf(50087L), v.getCurrentMileage());
        verify(serviceVehicles).save(v);
    }

    @Test
    void leRetourAttendLeConstatEcritDevantLeBus() {
        Intervention i = at(InterventionStatus.REPAIRED);
        i.setLeftSiteAt(LocalDateTime.now().minusMinutes(30));
        i.setDefectCode("P0087");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));
        photosDeLaVoiture();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().returnToWorkshop("itv-1",
                        new ReturnRequest(50087L, "Khaled", null),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("attend le constat écrit"), e.getReason());
        assertTrue(e.getReason().contains("la pièce en cause"), e.getReason());
        assertTrue(e.getReason().contains("ce que vous avez constaté"), e.getReason());
        assertEquals(InterventionStatus.REPAIRED, i.getStatus(),
                "le refus ne doit pas avancer la sortie");
    }

    // ------------------------------------------------- rentrée constatée sans pointage

    @Test
    void laRentreeSansPointageRendLaVoitureEtLaisseLesHeuresVides() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        i.setDepartedAt(LocalDateTime.now().minusDays(14));
        i.setStartMileage(50000L);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        ServiceVehicle v = truck();
        v.setStatus(ServiceVehicleStatus.DISPATCHED);
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(v));

        InterventionResponse response = service().declareReturn("itv-1",
                new DeclareReturnRequest("Sortie d'essai jamais pointée par le technicien"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("returned", response.status());
        assertNotNull(i.getReturnedAt());
        // Rien de ce que l'homme n'a pas fait n'est inventé : le registre montre les trous.
        assertNull(i.getArrivedAt());
        assertNull(i.getFinishedAt());
        assertNull(i.getLeftSiteAt());
        assertNull(i.getEndMileage());
        assertEquals(ServiceVehicleStatus.AVAILABLE, v.getStatus());
        verify(serviceVehicles).save(v);

        ArgumentCaptor<InterventionEvent> journal = ArgumentCaptor.forClass(InterventionEvent.class);
        verify(events).save(journal.capture());
        assertEquals(InterventionEventType.RETURNED, journal.getValue().getEventType());
        assertEquals(InterventionEventSource.MANAGED, journal.getValue().getSource());
        assertTrue(journal.getValue().getNote().contains("sans pointage"),
                journal.getValue().getNote());
        assertTrue(journal.getValue().getNote().contains("Sortie d'essai jamais pointée"),
                "la raison écrite reste au journal");
    }

    @Test
    void laRentreeSansPointageExigeUneRaison() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setDepartedAt(LocalDateTime.now().minusDays(3));
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().declareReturn("itv-1", new DeclareReturnRequest("   "),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("raison reste au journal"), e.getReason());
        assertEquals(InterventionStatus.DISPATCHED, i.getStatus(),
                "le refus ne doit pas avancer la sortie");
        verify(interventions, never()).save(any());
    }

    @Test
    void laRentreeSansPointageNeRendPasUneVoitureALAtelierOuEnPanne() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        i.setDepartedAt(LocalDateTime.now().minusDays(3));
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        ServiceVehicle v = truck();
        v.setStatus(ServiceVehicleStatus.AT_WORKSHOP);
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(v));

        service().declareReturn("itv-1", new DeclareReturnRequest("Rentrée constatée"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals(ServiceVehicleStatus.AT_WORKSHOP, v.getStatus(),
                "la voiture reste indisponible pour sa propre raison");
        verify(serviceVehicles, never()).save(any());
    }

    @Test
    void onNeConstatePasLaRentreeDUneSortieJamaisPartie() {
        Intervention i = at(InterventionStatus.REPORTED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().declareReturn("itv-1", new DeclareReturnRequest("Rentrée constatée"),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("jamais partie"), e.getReason());
        verify(interventions, never()).save(any());
    }

    @Test
    void laRentreeSansPointageEstUnGesteDuChefDAtelier() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setDepartedAt(LocalDateTime.now().minusDays(3));
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findByUserId(TECH_USER)).thenReturn(Optional.of(technician()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().declareReturn("itv-1", new DeclareReturnRequest("Rentrée constatée"),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("chef d'atelier"), e.getReason());
        verify(interventions, never()).save(any());
    }

    @Test
    void onNeCloturePasUneSortieQuiNEstPasRentrée() {
        Intervention i = at(InterventionStatus.REPAIRED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().close("itv-1", new ClosureRequest("ok", null, null, null, null),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("« repaired »"), e.getReason());
    }

    // ------------------------------------------------------------- lien avec le dossier SAV

    @Test
    void onNeRattachePasUnDossierInexistant() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        proofsPresent();
        when(savCases.findById("sav-faux")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().close("itv-1", new ClosureRequest("ok", "sav-faux", null, null, null),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("introuvable"), e.getReason());
        verify(interventions, never()).save(any());
    }

    @Test
    void onNePeutPasChoisirLesDeuxDossierEnMemeTemps() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        proofsPresent();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().close("itv-1", new ClosureRequest("ok", "sav-1", true, null, null),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("Il faut choisir"), e.getReason());
    }

    @Test
    void cloturerAvecUnDossierExistantLeRattacheEtLeRenvoie() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        SavCase case_ = savCase("sav-1", "SAV-WAR-2026-004");
        when(savCases.findById("sav-1")).thenReturn(Optional.of(case_));

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest("Pris en charge garantie", "sav-1", null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("sav-1", response.savCaseId());
        assertEquals("SAV-WAR-2026-004", response.savCaseNumber());
        // Le dossier rattaché avance avec la sortie : il passe « Clôturé » ici, pas à la main.
        verify(savCaseService).suivreSortie(eq(i), any());
    }

    /**
     * La sortie porte déjà son dossier : personne ne l'a re-choisi au moment de clôturer,
     * et le dossier avance quand même. C'est le geste réel de l'atelier — l'homme rentre,
     * il clôture sa sortie — et sans cette écriture le dossier restait « Rentré » après
     * une sortie fermée, donc un travail terminé qui se croit encore en cours.
     */
    @Test
    void cloturerUneSortieDejaRattacheeFermeLeDossier() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setSavCaseId("sav-1");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        when(savCases.findById("sav-1"))
                .thenReturn(Optional.of(savCase("sav-1", "SAV-WAR-2026-004")));

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest("Bus rendu", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("SAV-WAR-2026-004", response.savCaseNumber());
        verify(savCaseService).suivreSortie(eq(i), any());
    }

    @Test
    void cloturerPeutOuvrirLeDossierDepuisLaSortie() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setDefectCode("P0450");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        Technician tech = technician();
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(tech));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        SavCase created = savCase("sav-new", "SAV-REC-2026-001");
        when(savCaseService.createFromIntervention(eq(i), eq(tech),
                eq(SavCaseType.RECLAMATIONS), any())).thenReturn(created);
        when(savCases.findById("sav-new")).thenReturn(Optional.of(created));

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest(null, null, true, SavCaseType.RECLAMATIONS, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("sav-new", response.savCaseId());
        assertEquals("SAV-REC-2026-001", response.savCaseNumber());
        // Un dossier qui naît de ce geste reste « Ouvert » : il ne se clôt pas tout seul
        // à la seconde de sa création, c'est l'atelier qui le ferme après l'avoir relu.
        verify(savCaseService, never()).suivreSortie(any(), any());
    }

    @Test
    void cloturerSansDossierResteUneSortieSeule() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest("Rentrant", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertNull(response.savCaseId());
        assertNull(response.savCaseNumber());
        assertEquals("panne", response.purpose());
        // Aucun appel de numérotation : « pas de dossier » n'est pas « dossier à créer ».
        verifyNoInteractions(savCaseService);
    }

    // ---------------------------------------------------- la marchandise du dossier

    /**
     * Une pièce vue devant le bus n'est pas encore la marchandise du dossier : tant
     * que sa ligne ne porte aucun {@code sav_case_id}, elle ne figure dans aucun papier
     * AG, et le constructeur ne rembourse que des pièces nommées par un appel. Le
     * rattachement se fait au même moment que la clôture de la sortie qui l'a vue —
     * même moment, même transaction, pas un clic de plus à l'atelier.
     */
    @Test
    void laClotureReclameAuDossierLesPiecesVuesDevantLeBus() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        SavCase claim = savCase("sav-1", "SAV-WAR-2026-004");
        claim.setType(SavCaseType.GARANTIES);
        when(savCases.findById("sav-1")).thenReturn(Optional.of(claim));
        InterventionPartRequest vueDehors = piece("ipr-1", null);
        when(pieces.findByInterventionIdOrderByCreatedAtAsc("itv-1")).thenReturn(List.of(vueDehors));

        service().close("itv-1", new ClosureRequest("Pris en charge garantie", "sav-1", null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        ArgumentCaptor<List<InterventionPartRequest>> rattachees = ArgumentCaptor.forClass(List.class);
        verify(pieces).saveAll(rattachees.capture());
        assertEquals(1, rattachees.getValue().size());
        assertEquals("sav-1", rattachees.getValue().get(0).getSavCaseId());
        assertSame(vueDehors, rattachees.getValue().get(0),
                "c'est la ligne qui court, pas une copie : une écriture perdue ne se retrouve plus");
    }

    /**
     * Deux dossiers ne peuvent pas réclamer la même pièce. Une ligne déjà portée par
     * un dossier n'est pas déplacée vers celui qui clôture maintenant, même si c'est
     * elle qui est sortie du même bus : celle qui l'a demandée dehors a raison contre
     * celle qui vient après, et l'écran du premier dossier perdrait une marchandise
     * qu'il a déjà engagée chez le constructeur.
     */
    @Test
    void unePieceDejaReclameeParUnAutreDossierNeChangePasDeDossier() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        when(savCases.findById("sav-1")).thenReturn(Optional.of(savCase("sav-1", "SAV-WAR-2026-004")));
        when(pieces.findByInterventionIdOrderByCreatedAtAsc("itv-1"))
                .thenReturn(List.of(piece("ipr-1", "sav-7")));

        service().close("itv-1", new ClosureRequest("ok", "sav-1", null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        verify(pieces, never()).saveAll(anyList());
    }

    /**
     * Une sortie née d'un rappel de forfait n'a pas de réclamation au dos : il n'y a
     * rien à réclamer au constructeur, et le portail n'invente pas un dossier pour
     * habiller la pièce.
     */
    @Test
    void uneSortieSansDossierNeReclameRien() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();

        service().close("itv-1", new ClosureRequest("Rentrant", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        verify(pieces, never()).findByInterventionIdOrderByCreatedAtAsc(anyString());
    }

    /**
     * Le dossier rattaché n'existe plus ({@code DELETE /api/sav-cases} ne nettoie aucun
     * pointeur) : la pièce reste où elle est et la sortie se clôt. Refuser la clôture
     * parce qu'un lien est cassé punirait l'homme qui rentre d'un papier qu'un autre a
     * supprimé.
     */
    @Test
    void laClotureRestePossibleQuandLeDossierRattacheASupprime() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setSavCaseId("sav-fantome");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        when(savCases.findById("sav-fantome")).thenReturn(Optional.empty());

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest("ok", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("closed", response.status());
        verify(pieces, never()).saveAll(anyList());
    }

    // ------------------------------------------------------------------- preuves photo

    @Test
    void cloturerSansPhotosEstRefuseSansRaisonEcrite() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.BEFORE)).thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().close("itv-1", new ClosureRequest("ok", null, null, null, null),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        // Le refus nomme ce qui manque : « photos » seul ne dit pas à l'atelier quoi verser.
        assertTrue(e.getReason().contains("avant intervention"), e.getReason());
        assertTrue(e.getReason().contains("après intervention"), e.getReason());
        verify(interventions, never()).save(any());
    }

    @Test
    void cloturerSansPhotosAvecUneRaisonRestePossibleEtTrace() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.BEFORE)).thenReturn(false);
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.AFTER)).thenReturn(true);

        InterventionResponse response = service().close("itv-1", new ClosureRequest("ok", null, null, null,
                "équipe partie sans téléphone, cliché relevé au poste demain"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals("closed", response.status());
        ArgumentCaptor<InterventionEvent> journal = ArgumentCaptor.forClass(InterventionEvent.class);
        verify(events, atLeastOnce()).save(journal.capture());
        InterventionEvent gap = journal.getAllValues().stream()
                .filter(e -> e.getEventType() == InterventionEventType.PHOTO).findFirst().orElseThrow();
        assertTrue(gap.getNote().contains("équipe partie sans téléphone"), gap.getNote());
        assertTrue(gap.getNote().contains("avant intervention"), gap.getNote());
    }

    /**
     * Le second passage d'une pièce : la sortie de pose rentre, se clôture, et le
     * serveur écrit « Posée » sur la ligne que la livraison avait rattachée à cette
     * mission. Personne ne tape ce mot — et une sortie qui n'est pas née de la mission
     * de pose ne marque rien, sinon l'état redeviendrait une supposition.
     */
    @Test
    void laClotureDeLaSortieDePoseEcritPosee() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.AFTER)).thenReturn(true);

        Mission pose = new Mission();
        pose.setId("mss-2");
        pose.setNumber("MT-2026-021");
        pose.setInterventionId("itv-1");
        when(demandes.findByInterventionId("itv-1")).thenReturn(Optional.of(pose));

        InterventionPartRequest compresseur = new InterventionPartRequest();
        compresseur.setId("ipr-1");
        compresseur.setInterventionId("itv-9");
        compresseur.setDesignation("Compresseur de climatisation");
        compresseur.setQuantityOrdered(1);
        compresseur.setState(PartRequestState.LIVREE);
        compresseur.setFittingMissionId("mss-2");
        when(pieces.findByFittingMissionId("mss-2")).thenReturn(List.of(compresseur));

        service().close("itv-1", new ClosureRequest("Bus rendu au client", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals(PartRequestState.POSEE, compresseur.getState());
        assertNotNull(compresseur.getFittedAt());
        assertEquals("itv-1", compresseur.getFittedInterventionId());
        assertEquals("chef@saphir.tn", compresseur.getFittedBy(), "le nom qui écrit « Posée »");
        verify(pieces).save(compresseur);
        ArgumentCaptor<InterventionEvent> journal = ArgumentCaptor.forClass(InterventionEvent.class);
        verify(events, atLeastOnce()).save(journal.capture());
        assertTrue(journal.getAllValues().stream().anyMatch(e ->
                        e.getEventType() == InterventionEventType.PART
                                && e.getNote().contains("Pièce montée pendant cette sortie")),
                journal.getAllValues().toString());
    }

    /** Une commande à moitié arrivée ne se monte pas : « Posée » ne s'écrit que sur du « Livrée ». */
    @Test
    void unePiecePartielleNeSePosePas() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.AFTER)).thenReturn(true);

        Mission pose = new Mission();
        pose.setId("mss-2");
        pose.setNumber("MT-2026-021");
        pose.setInterventionId("itv-1");
        when(demandes.findByInterventionId("itv-1")).thenReturn(Optional.of(pose));

        InterventionPartRequest moitie = new InterventionPartRequest();
        moitie.setId("ipr-2");
        moitie.setDesignation("Pompe à eau");
        moitie.setQuantityOrdered(2);
        moitie.setState(PartRequestState.PARTIELLE);
        moitie.setFittingMissionId("mss-2");
        when(pieces.findByFittingMissionId("mss-2")).thenReturn(List.of(moitie));

        service().close("itv-1", new ClosureRequest("Bus rendu au client", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals(PartRequestState.PARTIELLE, moitie.getState());
        assertNull(moitie.getFittedAt());
        verify(pieces, never()).save(any());
    }

    /** Une sortie qui n'est pas la sortie de pose ne touche aucune ligne de pièce. */
    @Test
    void uneSortieSansMissionDePoseNeMarqueRien() {
        Intervention i = at(InterventionStatus.RETURNED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.BEFORE)).thenReturn(true);
        when(photos.existsByInterventionIdAndAngle("itv-1", PhotoAngle.AFTER)).thenReturn(true);
        when(demandes.findByInterventionId("itv-1")).thenReturn(Optional.empty());

        service().close("itv-1", new ClosureRequest("Bus rendu au client", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        verify(pieces, never()).findByFittingMissionId(anyString());
        verify(pieces, never()).save(any());
    }

    // --------------------------------------------------------------- propriété et rectification
    @Test
    void laSortieDUnAutreRepondIntrouvableEtNonDefense() {
        Intervention other = at(InterventionStatus.DISPATCHED);
        other.setTechnicianId("tch-999");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(other));
        when(technicians.findByUserId(any())).thenReturn(Optional.of(technician()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().detail("itv-1", auth("TECHNICIEN"),
                        jwt(TECH_USER, "a@saphir.tn")));
        // 404 et non 403 : on ne confirme pas à un technicien que la sortie de son collègue existe.
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void laRectificationAjouteUneTraceSansEffacerLAncienneValeur() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setDepartedAt(LocalDateTime.now().minusHours(4));
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));

        LocalDateTime nouvelleHeure = LocalDateTime.now().minusHours(3);
        service().correct("itv-1", new CorrectionRequest("departedAt", nouvelleHeure, null,
                "heure relevée sur le bon papier"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertEquals(nouvelleHeure, i.getDepartedAt());
        ArgumentCaptor<InterventionEvent> journal = ArgumentCaptor.forClass(InterventionEvent.class);
        verify(events).save(journal.capture());
        assertEquals(InterventionEventType.CORRECTED, journal.getValue().getEventType());
        assertEquals(InterventionEventSource.MANAGED, journal.getValue().getSource());
        assertTrue(journal.getValue().getNote().contains("motif : heure relevée sur le bon papier"),
                journal.getValue().getNote());
        assertTrue(journal.getValue().getNote().contains("→"), journal.getValue().getNote());
    }

    @Test
    void uneSortieClotureeNeSeModifiePlus() {
        Intervention i = at(InterventionStatus.CLOSED);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("itv-1", createRequest(),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));
        assertTrue(e.getReason().contains("clôturée"), e.getReason());
    }

    @Test
    void laPaieNeSortPasPourUnTechnicien() {
        Technician t = technician();
        t.setHourlyRate(new BigDecimal("12.500"));

        assertNull(TechnicianResponse.from(t, Access.seesPayroll(auth("TECHNICIEN")), null).hourlyRate());
        assertNull(TechnicianResponse.from(t, Access.seesPayroll(auth("GESTIONNAIRE_SAV")), null).hourlyRate());
        assertEquals(new BigDecimal("12.500"),
                TechnicianResponse.from(t, Access.seesPayroll(auth("CHEF_ATELIER")), null).hourlyRate());
    }

    // ------------------------------------------------------------------- tableau du jour

    @Test
    void uneSortieQuiDureEstSignaleeSurLeTableau() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        i.setDepartedAt(LocalDateTime.now().minusHours(10));
        when(interventions.findByReportedAtGreaterThanEqualOrderByReportedAtDesc(any()))
                .thenReturn(new ArrayList<>(List.of(i)));
        when(technicians.findAllById(any())).thenReturn(List.of(technician()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(serviceVehicles.findAllById(any())).thenReturn(List.of(truck()));
        when(serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(anyList()))
                .thenReturn(List.of(truck()));

        DayBoardResponse board = service().dayBoard(auth("CHEF_ATELIER"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"));

        assertEquals(1, board.openCount());
        assertEquals(1, board.longRunningCount());
        assertEquals(1, board.vehiclesEngaged());
    }

    /**
     * Le volet « Sorties » doit dire de quelle demande la sortie vient, sinon le
     * chef voit un DP- sans savoir ce qu'il a tranché. La liaison est relue dans le
     * sens inverse — par `missions.intervention_id` — et non recopiée sur la sortie :
     * deux écritures du même lien, c'est deux vérités possibles.
     */
    @Test
    void laLigneDeLaSortiePorteLeNumeroDeLaDemandeQuiLaFaitSortir() {
        Intervention i = at(InterventionStatus.DISPATCHED);
        i.setServiceVehicleId(SV_ID);
        i.setDepartedAt(LocalDateTime.now().minusHours(1));
        when(interventions.findByReportedAtGreaterThanEqualOrderByReportedAtDesc(any()))
                .thenReturn(new ArrayList<>(List.of(i)));
        when(technicians.findAllById(any())).thenReturn(List.of(technician()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(serviceVehicles.findAllById(any())).thenReturn(List.of(truck()));
        when(serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(anyList()))
                .thenReturn(List.of(truck()));

        Mission demande = new Mission();
        demande.setId("mss-1");
        demande.setNumber("MT-2026-004");
        demande.setInterventionId("itv-1");
        demande.setDecidedAt(LocalDateTime.now().minusHours(2));
        when(demandes.findByInterventionIdIn(any())).thenReturn(List.of(demande));

        DayBoardResponse board = service().dayBoard(auth("CHEF_ATELIER"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"));

        assertEquals("MT-2026-004", board.sorties().get(0).missionNumber());
        assertNotNull(board.sorties().get(0).missionDecidedAt());
        assertEquals(1, board.enRouteCount(), "partie, pas encore arrivée : en route");
    }

    /**
     * Une sortie née d'un dossier (pas d'une demande) n'a pas de mission à montrer.
     * Le champ reste vide — l'écran n'invente pas un « MT- » pour remplir la case.
     */
    @Test
    void uneSortieSansDemandeNeMontrePasUnNumeroInvente() {
        Intervention i = at(InterventionStatus.ON_SITE);
        i.setDepartedAt(LocalDateTime.now().minusHours(3));
        i.setArrivedAt(LocalDateTime.now().minusHours(2));
        when(interventions.findByReportedAtGreaterThanEqualOrderByReportedAtDesc(any()))
                .thenReturn(new ArrayList<>(List.of(i)));
        when(technicians.findAllById(any())).thenReturn(List.of(technician()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(serviceVehicles.findAllById(any())).thenReturn(List.of());
        when(serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(anyList()))
                .thenReturn(List.of());
        when(demandes.findByInterventionIdIn(any())).thenReturn(List.of());

        DayBoardResponse board = service().dayBoard(auth("CHEF_ATELIER"),
                jwt(UUID.randomUUID(), "chef@saphir.tn"));

        assertNull(board.sorties().get(0).missionNumber());
        assertEquals(0, board.enRouteCount());
        assertEquals(1, board.onSiteCount());
    }

    @Test
    void leTableauDuTechnicienNeMontreQueSesSorties() {
        Intervention mine = at(InterventionStatus.DISPATCHED);
        Intervention dAutrui = at(InterventionStatus.ON_SITE);
        dAutrui.setId("itv-2");
        dAutrui.setNumber("DP-2026-002");
        dAutrui.setTechnicianId("tch-999");
        when(interventions.findByReportedAtGreaterThanEqualOrderByReportedAtDesc(any()))
                .thenReturn(new ArrayList<>(List.of(mine, dAutrui)));
        when(technicians.findByUserId(any())).thenReturn(Optional.of(technician()));
        when(technicians.findAllById(any())).thenReturn(List.of(technician()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(serviceVehicles.findAllById(any())).thenReturn(List.of());
        when(serviceVehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(anyList()))
                .thenReturn(List.of());

        DayBoardResponse board = service().dayBoard(auth("TECHNICIEN"),
                jwt(TECH_USER, "a@saphir.tn"));

        assertEquals(1, board.openCount());
        assertEquals(TECH_ID, board.sorties().get(0).technicianId());
    }

    // ---------------------------------------------- la sortie naît du dossier SAV

    @Test
    void laSortieNéeDUnDossierHériteDuBusDuClientEtDeLaPanne() {
        when(savCases.findById("sav-9")).thenReturn(Optional.of(dossierAvecBus()));
        when(interventions.findBySavCaseIdOrderByReportedAtAsc("sav-9")).thenReturn(List.of());
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(interventions.findNumbersByPrefix(anyString())).thenReturn(List.of());
        // L'homme du dossier est dehors sur une autre sortie : la sortie l'attend,
        // elle ne le vole pas à ce qu'il fait déjà.
        when(directory.ficheFor(eq(TECH_USER), any())).thenReturn(technician());
        when(interventions.existsByTechnicianIdAndStatusIn(eq(TECH_ID), anyList())).thenReturn(true);

        InterventionResponse response = service().createFromSavCase("sav-9",
                jwt(UUID.randomUUID(), "sav@saphir.tn"), auth("GESTIONNAIRE_SAV"));

        ArgumentCaptor<Intervention> saved = ArgumentCaptor.forClass(Intervention.class);
        verify(interventions).save(saved.capture());
        Intervention i = saved.getValue();
        assertEquals("sav-9", i.getSavCaseId());
        assertEquals(BUS_ID, i.getVehicleId(), "le bus du dossier, pas un bus retapé");
        assertEquals("cli-1", i.getClientId());
        assertEquals("Sousse", i.getLocationCity());
        assertTrue(i.getDescription().contains("Panne climatisation"), i.getDescription());
        assertTrue(i.getRequestedBy().contains("SAV-REC-2026-004"), i.getRequestedBy());
        assertNull(i.getTechnicianId(), "personne n'est encore affecté");
        assertEquals(SavPriority.MEDIUM, i.getPriority());
        assertTrue(response.number().startsWith("DP-"), response.number());
    }

    @Test
    void unDossierAyantDéjàUneSortieOuverteNeEnRedéclarePasUneSeconde() {
        when(savCases.findById("sav-9")).thenReturn(Optional.of(dossierAvecBus()));
        Intervention quiCourt = at(InterventionStatus.REPORTED);
        quiCourt.setNumber("DP-2026-007");
        when(interventions.findBySavCaseIdOrderByReportedAtAsc("sav-9")).thenReturn(List.of(quiCourt));

        InterventionResponse response = service().createFromSavCase("sav-9",
                jwt(UUID.randomUUID(), "sav@saphir.tn"), auth("ADMIN"));

        assertEquals("DP-2026-007", response.number(), "la demande suivante renvoie la sortie qui court");
        verify(interventions, never()).save(any());
    }

    @Test
    void unCompteDeTerrainNeSeDéclarePasUneSortieLuiMême() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().createFromSavCase("sav-9", jwt(TECH_USER, "a@saphir.tn"),
                        auth("TECHNICIEN")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("terrain"), e.getReason());
        verify(interventions, never()).save(any());
    }

    // ------------------------- le rappel d'un palier envoie une sortie d'entretien

    /** Ce que le rappel transmet à la sortie : le bus et le palier du forfait, pas un choix à l'écran. */
    private SoldPlanAlertService.EnvoiAutorise rappelAEnvoyer(String sortieDejaEnvoyee) {
        return new SoldPlanAlertService.EnvoiAutorise("spa-1", "sp-1", "mps-20",
                "révision des 12 mois", "Forfait urbain 26 WBE", BUS_ID, "cli-1",
                "Bus CS-1234-26 : 365 jours échus depuis le début du forfait", sortieDejaEnvoyee);
    }

    @Test
    void leRappelEnvoieUneSortieMarqueeEntretienEtAffecteLHommeEtLaVoiture() {
        when(rappels.rappelAEnvoyer("spa-1")).thenReturn(rappelAEnvoyer(null));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(interventions.findNumbersByPrefix(anyString())).thenReturn(List.of());
        when(interventions.findByVehicleIdAndStatusInOrderByReportedAtDesc(BUS_ID, InterventionStatus.OPEN))
                .thenReturn(List.of());
        // Le numéro de la sortie naît dans la méthode : la fiche relue par l'affectation
        // est donc celle que l'écriture a laissée.
        Intervention[] stock = new Intervention[1];
        when(interventions.save(any(Intervention.class))).thenAnswer(invocation -> {
            stock[0] = invocation.getArgument(0);
            return stock[0];
        });
        when(interventions.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(stock[0]));
        ServiceVehicle v = truck();
        when(serviceVehicles.findById(SV_ID)).thenReturn(Optional.of(v));
        when(directory.ficheFor(eq(TECH_USER), any())).thenReturn(technician());
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));

        InterventionResponse response = service().envoyerPourRappel("spa-1",
                new EnvoyerRequest(SV_ID, TECH_USER), jwt(UUID.randomUUID(), "chef@saphir.tn"),
                auth("CHEF_ATELIER"));

        assertEquals("entretien", response.purpose());
        assertEquals("dispatched", response.status());
        Intervention i = stock[0];
        assertEquals("sp-1", i.getSoldPlanId());
        assertEquals("mps-20", i.getPlanStepId());
        assertEquals(BUS_ID, i.getVehicleId());
        assertEquals("cli-1", i.getClientId());
        assertEquals("Sousse", i.getLocationCity());
        assertTrue(i.getRequestedBy().contains("révision des 12 mois"), i.getRequestedBy());
        assertEquals("Bus CS-1234-26 : 365 jours échus depuis le début du forfait", i.getDescription());
        assertNull(i.getSavCaseId(), "une visite de forfait ne s'invente pas un dossier SAV");
        assertEquals(ServiceVehicleStatus.DISPATCHED, v.getStatus());
        verify(rappels).priseEnCharge(eq("spa-1"), eq(i.getId()), eq(i.getNumber()), any());
    }

    @Test
    void unBusDejaDehorsNeRecoitPasUneSecondeVisite() {
        when(rappels.rappelAEnvoyer("spa-1")).thenReturn(rappelAEnvoyer(null));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        Intervention dehors = at(InterventionStatus.ON_SITE);
        dehors.setNumber("DP-2026-009");
        when(interventions.findByVehicleIdAndStatusInOrderByReportedAtDesc(BUS_ID, InterventionStatus.OPEN))
                .thenReturn(List.of(dehors));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().envoyerPourRappel("spa-1", new EnvoyerRequest(SV_ID, TECH_USER),
                        jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("DP-2026-009"), e.getReason());
        assertTrue(e.getReason().contains("deux sorties"), e.getReason());
        verify(interventions, never()).save(any());
        verify(rappels, never()).priseEnCharge(any(), any(), any(), any());
    }

    @Test
    void unSecondClicRenzoieLaSortieDejaEnvoyeeSansEnRecrerUne() {
        when(rappels.rappelAEnvoyer("spa-1")).thenReturn(rappelAEnvoyer("itv-9"));
        Intervention envoyee = at(InterventionStatus.DISPATCHED);
        envoyee.setPurpose(InterventionPurpose.ENTRETIEN);
        when(interventions.findById("itv-9")).thenReturn(Optional.of(envoyee));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));

        InterventionResponse response = service().envoyerPourRappel("spa-1",
                new EnvoyerRequest(SV_ID, TECH_USER), jwt(UUID.randomUUID(), "chef@saphir.tn"),
                auth("CHEF_ATELIER"));

        assertEquals("DP-2026-001", response.number());
        assertEquals("entretien", response.purpose());
        verify(interventions, never()).save(any());
        verify(rappels, never()).priseEnCharge(any(), any(), any(), any());
    }

    @Test
    void unCompteSansLaSortieNEndePasUneSurUnRappel() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().envoyerPourRappel("spa-1", new EnvoyerRequest(SV_ID, TECH_USER),
                        jwt(TECH_USER, "a@saphir.tn"), auth("TECHNICIEN")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("chef d'atelier"), e.getReason());
        verifyNoInteractions(rappels);
    }

    @Test
    void laClotureDuneSortieDEntretienEcritLePapierDuPalier() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setPurpose(InterventionPurpose.ENTRETIEN);
        i.setSoldPlanId("sp-1");
        i.setPlanStepId("mps-20");
        i.setBusMileageEnd(41230L);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();
        when(forfaits.papierPourPalier(any(), any())).thenReturn(new SoldPlanService.PapierDePalier(
                "maint-1", "OR-2026-001",
                "Palier « révision des 12 mois » coché par l'opération OR-2026-001 — incluse au forfait."));

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest("Visite faite", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        ArgumentCaptor<SoldPlanService.SortiePourPalier> transmis =
                ArgumentCaptor.forClass(SoldPlanService.SortiePourPalier.class);
        verify(forfaits).papierPourPalier(transmis.capture(), any());
        SoldPlanService.SortiePourPalier sortie = transmis.getValue();
        assertEquals("sp-1", sortie.soldPlanId());
        assertEquals("mps-20", sortie.planStepId());
        assertEquals(BUS_ID, sortie.vehicleId());
        assertEquals("DP-2026-001", sortie.sortieNumber());
        assertEquals("Ali Trabelsi", sortie.technicien(), "le papier porte l'homme qui y est allé");
        assertEquals(Long.valueOf(41230L), sortie.compteurBus(), "le compteur relu sur place prouve la borne");

        assertEquals("maint-1", response.maintenanceItemId());
        assertEquals("entretien", response.purpose());
        ArgumentCaptor<InterventionEvent> journal = ArgumentCaptor.forClass(InterventionEvent.class);
        verify(events, atLeastOnce()).save(journal.capture());
        InterventionEvent palier = journal.getAllValues().stream()
                .filter(e -> e.getEventType() == InterventionEventType.PALIER).findFirst().orElseThrow();
        assertTrue(palier.getNote().contains("OR-2026-001"), palier.getNote());
    }

    @Test
    void laClotureDunePanneNEcritAucunPapierDeForfait() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setPurpose(InterventionPurpose.PANNE);
        i.setSoldPlanId("sp-1");
        i.setPlanStepId("mps-20");
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();

        service().close("itv-1", new ClosureRequest("Rentrant", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        verifyNoInteractions(forfaits);
    }

    @Test
    void uneSortieDEntretienSansPalierNommeNeCreePasDePapier() {
        Intervention i = at(InterventionStatus.RETURNED);
        i.setPurpose(InterventionPurpose.ENTRETIEN);
        when(interventions.findById("itv-1")).thenReturn(Optional.of(i));
        when(technicians.findById(TECH_ID)).thenReturn(Optional.of(technician()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.empty());
        proofsPresent();

        InterventionResponse response = service().close("itv-1",
                new ClosureRequest("Visite hors forfait", null, null, null, null),
                jwt(UUID.randomUUID(), "chef@saphir.tn"), auth("CHEF_ATELIER"));

        assertNull(response.maintenanceItemId());
        verifyNoInteractions(forfaits);
    }
}
