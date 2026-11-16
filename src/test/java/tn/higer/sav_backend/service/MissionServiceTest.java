package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.InterventionDtos.InterventionResponse;
import tn.higer.sav_backend.dto.MissionDtos.MissionEnvoiRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionCountersResponse;
import tn.higer.sav_backend.dto.MissionDtos.MissionReasonRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionRequest;
import tn.higer.sav_backend.dto.MissionDtos.MissionResponse;
import tn.higer.sav_backend.model.CallerChannel;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionIssue;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanAlert;
import tn.higer.sav_backend.model.SoldPlanAlertKind;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.SoldPlanAlertRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de la mission : le chaînon entre la demande et le papier.
 *
 * Les règles qui portent le module sont vérifiées ici, pas à l'écran :
 *  - le numéro MT- vient du serveur, et reprend le plus HAUT numéro posé ;
 *  - une demande répond à UNE seule origine : le dossier où un client a parlé, ou le
 *    rappel d'un palier qui a tourné — jamais les deux, jamais rien ;
 *  - « envoyer un homme » passe par la porte unique de la sortie : le service de
 *    dépannage est appelé par ces méthodes-là, il n'y a pas une seconde écriture ;
 *  - une demande a DEUX places de réponse : l'homme dehors et le papier de l'atelier
 *    peuvent courir ensemble sur la même ligne (la même panne a un dépannage ET un
 *    devis), mais ordre et devis se disputent la même case et se refusent ;
 *  - « Soldée » se lit derrière le papier, jamais ne s'écrit : la preuve est qu'aucun
 *    save() a lieu pour la produire. Avec deux papiers, il faut les deux finis ;
 *  - un devis se refuse sur un bus sous garantie : la panne se demande au constructeur,
 *    elle ne se chiffre pas chez le client. La sortie et l'ordre de travail, eux,
 *    ne passent pas par cette garde.
 *
 * Les dépôts, {@link InterventionService} et {@link AuditService} sont mockés ; le
 * service est instancié directement, comme sur les autres fiches du projet.
 */
@ExtendWith(MockitoExtension.class)
class MissionServiceTest {

    @Mock private MissionRepository missions;
    @Mock private SavCaseRepository savCases;
    @Mock private InterventionRepository interventions;
    @Mock private MaintenanceRepository documents;
    @Mock private VehicleRepository vehicles;
    @Mock private ClientRepository clients;
    @Mock private SoldPlanAlertRepository rappels;
    @Mock private SoldPlanStepRepository suivis;
    @Mock private InterventionService sortie;
    @Mock private AuditService audit;

    private static final String MSS = "mss-1";
    private static final String SAV_ID = "sav-1";
    private static final String ALERT = "spa-3";
    private static final String BUS_ID = "veh-1";
    private static final String CLI_ID = "cli-1";
    private static final String ITV = "itv-9";
    private static final String ORDRE = "maint-9";
    private static final String DEVIS = "maint-devis-9";
    private static final UUID HOMME = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final int ANNEE = LocalDateTime.now().getYear();
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private MissionService service() {
        return new MissionService(missions, savCases, interventions, documents, vehicles, clients,
                rappels, suivis, sortie, audit, 19, 48, 12);
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "guichet@higer.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Le dossier d'appel : c'est lui qui porte qui, par quel canal, à quelle heure. */
    private static SavCase dossier() {
        SavCase c = new SavCase();
        c.setId(SAV_ID);
        c.setCaseNumber("REC-" + ANNEE + "-004");
        c.setTitle("Panne climatisation");
        c.setVehicleId(BUS_ID);
        c.setClientId(CLI_ID);
        c.setCallerName("M. Ben Salah");
        c.setCallerChannel(CallerChannel.TELEPHONE);
        c.setCalledAt(LocalDateTime.of(ANNEE, 10, 27, 8, 15));
        return c;
    }

    private static Mission mission(MissionState etat, MissionIssue issue) {
        Mission m = new Mission();
        m.setId(MSS);
        m.setNumber("MT-" + ANNEE + "-001");
        m.setSavCaseId(SAV_ID);
        m.setState(etat);
        m.setIssue(issue);
        m.setCreatedAt(LocalDateTime.of(ANNEE, 10, 27, 9, 0));
        return m;
    }

    /** Le rappel que le compteur du bus a posé : personne n'a appelé, la question vient du forfait. */
    private static SoldPlanAlert rappel() {
        SoldPlanAlert a = new SoldPlanAlert();
        a.setId(ALERT);
        a.setSoldPlanId("sp-1");
        a.setSoldPlanStepId("sps-mps-40");
        a.setKind(SoldPlanAlertKind.KM_ATTEINT);
        a.setMessage("palier atteint");
        a.setDetectedAt(LocalDateTime.of(ANNEE, 10, 26, 7, 30));
        a.setResolved(false);
        a.setWaived(false);
        return a;
    }

    /**
     * Le palier du catalogue, avec le forfait qui l'a vendu sur CE bus. La mission
     * lit le bus et le client dessus : c'est la seule origine d'une demande de
     * rappel, et rien n'en est recopié dans la mission.
     */
    private static SoldPlanStep palierVendu() {
        MaintenancePlan plan = new MaintenancePlan();
        plan.setId("mpl-1");
        plan.setName("Forfait urbain 26 WBE");

        MaintenancePlanStep palier = new MaintenancePlanStep();
        palier.setId("mps-40");
        palier.setStepOrder(3);
        palier.setLabel("visite des 40 000 km");
        palier.setKm(40000L);
        palier.setPlan(plan);

        SoldPlan vendu = new SoldPlan();
        vendu.setId("sp-1");
        vendu.setPlan(plan);
        vendu.setVehicleId(BUS_ID);
        vendu.setClientId(CLI_ID);

        SoldPlanStep suivi = new SoldPlanStep();
        suivi.setId("sps-mps-40");
        suivi.setSoldPlan(vendu);
        suivi.setStep(palier);
        return suivi;
    }

    private static Intervention sortieLiee(InterventionStatus status) {
        Intervention i = new Intervention();
        i.setId(ITV);
        i.setNumber("DP-" + ANNEE + "-012");
        i.setStatus(status);
        return i;
    }

    private static MaintenanceItem ordreLie(MaintenanceStatus status) {
        MaintenanceItem o = new MaintenanceItem();
        o.setId(ORDRE);
        o.setNumber("OR-" + ANNEE + "-007");
        o.setType(MaintenanceType.OPERATION);
        o.setMissionId(MSS);
        o.setStatus(status);
        return o;
    }

    /** Le devis que la demande a déjà ouvert, accepté ou non par le client. */
    private static MaintenanceItem devisLie(MaintenanceStatus status) {
        MaintenanceItem d = ordreLie(status);
        d.setId(DEVIS);
        d.setNumber("DV-" + ANNEE + "-003");
        d.setType(MaintenanceType.DEVIS);
        return d;
    }

    /** La vue qu'{@link InterventionService} rend quand la sortie naît de son origine. */
    private static InterventionResponse vueDeSortie(String numero) {
        return new InterventionResponse(ITV, numero, "dispatched", "high",
                BUS_ID, "VF631E132LD002323", CLI_ID, "SNTRI",
                "tch-1", "Ali Trabelsi", null, null,
                null, "M. Ben Salah", "Panne climatisation",
                null, null, null,
                null, null, null, null, null, null,
                SAV_ID, "REC-" + ANNEE + "-004",
                "panne", null, null, null,
                "usr-1", false, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                null, null, List.of(), false);
    }

    private static Client client() {
        Client c = new Client();
        c.setId(CLI_ID);
        c.setName("SNTRI");
        return c;
    }

    private static Vehicle bus() {
        Vehicle v = new Vehicle();
        v.setId(BUS_ID);
        v.setVin("VF631E132LD002323");
        v.setRegistration("1234-5678");
        return v;
    }

    // ------------------------------------------------------ enregistrer une demande

    /**
     * Le numéro se pose serveur, et le compteur reprend le plus haut numéro — pas le
     * plus long : « MT-2026-0028 » cohabite avec « MT-2026-029 » et rendrait un
     * numéro déjà pris (le 500 du 26/10 sur les dossiers SAV).
     */
    @Test
    void le_numero_MT_vient_du_serveur_et_la_mission_dem_arrive_a_traiter() {
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(missions.findNumbersByPrefix("MT-" + ANNEE + "-"))
                .thenReturn(List.of("MT-" + ANNEE + "-029", "MT-" + ANNEE + "-0028"));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));

        MissionResponse vue = service().creer(new MissionRequest(SAV_ID, null, "Client rappelé deux fois"),
                jwt(), auth("GESTIONNAIRE_SAV"));

        assertEquals("MT-" + ANNEE + "-0030", vue.number());
        assertEquals("a_traiter", vue.state());
        assertEquals("À traiter", vue.stateLabel());
        assertNull(vue.issue());

        ArgumentCaptor<Mission> ecrite = ArgumentCaptor.forClass(Mission.class);
        verify(missions).save(ecrite.capture());
        assertEquals(SAV_ID, ecrite.getValue().getSavCaseId());
        assertNull(ecrite.getValue().getAlertId(), "c'est un appel, pas un rappel : une seule origine");
        assertEquals("usr-1", ecrite.getValue().getCreatedBy());
        assertNotNull(ecrite.getValue().getCreatedAt());
        assertNull(ecrite.getValue().getInterventionId());
        assertTrue(ecrite.getValue().getId().startsWith("mss-"));
    }

    /**
     * Les cases de l'appel sont des reflets lus sur le dossier : la mission n'a ni
     * colonne « qui a parlé » ni colonne « canal ». C'est le dessin de l'atelier —
     * une copie par écran serait une seconde version de l'appel à corriger deux fois.
     */
    @Test
    void rien_de_l_appel_n_est_recopie_ca_se_lit_sur_le_dossier() {
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of(client()));

        MissionResponse vue = service().creer(new MissionRequest(SAV_ID, null, null), jwt(), auth("ADMIN"));

        assertEquals("REC-" + ANNEE + "-004", vue.caseNumber());
        assertEquals("M. Ben Salah", vue.callerName());
        assertEquals("Téléphone", vue.callerChannelLabel());
        assertEquals("SNTRI", vue.clientName());
        assertEquals("VF631E132LD002323", vue.busLabel());
    }

    /**
     * Une demande née d'un rappel n'a personne au bout du fil : le bus et le client
     * se lisent sur le forfait vendu, et la ligne nomme le palier réclamé.
     */
    @Test
    void une_demandee_nee_d_un_rappel_porte_son_palier_sans_dossier() {
        when(rappels.findById(ALERT)).thenReturn(Optional.of(rappel()));
        when(rappels.findAllById(any())).thenReturn(List.of(rappel()));
        when(suivis.findAllById(any())).thenReturn(List.of(palierVendu()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of(client()));

        MissionResponse vue = service().creer(new MissionRequest(null, ALERT, null),
                jwt(), auth("GESTIONNAIRE_SAV"));

        assertEquals("MT-" + ANNEE + "-001", vue.number());
        assertEquals(ALERT, vue.alertId());
        assertNull(vue.savCaseId(), "personne n'a appelé : il n'y a pas de dossier derrière la demande");
        assertTrue(vue.rappelLabel().contains("visite des 40 000 km"), vue.rappelLabel());
        assertEquals("SNTRI", vue.clientName(), "le client se lit sur le forfait, jamais recopié");
        assertEquals("VF631E132LD002323", vue.busLabel());
        assertNull(vue.callerName());

        ArgumentCaptor<Mission> ecrite = ArgumentCaptor.forClass(Mission.class);
        verify(missions).save(ecrite.capture());
        assertEquals(ALERT, ecrite.getValue().getAlertId());
        assertNull(ecrite.getValue().getSavCaseId());
    }

    /** Deux clics sur « Envoyer une équipe » du bandeau ne doivent pas aligner deux demandes. */
    @Test
    void deux_clics_sur_le_meme_rappel_ne_font_pas_deux_missions() {
        Mission deja = mission(MissionState.A_TRAITER, null);
        deja.setSavCaseId(null);
        deja.setAlertId(ALERT);
        when(rappels.findById(ALERT)).thenReturn(Optional.of(rappel()));
        when(missions.findByAlertIdOrderByCreatedAtDesc(ALERT)).thenReturn(List.of(deja));
        when(rappels.findAllById(any())).thenReturn(List.of(rappel()));
        when(suivis.findAllById(any())).thenReturn(List.of(palierVendu()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of(client()));

        MissionResponse vue = service().creer(new MissionRequest(null, ALERT, null), jwt(), auth("ADMIN"));

        assertEquals(MSS, vue.id(), "la demande qui court déjà est rendue, pas une seconde écrite");
        verify(missions, never()).save(any());
    }

    /** Un rappel levé ou soldé n'attend plus rien : y répondre serait une mission fantôme. */
    @Test
    void un_rappel_deja_ferme_ne_demande_plus_de_mission() {
        SoldPlanAlert ferme = rappel();
        ferme.setResolved(true);
        when(rappels.findById(ALERT)).thenReturn(Optional.of(ferme));

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> service().creer(new MissionRequest(null, ALERT, null), jwt(), auth("ADMIN")));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("déjà fermé"), erreur.getReason());
        verify(missions, never()).save(any());
    }

    /**
     * L'origine est exclusive : les deux cases à la fois, comme aucune des deux,
     * ne produisent pas de demande. Un dossier sans réponse écrite laisserait
     * l'atelier trancher sans savoir à qui.
     */
    @Test
    void une_demande_ne_peut_pas_repondre_a_deux_origines_ni_a_aucune() {
        ResponseStatusException lesDeux = assertThrows(ResponseStatusException.class,
                () -> service().creer(new MissionRequest(SAV_ID, ALERT, null), jwt(), auth("ADMIN")));
        assertEquals(HttpStatus.BAD_REQUEST, lesDeux.getStatusCode());
        assertTrue(lesDeux.getReason().contains("jamais aux deux"), lesDeux.getReason());

        ResponseStatusException aucune = assertThrows(ResponseStatusException.class,
                () -> service().creer(new MissionRequest(null, null, null), jwt(), auth("ADMIN")));
        assertEquals(HttpStatus.BAD_REQUEST, aucune.getStatusCode());

        verify(missions, never()).save(any());
    }

    @Test
    void une_demande_ne_peut_pas_repondre_a_un_dossier_inexistant() {
        ResponseStatusException sansDossier = assertThrows(ResponseStatusException.class,
                () -> service().creer(new MissionRequest("  ", null, null), jwt(), auth("ADMIN")));
        assertEquals(HttpStatus.BAD_REQUEST, sansDossier.getStatusCode());

        when(savCases.findById("sav-999")).thenReturn(Optional.empty());
        ResponseStatusException introuvable = assertThrows(ResponseStatusException.class,
                () -> service().creer(new MissionRequest("sav-999", null, null), jwt(), auth("ADMIN")));
        assertEquals(HttpStatus.BAD_REQUEST, introuvable.getStatusCode());
        verify(missions, never()).save(any());
    }

    /** Le guichet note ; un compte de consultation ne touche pas la file des demandes. */
    @Test
    void un_compte_de_consultation_ne_peut_pas_noter_une_demande() {
        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().creer(new MissionRequest(SAV_ID, null, null), jwt(), auth("CONSULTATION")));
        assertEquals(HttpStatus.FORBIDDEN, refuse.getStatusCode());
        verify(missions, never()).save(any());
    }

    // ------------------------------------- la case du dossier : la demande, pas la sortie

    /**
     * Cocher « un technicien doit aller sur place » enregistre la demande : c'est la
     * porte qui restait ouverte vers une sortie, et le lot N2 l'a refermée. La preuve
     * est double — la mission naît, et personne n'appelle la sortie.
     */
    @Test
    void la_case_d_un_dossier_enregistre_la_demande_et_ne_sort_personne() {
        Mission m = service().enregistrerDepuisDossier(dossier(), jwt());

        assertEquals("MT-" + ANNEE + "-001", m.getNumber());
        assertEquals(SAV_ID, m.getSavCaseId());
        assertEquals(MissionState.A_TRAITER, m.getState());
        assertTrue(m.getNote().contains("REC-" + ANNEE + "-004"), m.getNote());
        verify(missions).save(m);
        verify(sortie, never()).createFromSavCase(any(), any(), any());
    }

    /** Recocher la case du même dossier ne l'aligne pas en trois demandes. */
    @Test
    void recocher_la_meme_case_ne_refait_pas_une_demande() {
        Mission deja = mission(MissionState.A_TRAITER, null);
        when(missions.findBySavCaseIdOrderByCreatedAtDesc(SAV_ID)).thenReturn(List.of(deja));

        assertSame(deja, service().enregistrerDepuisDossier(dossier(), jwt()));
        verify(missions, never()).save(any());
    }

    /** Une demande refusée n'est plus ouverte : la case peut en poser une neuve. */
    @Test
    void une_demande_refusee_ne_bloque_pas_la_suivante() {
        Mission refusee = mission(MissionState.REFUSEE, null);
        refusee.setReason("Hors garantie");
        when(missions.findBySavCaseIdOrderByCreatedAtDesc(SAV_ID)).thenReturn(List.of(refusee));

        Mission suivante = service().enregistrerDepuisDossier(dossier(), jwt());

        assertNotSame(refusee, suivante);
        assertEquals(MissionState.A_TRAITER, suivante.getState());
    }

    // ------------------------------ la pièce livrée : la demande naît du fait, pas d'un homme

    /**
     * La pièce est arrivée au magasin : la demande de la monter doit exister sans
     * qu'un humain la tape. C'était le trou du portail — une ligne « Livrée » qui
     * n'envoyait rien, pendant que le bus attendait dehors.
     *
     * Ce que le test tient : la mission répond au DOSSIER D'ORIGINE (donc relit le
     * même bus, le même client), elle arrive « À traiter » et sans issue — le
     * portail ouvre la question, il ne la résout pas.
     */
    @Test
    void la_piece_livree_ouvre_sa_demande_de_pose_sur_le_meme_dossier() {
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));

        Mission pose = service().ouvrirPourLaPiece(
                new MissionService.PoseDePiece(SAV_ID, "SOR-" + ANNEE + "-011",
                        "Pompe à eau", "2115U", 2), jwt());

        assertEquals("MT-" + ANNEE + "-001", pose.getNumber());
        assertEquals(SAV_ID, pose.getSavCaseId());
        assertNull(pose.getAlertId(), "Une pièce ne naît pas d'un rappel de palier");
        assertNull(pose.getIssue(), "Personne n'a encore décidé qui y va");
        assertEquals(MissionState.A_TRAITER, pose.getState());
        assertTrue(pose.getNote().contains("Pompe à eau"), pose.getNote());
        assertTrue(pose.getNote().contains("2115U"), pose.getNote());
        assertTrue(pose.getNote().contains("2 arrivée(s)"), pose.getNote());
        assertTrue(pose.getNote().contains("SOR-" + ANNEE + "-011"), pose.getNote());
        verify(missions).save(pose);
        verify(audit).record(eq("CREATE"), eq("MISSION"), eq(pose.getId()), any(), any(Jwt.class));
    }

    /** Une pièce sans référence Higer se nomme par sa désignation, sans « réf. » vide. */
    @Test
    void sans_reference_la_demande_de_pose_nomme_qu_la_designation() {
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));

        Mission pose = service().ouvrirPourLaPiece(
                new MissionService.PoseDePiece(SAV_ID, "SOR-" + ANNEE + "-011",
                        "Pompe à eau", null, 1), jwt());

        assertTrue(pose.getNote().contains("« Pompe à eau »"), pose.getNote());
        assertTrue(!pose.getNote().contains("réf."), pose.getNote());
    }

    /**
     * La réception d'une pièce ne doit pas pouvoir inventer une réclamation. Sans
     * dossier réel derrière, rien ne s'ouvre et rien ne s'écrit.
     */
    @Test
    void la_demande_de_pose_ne_veut_pas_un_dossier_inexistant() {
        when(savCases.findById(SAV_ID)).thenReturn(Optional.empty());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().ouvrirPourLaPiece(new MissionService.PoseDePiece(SAV_ID,
                        "SOR-" + ANNEE + "-011", "Pompe à eau", "2115U", 1), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        verify(missions, never()).save(any());
    }

    // ------------------------------------------------------------------- trancher

    /** Le geste « envoyer un homme » appelle la porte unique de la sortie. */
    @Test
    void envoyer_un_homme_passe_par_la_porte_unique_de_la_sortie() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(sortie.createFromSavCase(eq(SAV_ID), any(), any()))
                .thenReturn(vueDeSortie("DP-" + ANNEE + "-012"));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.DISPATCHED)));

        MissionResponse vue = service().envoyerUnHomme(MSS, null, jwt(), auth("CHEF_ATELIER"));

        verify(sortie, times(1)).createFromSavCase(eq(SAV_ID), any(), any());
        assertEquals("en_cours", vue.state());
        assertEquals("Envoyer un homme", vue.issueLabel());
        assertEquals("DP-" + ANNEE + "-012", vue.interventionNumber());
        assertNull(vue.papierNumero());

        assertEquals(ITV, m.getInterventionId());
        assertNull(m.getMaintenanceItemId());
        assertEquals("usr-1", m.getDecidedBy());
        assertNotNull(m.getDecidedAt());
        verify(documents, never()).save(any());
    }

    /**
     * Une sortie d'entretien qui part d'un rappel n'a pas de dossier à lire : la
     * voiture de déplacement et l'homme se choisissent sur la ligne, et le portail
     * n'en devine aucun des deux.
     */
    @Test
    void une_sortie_d_entretien_part_avant_tout_sa_voiture_et_son_homme() {
        Mission m = mission(MissionState.A_TRAITER, null);
        m.setSavCaseId(null);
        m.setAlertId(ALERT);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));

        ResponseStatusException sansRien = assertThrows(ResponseStatusException.class,
                () -> service().envoyerUnHomme(MSS, new MissionEnvoiRequest(null, null), jwt(), auth("ADMIN")));
        assertTrue(sansRien.getReason().contains("voiture de déplacement"), sansRien.getReason());

        ResponseStatusException pasUnCompte = assertThrows(ResponseStatusException.class,
                () -> service().envoyerUnHomme(MSS,
                        new MissionEnvoiRequest("vs-1", "monsieur ben ali"), jwt(), auth("ADMIN")));
        assertTrue(pasUnCompte.getReason().contains("rechoisissez-le"), pasUnCompte.getReason());

        verify(sortie, never()).envoyerPourRappel(any(), any(), any(), any());
    }

    /** La sortie d'un rappel part par la porte du rappel, avec son motif « entretien ». */
    @Test
    void la_sortie_d_un_rappel_passe_par_la_porte_du_rappel() {
        Mission m = mission(MissionState.A_TRAITER, null);
        m.setSavCaseId(null);
        m.setAlertId(ALERT);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(sortie.envoyerPourRappel(eq(ALERT), any(), any(), any()))
                .thenReturn(vueDeSortie("DP-" + ANNEE + "-012"));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.DISPATCHED)));

        MissionResponse vue = service().envoyerUnHomme(MSS,
                new MissionEnvoiRequest("vs-1", HOMME.toString()), jwt(), auth("CHEF_ATELIER"));

        verify(sortie).envoyerPourRappel(eq(ALERT), any(), any(), any());
        verify(sortie, never()).createFromSavCase(any(), any(), any());
        assertEquals("DP-" + ANNEE + "-012", vue.interventionNumber());
    }

    /** Deux clics sur le bouton, ou l'écran qui renvoie : un seul papier dehors. */
    @Test
    void deux_clics_ne_font_pas_deux_sorties() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.ON_SITE)));

        MissionResponse vue = service().envoyerUnHomme(MSS, null, jwt(), auth("CHEF_ATELIER"));

        verify(sortie, never()).createFromSavCase(any(), any(), any());
        verify(missions, never()).save(any());
        assertEquals(MSS, vue.id());
        assertEquals("en_cours", vue.state());
    }

    /** Le bus vient à l'atelier : un ordre OR- brouillon, sans ligne ni montant. */
    @Test
    void traiter_a_atelier_ouvre_un_ordre_brouillon_qui_ne_devine_aucun_prix() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(documents.findNumbersByPrefix("OR-" + ANNEE + "-")).thenReturn(List.of("OR-" + ANNEE + "-007"));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        // Le dépôt rend ce que le service vient d'écrire, comme en base.
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().traiterAtelier(MSS, jwt(), auth("ADMIN"));

        MaintenanceItem papier = papierEcrit[0];
        assertEquals(MaintenanceType.OPERATION, papier.getType());
        assertEquals(MaintenanceStatus.DRAFT, papier.getStatus());
        assertEquals("OR-" + ANNEE + "-008", papier.getNumber());
        assertEquals(BUS_ID, papier.getVehicleId());
        assertTrue(papier.getDetails().isEmpty());
        assertTrue(papier.getDescription().contains("MT-" + ANNEE + "-001"));

        verify(sortie, never()).createFromSavCase(any(), any(), any());
        assertEquals(papier.getId(), m.getMaintenanceItemId());
        assertEquals("Traiter à l'atelier", vue.issueLabel());
        assertEquals("OR-" + ANNEE + "-008", vue.papierNumero());
        assertEquals("Ordre de travail", vue.papierLabel());
        assertNull(vue.interventionNumber());
    }

    /**
     * Le travail n'est pas couvert : un devis DV- s'ouvre, dans la même table que
     * l'ordre. L'écran doit dire « devis » — un atelier qui chercherait un ordre de
     * réparation sur un devis ne trouverait rien.
     */
    @Test
    void etablir_un_devis_ouvre_un_DV_brouillon_et_ne_devine_aucun_prix() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals(MaintenanceType.DEVIS, papierEcrit[0].getType());
        assertEquals("DV-" + ANNEE + "-001", papierEcrit[0].getNumber());
        assertEquals(MaintenanceStatus.DRAFT, papierEcrit[0].getStatus());
        assertTrue(papierEcrit[0].getDetails().isEmpty(), "le portail n'écrit pas un prix à la place de l'atelier");
        assertEquals("Établir un devis", vue.issueLabel());
        assertEquals("DV-" + ANNEE + "-001", vue.papierNumero());
        assertEquals("Devis", vue.papierLabel());
    }

    /**
     * Le cours réel d'une panne : l'homme constate sur place que le travail n'est pas
     * couvert, l'atelier doit le chiffrer. Les deux réponses écrivent dans deux cases
     * différentes de la même mission — la sortie reste, le devis s'ajoute, et
     * personne n'a refait une demande.
     */
    @Test
    void la_meme_panne_a_un_homme_dehors_et_un_devis() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findById(ITV)).thenReturn(Optional.of(sortieLiee(InterventionStatus.IN_PROGRESS)));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.IN_PROGRESS)));
        when(sortie.full(any())).thenReturn(vueDeSortie("DP-" + ANNEE + "-012"));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals(ITV, m.getInterventionId(), "la sortie qui court n'est pas effacée par le devis");
        assertEquals(papierEcrit[0].getId(), m.getMaintenanceItemId());
        assertEquals(MaintenanceType.DEVIS, papierEcrit[0].getType());
        assertEquals(MissionState.EN_COURS, m.getState());
        assertTrue(papierEcrit[0].getDetails().isEmpty(), "le portail n'écrit toujours pas un prix à la place de l'atelier");
        assertEquals("DP-" + ANNEE + "-012", vue.interventionNumber());
        assertEquals("DV-" + ANNEE + "-001", vue.papierNumero());
        assertEquals("Devis", vue.papierLabel());
        assertEquals("En cours", vue.stateLabel(), "un devis brouillon ne rend pas la demande finie");
        ArgumentCaptor<String> journal = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("UPDATE"), eq("MISSION"), eq(MSS), journal.capture(), any());
        assertTrue(journal.getValue().contains("seconde réponse"), journal.getValue());
        assertTrue(journal.getValue().contains("sortie DP-" + ANNEE + "-012"), journal.getValue());
    }

    /** Dans l'autre sens aussi : un devis qui court n'empêche pas l'homme de partir. */
    @Test
    void un_devis_deja_ouvert_empeche_pas_d_envoyer_un_homme() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.DEVIS);
        m.setMaintenanceItemId(ORDRE);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(documents.findById(ORDRE)).thenReturn(Optional.of(ordreLie(MaintenanceStatus.DRAFT)));
        when(sortie.createFromSavCase(eq(SAV_ID), any(), any()))
                .thenReturn(vueDeSortie("DP-" + ANNEE + "-013"));
        // La sortie qui vient de naître, telle que le lecteur la retrouve ensuite :
        // même identifiant, même numéro — deux fixtures différentes mentiraient l'une
        // sur l'autre.
        Intervention nouvelle = new Intervention();
        nouvelle.setId(ITV);
        nouvelle.setNumber("DP-" + ANNEE + "-013");
        nouvelle.setStatus(InterventionStatus.DISPATCHED);
        when(interventions.findById(ITV)).thenReturn(Optional.of(nouvelle));
        when(interventions.findAllById(any())).thenReturn(List.of(nouvelle));
        when(documents.findAllById(any())).thenReturn(List.of(ordreLie(MaintenanceStatus.DRAFT)));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));

        MissionResponse vue = service().envoyerUnHomme(MSS, null, jwt(), auth("CHEF_ATELIER"));

        assertEquals(ORDRE, m.getMaintenanceItemId(), "le devis qui court n'est pas effacé par la sortie");
        assertEquals(ITV, m.getInterventionId());
        assertEquals("DP-" + ANNEE + "-013", vue.interventionNumber());
        assertEquals("OR-" + ANNEE + "-007", vue.papierNumero());
    }

    /**
     * Ce qui ne coexiste pas, ce sont les deux papiers d'atelier : ordre et devis
     * écrivent dans la même case, le second effacerait le travail du premier. Le refus
     * est écrit et nomme le papier à clôturer.
     */
    @Test
    void un_ordre_et_un_devis_ne_tiennent_pas_dans_la_meme_case() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.ATELIER);
        m.setMaintenanceItemId(ORDRE);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(documents.findById(ORDRE)).thenReturn(Optional.of(ordreLie(MaintenanceStatus.DRAFT)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("ADMIN")));

        assertTrue(refuse.getReason().contains("même case"), refuse.getReason());
        assertTrue(refuse.getReason().contains("OR-" + ANNEE + "-007"), refuse.getReason());
        assertEquals(MissionIssue.ATELIER, m.getIssue());
        verify(documents, never()).save(any());
        verify(missions, never()).save(any());
    }

    // ---------------------------- un devis peut nommer le palier (article 5 du contrat)

    /**
     * La règle, telle que le porteur du portail l'a posée le 02/11 : le forfait ne vend
     * que la <b>main-d'œuvre</b>. Ce que l'article 5 exclut — la pièce fournie par
     * Saphir, le consommable, le déplacement, le travail correctif, la prestation en
     * plus — se chiffre au client, et le devis est le seul papier qui demande son accord.
     * Une demande née d'un rappel ouvre donc son devis, et le papier <b>garde le forfait
     * et le palier</b> dont il sort : c'est ce lien qui dira à la lecture que cette
     * visite des 40 000 km a eu une pièce payante.
     */
    @Test
    void un_rappel_de_palier_donnera_un_devis_qui_nomme_ce_palier() {
        Mission m = mission(MissionState.A_TRAITER, null);
        m.setSavCaseId(null);
        m.setAlertId(ALERT);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(rappels.findById(ALERT)).thenReturn(Optional.of(rappel()));
        when(suivis.findById("sps-mps-40")).thenReturn(Optional.of(palierVendu()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] pose = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            pose[0] = appel.getArgument(0);
            return pose[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(pose[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("Devis", vue.papierLabel());
        assertEquals(MaintenanceType.DEVIS, pose[0].getType());
        assertEquals("sp-1", pose[0].getSoldPlanId(), "le devis dit de quel forfait il sort");
        assertEquals("mps-40", pose[0].getPlanStepId(), "et de quel palier il facture le hors main-d'œuvre");
    }

    /**
     * L'autre porte : le dossier de la famille « Visites » marqué sur un forfait vendu.
     * Le devis se permet aussi — la visite a son homme et son temps payés, rien n'oblige
     * à chiffré une pièce ajoutée. Ce que l'écran relit, lui, reste le palier du dossier :
     * {@code ouvrirPapier} ne le reprend pas encore depuis un dossier (seul le rappel
     * porte le lien), et le test le dit au lieu de le cacher.
     */
    @Test
    void une_visite_marquee_au_forfait_sur_le_dossier_donnera_un_devis() {
        SavCase c = dossier();
        c.setType(SavCaseType.VISITES);
        c.setSoldPlanId("sp-1");
        c.setPlanStepId("mps-40");
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(c));
        when(savCases.findAllById(any())).thenReturn(List.of(c));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] pose = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            pose[0] = appel.getArgument(0);
            return pose[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(pose[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("DV-" + ANNEE + "-001", vue.papierNumero());
        assertTrue(vue.papierNumero() != null, "plus aucun refus sur ce geste");
    }

    /**
     * Ce qui passe : une <b>panne</b> sur un bus couvert par un forfait. Le travail
     * n'est pas au forfait, il se chiffre chez le client — la garde ne doit pas
     * transformer tout devis d'un client équipé en refus.
     */
    @Test
    void une_panne_d_un_bus_couvert_par_un_forfait_se_chiffre_toujours() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        MaintenanceItem[] pose = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            pose[0] = appel.getArgument(0);
            return pose[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(pose[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("Devis", vue.papierLabel());
        assertEquals(MSS, pose[0].getMissionId(), "le devis sait à quelle demande il répond");
        assertNull(pose[0].getSourceItemId(), "il sort de rien : c'est le premier papier de la chaîne");
    }

    // ------------------------------------------------- le devis accepté donne l'ordre

    /**
     * Le devis a l'accord du client : la même demande répond maintenant « Traiter à
     * l'atelier », et le papier d'atelier change de case. Avant ce chantier, les deux
     * papiers se disputaient la case et le second était refusé — l'atelier devait
     * refaire une demande. Ici l'ordre est <b>ouvert depuis le devis</b> : lien
     * {@code source_item_id}, lien {@code mission_id}, lignes non recopiées à la main.
     */
    @Test
    void un_devis_accepte_donne_enfin_le_travail_a_l_atelier() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.DEVIS);
        m.setMaintenanceItemId(DEVIS);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devisLie(MaintenanceStatus.VALIDATED)));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(documents.findNumbersByPrefix("OR-" + ANNEE + "-")).thenReturn(List.of("OR-" + ANNEE + "-007"));
        MaintenanceItem[] pose = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            pose[0] = appel.getArgument(0);
            return pose[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(pose[0]));

        MissionResponse vue = service().traiterAtelier(MSS, jwt(), auth("ADMIN"));

        MaintenanceItem ordre = pose[0];
        assertEquals(MaintenanceType.OPERATION, ordre.getType());
        assertEquals("OR-" + ANNEE + "-008", ordre.getNumber());
        assertEquals(DEVIS, ordre.getSourceItemId(), "l'ordre sait du devis qu'il sort");
        assertEquals(MSS, ordre.getMissionId());
        assertTrue(ordre.getDescription().contains("après le devis « DV-" + ANNEE + "-003 »"),
                ordre.getDescription());
        assertEquals(ordre.getId(), m.getMaintenanceItemId(), "la demande suit le travail qui court");
        assertEquals(MissionIssue.ATELIER, m.getIssue());
        assertEquals("OR-" + ANNEE + "-008", vue.papierNumero());
        ArgumentCaptor<String> journal = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("CREATE"), eq("MAINTENANCE"), eq(ordre.getId()), journal.capture(), any());
        assertTrue(journal.getValue().contains("depuis le devis « DV-" + ANNEE + "-003 »"), journal.getValue());
    }

    /**
     * Tant que le client n'a pas dit oui, le devis occupe la case : « Traiter à
     * l'atelier » se refuse et nomme le papier à faire avancer. Le refus ne déplace
     * rien — la demande garde son devis brouillon.
     */
    @Test
    void un_devis_brouillon_bloc_toujours_l_ouverture_de_l_ordre() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.DEVIS);
        m.setMaintenanceItemId(DEVIS);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devisLie(MaintenanceStatus.DRAFT)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().traiterAtelier(MSS, jwt(), auth("ADMIN")));

        assertTrue(refuse.getReason().contains("même case"), refuse.getReason());
        assertTrue(refuse.getReason().contains("DV-" + ANNEE + "-003"), refuse.getReason());
        assertEquals(DEVIS, m.getMaintenanceItemId());
        verify(documents, never()).save(any());
        verify(missions, never()).save(any());
    }

    // ------------------------------------------------------------------- P5 : la garantie

    /** Le bus avec sa date de fin de garantie — la seule case de la fiche que lit la garde. */
    private static Vehicle busSousGarantie(LocalDate fin) {
        Vehicle v = bus();
        v.setWarrantyEndDate(fin);
        return v;
    }

    /** Le dossier dont Higer a déjà répondu. La réponse se lit sur le dossier, jamais sur la mission. */
    private static SavCase dossierRepondu(ClaimState reponse) {
        SavCase c = dossier();
        c.setClaimState(reponse);
        return c;
    }

    /**
     * Le garde-fou de L6 : un bus couvert par Higer ne se chiffre pas chez le client.
     * Le devis écrit une dette chez le client pour une panne qui se demande au
     * constructeur — la perte serait pour Saphir. Le refus est écrit, il nomme le bus,
     * le dossier et le geste attendu, et rien n'est posé derrière.
     */
    @Test
    void un_bus_sous_garantie_ne_se_chiffre_pas_chez_le_client() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(LocalDate.now().plusDays(120))));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("sous garantie"), refuse.getReason());
        assertTrue(refuse.getReason().contains("VF631E132LD002323"), "le refus nomme le bus : " + refuse.getReason());
        assertTrue(refuse.getReason().contains("REC-" + ANNEE + "-004"), "et le dossier où ouvrir l'appel : " + refuse.getReason());
        assertTrue(refuse.getReason().contains("appel en garantie"), refuse.getReason());
        // Rien n'est écrit derrière le refus : ni papier, ni décision sur la demande.
        verify(documents, never()).save(any());
        verify(missions, never()).save(any());
        assertEquals(MissionState.A_TRAITER, m.getState());
        assertNull(m.getIssue(), "refuser n'est pas décider : la demande attend toujours son geste");
    }

    /**
     * La garde ne renvoie pas seulement « non » : elle dit où aller. Le bus est garanti
     * jusqu'à une date écrite — le refus la montre, l'atelier n'a pas à rouvrir la
     * fiche du bus pour comprendre.
     */
    @Test
    void le_refus_nomme_la_fin_de_garantie_ecrite_sur_la_fiche_d_bus() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(LocalDate.of(2027, 3, 15))));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")));

        assertTrue(refuse.getReason().contains("15/03/2027"), refuse.getReason());
    }

    /**
     * Le jour de la panne se lit sur l'appel du client, et c'est là que la garantie se
     * juge — pas à la date du jour (décision du 29/10). Un bus dont la garantie a expiré
     * la semaine dernière reste couvrable pour une panne d'il y a deux mois : c'est même
     * le cas normal, l'appel en garantie se dépose après coup. Ancien comportement : le
     * devis passait, et la même panne se facturait au client ET chez Higer.
     */
    @Test
    void la_garantie_se_juge_au_jour_de_l_incident_et_pas_aujourdhui() {
        LocalDate panne = LocalDate.now().minusMonths(2);
        LocalDate finDeGarantie = panne.plusDays(20);
        SavCase appele = dossier();
        appele.setCalledAt(panne.atTime(8, 15));

        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(appele));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(finDeGarantie)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")));

        assertTrue(refuse.getReason().contains(panne.format(JOUR)), refuse.getReason());
        assertTrue(refuse.getReason().contains(finDeGarantie.format(JOUR)), refuse.getReason());
        assertTrue(refuse.getReason().contains("appel du client"),
                "le refus doit dire quelle case il a relue : " + refuse.getReason());
        verify(documents, never()).save(any());
    }

    /**
     * Quand le papier AG porte sa date d'incident, c'est elle que le constructeur
     * confronte à sa garantie : elle prime sur l'appel du client. Sans ça, un dossier
     * dont l'appel est tardif laisserait passer un devis sur une panne couverte.
     */
    @Test
    void la_date_d_incident_du_papier_ag_prime_sur_l_appel() {
        LocalDate finDeGarantie = LocalDate.now().minusDays(6);
        SavCase papier = dossier();
        papier.setCalledAt(LocalDate.now().atTime(9, 0));
        papier.setAgIncidentDate(finDeGarantie.minusDays(12));

        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(papier));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(finDeGarantie)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")));

        assertTrue(refuse.getReason().contains("date de l’incident écrite sur le papier AG"),
                refuse.getReason());
        assertTrue(refuse.getReason().contains(papier.getAgIncidentDate().format(JOUR)), refuse.getReason());
    }

    /**
     * Ni papier AG ni appel daté : le jour où le dossier a été ouvert reste la date à
     * laquelle la panne a été annoncée. C'est le seul jour que le portail connaisse, et
     * le refus le nomme au lieu de faire croire à une garantie inventée.
     */
    @Test
    void a_defaut_d_appel_le_devis_se_juge_a_l_ouverture_du_dossier() {
        SavCase sansAppel = dossier();
        sansAppel.setCalledAt(null);
        sansAppel.setCreatedAt(LocalDate.now().minusMonths(4));

        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(sansAppel));
        when(vehicles.findById(BUS_ID))
                .thenReturn(Optional.of(busSousGarantie(LocalDate.now().minusMonths(3))));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")));

        assertTrue(refuse.getReason().contains("ouverture du dossier"), refuse.getReason());
    }

    /**
     * Higer a refusé l'appel : la panne est pour le client, le devis se rouvre. C'est
     * la sortie prévue par la règle — la garde ne tient qu'aussi longtemps que le
     * constructeur n'a pas dit non.
     */
    @Test
    void le_refus_du_constructeur_rouvre_le_devis() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossierRepondu(ClaimState.REFUSE)));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierRepondu(ClaimState.REFUSE)));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("DV-" + ANNEE + "-001", vue.papierNumero());
        assertEquals(MaintenanceType.DEVIS, papierEcrit[0].getType());
        // Le chemin du refus n'a pas eu à lire la fiche du bus : la réponse du
        // constructeur suffit à rouvrir le devis.
        verify(vehicles, never()).findById(any());
    }

    /** Un appel contesté n'est pas une panne couverte : le devis se rouvre aussi. */
    @Test
    void un_appel_conteste_rouvre_le_devis() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossierRepondu(ClaimState.CONTESTE)));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierRepondu(ClaimState.CONTESTE)));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of("DV-" + ANNEE + "-004"));
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("DV-" + ANNEE + "-005", vue.papierNumero());
    }

    /**
     * La garantie était finie deux mois avant l'appel du client : rien à demander à
     * Higer, le devis part. La date se lit sur l'appel (27/10 de l'année en cours),
     * pas sur « aujourd'hui » — c'est la décision du 29/10.
     */
    @Test
    void une_garantie_passee_ne_retient_plus_le_devis() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(LocalDate.now().minusMonths(2))));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("DV-" + ANNEE + "-001", vue.papierNumero());
        assertEquals("Établir un devis", vue.issueLabel());
    }

    /**
     * La fiche du bus ne porte aucune date de fin de garantie — le cas du bus acheté
     * hors contrat. Rien n'est deviné : pas de date, pas de refus. Un écran qui
     * bloquerait ici pousser l'atelier écrire une date au hasard.
     */
    @Test
    void une_garantie_sans_date_n_arrete_pas_le_devis() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(null)));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        assertEquals("DV-" + ANNEE + "-001", service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")).papierNumero());
    }

    /** La fiche du bus est absente (dossier posé sur un bus rayé) : la garde ne peut rien lire. */
    @Test
    void une_fiche_de_bus_absente_n_arrete_pas_le_devis() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.empty());
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        assertEquals("DV-" + ANNEE + "-001", service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")).papierNumero());
    }

    /**
     * La garde porte sur le devis, pas sur la réparation. Un bus garanti se répare à
     * l'atelier — c'est même là que se prépare le papier AG. Fermer cette porte
     * arrêterait le travail de l'atelier pour une règle d'argent.
     */
    @Test
    void la_garantie_n_arrete_pas_un_ordre_de_travail() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(documents.findNumbersByPrefix("OR-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] papierEcrit = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            papierEcrit[0] = appel.getArgument(0);
            return papierEcrit[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(papierEcrit[0]));

        MissionResponse vue = service().traiterAtelier(MSS, jwt(), auth("ADMIN"));

        assertEquals("OR-" + ANNEE + "-001", vue.papierNumero());
        verify(vehicles, never()).findById(any());
    }

    /** L'homme dehors ne se garde pas non plus : c'est la garantie qui paie sa sortie. */
    @Test
    void la_sortie_ne_se_garde_pas_de_la_garantie() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        // Le bus est garanti : si la porte de la sortie consultait la fiche, elle
        // refuserait et cette assertion tomberait.
        lenient().when(vehicles.findById(BUS_ID))
                .thenReturn(Optional.of(busSousGarantie(LocalDate.now().plusDays(30))));
        when(sortie.createFromSavCase(eq(SAV_ID), any(), any())).thenReturn(vueDeSortie("DP-" + ANNEE + "-014"));
        Intervention nouvelle = new Intervention();
        nouvelle.setId(ITV);
        nouvelle.setNumber("DP-" + ANNEE + "-014");
        nouvelle.setStatus(InterventionStatus.DISPATCHED);
        when(interventions.findAllById(any())).thenReturn(List.of(nouvelle));

        MissionResponse vue = service().envoyerUnHomme(MSS, null, jwt(), auth("CHEF_ATELIER"));

        assertEquals("DP-" + ANNEE + "-014", vue.interventionNumber());
    }

    /**
     * Le rappel d'un palier n'a pas de dossier au bout du fil : la garde « garantie »
     * ne se joue pas sur lui, et depuis la correction du 02/11 le palier vendu ne
     * ferme plus la porte du devis non plus. Ce qui reste prouvé ici : la demande née
     * d'un rappel va son devis <b>sans qu'aucun dossier soit lu</b>.
     */
    @Test
    void un_rappel_donnera_un_devis_sans_meme_lire_la_garantie() {
        Mission m = mission(MissionState.A_TRAITER, null);
        m.setSavCaseId(null);
        m.setAlertId(ALERT);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(rappels.findById(ALERT)).thenReturn(Optional.of(rappel()));
        when(suivis.findById("sps-mps-40")).thenReturn(Optional.of(palierVendu()));
        when(documents.findNumbersByPrefix("DV-" + ANNEE + "-")).thenReturn(List.of());
        MaintenanceItem[] pose = new MaintenanceItem[1];
        when(documents.save(any())).thenAnswer(appel -> {
            pose[0] = appel.getArgument(0);
            return pose[0];
        });
        when(documents.findAllById(any())).thenAnswer(appel -> List.of(pose[0]));

        MissionResponse vue = service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER"));

        assertEquals("DV-" + ANNEE + "-001", vue.papierNumero());
        verify(savCases, never()).findById(any());
    }

    /**
     * L'homme est déjà dehors et l'atelier veut chiffrer : la seconde réponse passe
     * par la même garde. Le refus n'efface pas la sortie qui court.
     */
    @Test
    void un_bus_garanti_refuse_le_devis_meme_qu_and_l_homme_est_dehors() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findById(SAV_ID)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById(BUS_ID)).thenReturn(Optional.of(busSousGarantie(LocalDate.now().plusDays(60))));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().etablirDevis(MSS, jwt(), auth("CHEF_ATELIER")));

        assertTrue(refuse.getReason().contains("sous garantie"), refuse.getReason());
        assertEquals(ITV, m.getInterventionId(), "la sortie qui court n'est pas touchée par le refus");
        assertEquals(MissionState.EN_COURS, m.getState());
        verify(documents, never()).save(any());
    }

    /** Trancher n'est pas le guichet : un technicien ne décide pas qu'il sort. */
    @Test
    void un_technicien_ne_tranche_pas_une_mission() {
        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().envoyerUnHomme(MSS, null, jwt(), auth("TECHNICIEN")));
        assertEquals(HttpStatus.FORBIDDEN, refuse.getStatusCode());
        verify(sortie, never()).createFromSavCase(any(), any(), any());
    }

    // ------------------------------------------------------- refuser, reporter, reprendre

    @Test
    void refuser_sans_ecrire_le_motif_est_refuse() {
        when(missions.findById(MSS)).thenReturn(Optional.of(mission(MissionState.A_TRAITER, null)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().refuser(MSS, new MissionReasonRequest("   "), jwt(), auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        verify(missions, never()).save(any());
    }

    @Test
    void le_motif_d_un_refus_s_ecrit_et_le_journal_le_porte() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));

        MissionResponse vue = service().refuser(MSS,
                new MissionReasonRequest("Hors garantie constructeur, le client paiera"), jwt(), auth("CHEF_ATELIER"));

        assertEquals("refusee", vue.state());
        assertEquals("Refusée", vue.stateLabel());
        assertEquals("Hors garantie constructeur, le client paiera", m.getReason());
        verify(audit).record(eq("UPDATE"), eq("MISSION"), eq(MSS), any(), any());
    }

    /** Refuser après avoir sorti l'homme voudrait dire qu'une sortie a couru pour rien. */
    @Test
    void on_ne_refuse_pas_apres_avoir_sorti_l_homme() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(interventions.findById(ITV)).thenReturn(Optional.of(sortieLiee(InterventionStatus.DISPATCHED)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().refuser(MSS, new MissionReasonRequest("finalement non"), jwt(), auth("ADMIN")));

        assertTrue(refuse.getReason().contains("DP-" + ANNEE + "-012"));
        assertEquals(MissionState.EN_COURS, m.getState());
    }

    /** Le refus est définitif pour cette demande : on ne le repeint pas en report. */
    @Test
    void une_mission_refusee_ne_se_reprase_pas_en_report() {
        Mission m = mission(MissionState.REFUSEE, null);
        m.setReason("Hors garantie");
        when(missions.findById(MSS)).thenReturn(Optional.of(m));

        ResponseStatusException reporterApresRefus = assertThrows(ResponseStatusException.class,
                () -> service().reporter(MSS, new MissionReasonRequest("le client réfléchit"), jwt(), auth("ADMIN")));
        assertTrue(reporterApresRefus.getReason().contains("refusée"));

        ResponseStatusException reprendreApresRefus = assertThrows(ResponseStatusException.class,
                () -> service().reprendre(MSS, jwt(), auth("ADMIN")));
        assertEquals(HttpStatus.BAD_REQUEST, reprendreApresRefus.getStatusCode());

        ResponseStatusException trancherApresRefus = assertThrows(ResponseStatusException.class,
                () -> service().envoyerUnHomme(MSS, null, jwt(), auth("ADMIN")));
        assertTrue(trancherApresRefus.getReason().contains("Hors garantie"));
        verify(missions, never()).save(any());
    }

    /** Reporter n'éteint rien : la demande reste due, et le motif reste écrit. */
    @Test
    void reporter_puis_rependre_laisse_le_motif_du_report_ecrit() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));

        MissionResponse reportee = service().reporter(MSS,
                new MissionReasonRequest("Pièce attendue du constructeur"), jwt(), auth("CHEF_ATELIER"));
        assertEquals("reportee", reportee.state());
        assertEquals("Reportée", reportee.stateLabel());

        MissionResponse reprise = service().reprendre(MSS, jwt(), auth("CHEF_ATELIER"));
        assertEquals("a_traiter", reprise.state());
        assertEquals("Pièce attendue du constructeur", m.getReason());
    }

    @Test
    void seule_une_mission_reportee_se_reprend() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().reprendre(MSS, jwt(), auth("CHEF_ATELIER")));
        assertTrue(refuse.getReason().contains("À traiter"));
    }

    // ------------------------------------------------------ « Soldée » ne s'écrit pas

    /**
     * La sortie du bout de la chaîne est clôturée : la ligne dit « Soldée ». Elle ne
     * l'écrit nulle part — la preuve est qu'aucun save() a lieu. Sinon deux services
     * (dépannage, maintenance) maintiendraient l'état d'un objet qui ne leur
     * appartient pas, et se contrediraient.
     */
    @Test
    void la_mission_est_dite_soldee_quand_la_sortie_est_cloturee_sans_rien_ecrire() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.CLOSED)));

        MissionResponse vue = service().get(MSS);

        assertEquals("soldee", vue.state());
        assertEquals("Soldée", vue.stateLabel());
        verify(missions, never()).save(any());
        assertEquals(MissionState.EN_COURS, m.getState());
    }

    @Test
    void un_ordre_livre_solde_aussi_la_mission() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.ATELIER);
        m.setMaintenanceItemId(ORDRE);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(documents.findAllById(any())).thenReturn(List.of(ordreLie(MaintenanceStatus.DELIVERED)));

        assertEquals("Soldée", service().get(MSS).stateLabel());
    }

    @Test
    void tant_que_le_papier_court_la_mission_reste_en_cours() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.RETURNED)));

        MissionResponse vue = service().get(MSS);
        assertEquals("En cours", vue.stateLabel());
        assertEquals("DP-" + ANNEE + "-012", vue.interventionNumber());
    }

    /**
     * Deux réponses sur la même panne : l'homme est rentré, sa sortie est clôturée,
     * mais le devis est encore brouillon. Dire « Soldée » ici enverrait un papier non
     * signé au client. La ligne ne se ferme que quand les DEUX papiers sont finis.
     */
    @Test
    void la_sortie_rendue_ne_solde_pas_seule_une_demande_qui_a_aussi_un_devis() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.DEVIS);
        m.setInterventionId(ITV);
        m.setMaintenanceItemId(ORDRE);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.CLOSED)));
        when(documents.findAllById(any())).thenReturn(List.of(ordreLie(MaintenanceStatus.DRAFT)));

        MissionResponse vue = service().get(MSS);

        assertEquals("En cours", vue.stateLabel());
        assertEquals("DP-" + ANNEE + "-012", vue.interventionNumber());
        assertEquals("OR-" + ANNEE + "-007", vue.papierNumero());
    }

    /** Les deux finis : la demande est rendue, et toujours sans que la mission l'écrive. */
    @Test
    void une_demande_a_deux_papiers_n_est_soldee_qu_une_fois_les_deux_finis() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.DEVIS);
        m.setInterventionId(ITV);
        m.setMaintenanceItemId(ORDRE);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.CLOSED)));
        when(documents.findAllById(any())).thenReturn(List.of(ordreLie(MaintenanceStatus.COMPLETED)));

        assertEquals("Soldée", service().get(MSS).stateLabel());
        verify(missions, never()).save(any());
    }

    /**
     * Un dossier SAV peut n'avoir ni client ni bus (saisie au téléphone, fiche pas
     * encore ouverte). Le lecteur de page ne doit pas en mourir : c'est le 500 du
     * 26/10, où une carte vide faisait tomber tout l'écran.
     */
    @Test
    void un_dossier_sans_bus_ni_client_ne_casse_pas_la_lecture() {
        SavCase creux = new SavCase();
        creux.setId(SAV_ID);
        creux.setCaseNumber("REC-" + ANNEE + "-009");
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(creux));

        MissionResponse vue = service().get(MSS);
        assertEquals("REC-" + ANNEE + "-009", vue.caseNumber());
        assertNull(vue.busLabel());
        assertNull(vue.clientName());
        assertNull(vue.callerChannelLabel());
        assertNull(vue.interventionNumber());
    }


    // ------------------------------------------------------ la montre des 48 heures

    /**
     * Un dossier d'appel horodaté à N heures d'ici. La montre s'appuie sur cette
     * seule heure : jamais l'écran ne la calcule, jamais la base ne la duplique.
     */
    private static SavCase dossierAppelleIlYa(long heures) {
        SavCase c = dossier();
        c.setCalledAt(LocalDateTime.now().minusHours(heures));
        return c;
    }

    /**
     * Le délai du contrat se mesure sur la demande, pas sur la décision : deux heures
     * après l'appel, il en reste quarante-six, et le guichet lit le même chiffre que
     * le serveur.
     */
    @Test
    void la_monte_dit_ce_quil_reste_depuis_lappel_du_client() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(12)));

        MissionResponse vue = service().get(MSS);
        assertEquals("dans_delai", vue.slaState());
        assertEquals("36 h rest.", vue.slaLabel());
    }

    @Test
    void le_delai_depasse_secrit_accompagne_de_de_quoi_il_depasse() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(50)));

        MissionResponse vue = service().get(MSS);
        assertEquals("depasse", vue.slaState());
        assertTrue(vue.slaLabel().startsWith("- "), "le retard se lit en heures passées : " + vue.slaLabel());
        assertTrue(vue.slaLabel().endsWith(" h"));
    }

    /** Le dernier tiers de la montre devient orange avant de passer en retard. */
    @Test
    void les_douze_dernieres_heures_sont_signalees_comme_limites() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(40)));

        MissionResponse vue = service().get(MSS);
        assertEquals("limite", vue.slaState());
        assertEquals("8 h rest.", vue.slaLabel());
    }

    /**
     * La zone d'alerte est un réglage du serveur, pas une opinion d'écran : le même
     * appel d'il y a 12 h passe en « limite » dès qu'on décide d'avertir plus tôt.
     * Deux postes qui choisiraient chacun leur seuil afficheraient deux couleurs sur
     * la même demande.
     */
    @Test
    void la_zone_alerte_se_regle_aussi_chez_le_serveur() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(12)));

        MissionService regle = new MissionService(missions, savCases, interventions, documents,
                vehicles, clients, rappels, suivis, sortie, audit, 19, 48, 40);

        assertEquals("limite", regle.get(MSS).slaState());
        assertEquals("36 h rest.", regle.get(MSS).slaLabel());
    }

    /**
     * Une demande reportée n'a pas reçu de réponse : le client attend toujours, et
     * la montre continue. S'il existe un moyen commode d'être toujours dans les
     * délais, c'est de reporter la demande — ce test-là ferme cette porte.
     */
    @Test
    void une_demande_reportee_garde_sa_monte_a_lheure() {
        SavCase vieille = dossier();
        // Un départ posé au-delà du double du délai : le reste mesuré ne dépend plus
        // de l'heure où le test tourne, seul le sens (retardé) compte ici.
        vieille.setCalledAt(LocalDateTime.of(ANNEE, 1, 1, 3, 0));
        Mission m = mission(MissionState.REPORTEE, null);
        m.setReason("pièce indisponible, rappel promis au client");
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(vieille));

        MissionResponse vue = service().get(MSS);
        assertEquals("depasse", vue.slaState());
        assertTrue(vue.slaLabel().startsWith("- "), "une demande reportée n'a pas répondu au client");
    }

    /** La demande rendue — bus rendu — n'a plus de course à courir. */
    @Test
    void une_demande_soldee_arrete_la_monte() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(80)));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.CLOSED)));

        MissionResponse vue = service().get(MSS);
        assertEquals("termine", vue.slaState());
        assertEquals("Terminé", vue.slaLabel());
    }

    /**
     * Personne n'a horodaté l'appel : rien à mesurer. La case reste vide avec ce qui
     * manque écrit, au lieu d'inventer une heure de départ pour remplir un bandeau.
     */
    @Test
    void un_appel_non_horodate_lise_aucun_delai_plutot_quun_chiffre_invente() {
        SavCase sansHeure = dossier();
        sansHeure.setCalledAt(null);
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(sansHeure));

        MissionResponse vue = service().get(MSS);
        assertEquals("aucun", vue.slaState());
        assertEquals("départ non horodaté", vue.slaLabel());
    }

    /**
     * Un rappel de palier : personne n'a appelé, la montre part de la date où le
     * compteur a fait tourner le rappel.
     */
    @Test
    void un_rappel_de_palier_compte_depuis_sa_date_de_detection() {
        SoldPlanAlert alerte = rappel();
        alerte.setDetectedAt(LocalDateTime.now().minusHours(26));
        Mission m = mission(MissionState.A_TRAITER, null);
        m.setSavCaseId(null);
        m.setAlertId(ALERT);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(rappels.findAllById(any())).thenReturn(List.of(alerte));
        when(suivis.findAllById(any())).thenReturn(List.of(palierVendu()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of(client()));

        MissionResponse vue = service().get(MSS);
        assertEquals("dans_delai", vue.slaState());
        assertEquals("22 h rest.", vue.slaLabel());
    }

    /**
     * Le bus se lit en deux cases : l'immatriculation que regarde l'atelier, le VIN
     * que demande le constructeur. Une seule des deux serait une information perdue.
     */
    @Test
    void la_ligne_porte_immatriculation_et_vin_du_bus() {
        Mission m = mission(MissionState.A_TRAITER, null);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus()));
        when(clients.findAllById(any())).thenReturn(List.of(client()));

        MissionResponse vue = service().get(MSS);
        assertEquals("1234-5678", vue.busRegistration());
        assertEquals("VF631E132LD002323", vue.busVin());
    }

    /**
     * L'homme dehors et l'étape de sa sortie se relisent sur la sortie : l'écran
     * Missions ne tient pas une seconde liste du personnel.
     */
    @Test
    void la_sortie_liee_dit_qui_est_dehors_et_ou_en_est_la_sortie() {
        Mission m = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        m.setInterventionId(ITV);
        when(missions.findById(MSS)).thenReturn(Optional.of(m));
        when(savCases.findAllById(any())).thenReturn(List.of(dossier()));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.ON_SITE)));
        when(sortie.full(any())).thenReturn(vueDeSortie("DP-" + ANNEE + "-012"));

        MissionResponse vue = service().get(MSS);
        assertEquals("dispatched", vue.interventionStatus());
        assertEquals("Ali Trabelsi", vue.interventionTechnicianName());
    }

    // ------------------------------------------------------------------ le bandeau

    /**
     * Les quatre chiffres du bandeau sortent du même lecteur que la liste : la preuve
     * est qu'ils comptent les mêmes lignes, y compris une mission que son papier a
     * déjà rendue et qui ne doit plus être portée comme une attente.
     */
    @Test
    void le_bandeau_compte_les_memes_lignes_que_la_liste() {
        Mission enRetard = mission(MissionState.A_TRAITER, null);
        Mission dehors = mission(MissionState.EN_COURS, MissionIssue.HOMME);
        dehors.setId("mss-2");
        dehors.setInterventionId(ITV);
        Mission rendue = mission(MissionState.EN_COURS, MissionIssue.ATELIER);
        rendue.setId("mss-3");
        rendue.setMaintenanceItemId(ORDRE);

        when(missions.findByStateInOrderByCreatedAtDesc(any())).thenReturn(
                List.of(enRetard, dehors, rendue));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(50)));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.ON_SITE)));
        when(sortie.full(any())).thenReturn(vueDeSortie("DP-" + ANNEE + "-012"));
        when(documents.findAllById(any())).thenReturn(List.of(ordreLie(MaintenanceStatus.COMPLETED)));

        MissionCountersResponse bandeau = service().compteurs();

        assertEquals(1, bandeau.enAttenteDecision());
        assertEquals(2, bandeau.delaiDepasse(),
                "les deux demandes encore dues sont en retard ; la rendue ne compte plus");
        assertEquals(1, bandeau.depannagesEnCours());
        assertEquals(0, bandeau.aLAtelier(), "l'ordre terminé rend le bus : ce n'est plus une attente");
        assertEquals(48, bandeau.delaiHeures());
    }

    /**
     * Une seule demande, deux réponses qui courent : elle compte dans les deux cases
     * du bandeau. Compter la dernière issue effacerait l'homme dehors ou le devis —
     * et le chef d'atelier regarderait un chiffre qui ne dit pas ce qu'il reste à
     * faire.
     */
    @Test
    void une_demande_a_deux_papiers_compte_dans_les_deux_cases_du_bandeau() {
        Mission deuxReponses = mission(MissionState.EN_COURS, MissionIssue.DEVIS);
        deuxReponses.setInterventionId(ITV);
        deuxReponses.setMaintenanceItemId(ORDRE);
        when(missions.findByStateInOrderByCreatedAtDesc(any())).thenReturn(List.of(deuxReponses));
        when(savCases.findAllById(any())).thenReturn(List.of(dossierAppelleIlYa(50)));
        when(interventions.findAllById(any())).thenReturn(List.of(sortieLiee(InterventionStatus.ON_SITE)));
        when(sortie.full(any())).thenReturn(vueDeSortie("DP-" + ANNEE + "-012"));
        when(documents.findAllById(any())).thenReturn(List.of(ordreLie(MaintenanceStatus.DRAFT)));

        MissionCountersResponse bandeau = service().compteurs();

        assertEquals(1, bandeau.depannagesEnCours(), "l'homme est dehors");
        assertEquals(1, bandeau.aLAtelier(), "le devis est à chiffrer en même temps");
    }

    @Test
    void une_mission_inconnue_rend_404_pas_une_page_vide() {
        when(missions.findById("mss-999")).thenReturn(Optional.empty());
        ResponseStatusException introuvable = assertThrows(ResponseStatusException.class,
                () -> service().get("mss-999"));
        assertEquals(HttpStatus.NOT_FOUND, introuvable.getStatusCode());
    }
}
