package tn.higer.sav_backend.service;

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
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.PlanAlertDto;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.PlanAlertListResponse;
import tn.higer.sav_backend.dto.SoldPlanAlertDtos.WaiveRequest;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.MileageOrigin;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanAlert;
import tn.higer.sav_backend.model.SoldPlanAlertKind;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.SoldPlanAlertRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le rappel des paliers : ce que LE COMPTEUR du bus réclame.
 *
 * Ce fichier tient les quatre comportements qui rendent un rappel utilisable plutôt
 * que bruit :
 *  - une borne atteinte ouvre UNE ligne, nommée avec son chiffre et sa source ;
 *  - le kilométrage décide pour un bus qui roule, les jours pour un bus qui dort,
 *    et un palier déjà fait n'est jamais rappelé ;
 *  - ce qui se règle tout seul — palier fait, forfait fermé, compteur corrigé à la
 *    baisse — ferme la ligne sans décision, et la même question n'est pas reposée
 *    deux fois par heure ;
 *  - un rappel ne se lève que par une raison écrite, et seulement par un compte qui
 *    écrit dans les forfaits.
 *
 * Les seuils ne sont pas des propriétés : ils sont écrits palier par palier dans le
 * catalogue importé. C'est donc la borne du palier qui est fixée dans chaque test.
 */
@ExtendWith(MockitoExtension.class)
class SoldPlanAlertServiceTest {

    @Mock private SoldPlanRepository soldPlans;
    @Mock private SoldPlanAlertRepository alerts;
    @Mock private VehicleRepository vehicles;
    @Mock private ClientRepository clients;
    @Mock private AuditService audit;
    /** Les sorties, lues seulement pour donner leur numéro à un rappel pris en charge. */
    @Mock private InterventionRepository sorties;
    /** Les dossiers de visite, lus seulement pour dire sur quelle ligne un visite court déjà. */
    @Mock private SavCaseRepository dossiers;

    private static final String BUS = "veh-1";
    private static final String CLIENT = "cli-1";
    private static final String ALERT_ID = "spa-1";

    private SoldPlanAlertService service() {
        return new SoldPlanAlertService(soldPlans, alerts, vehicles, clients, audit, sorties, dossiers, false);
    }

    // ------------------------------------------------------------------ les pièces

    /**
     * Le début du forfait, en jours depuis aujourd'hui. Une date absolue ferait
     * passer le test au vert aujourd'hui et à l'orange dans un an : la borne qui
     * décide est un délai, donc c'est l'écart au jour d'aujourd'hui qui compte.
     */
    private static LocalDate ilYa(int jours) {
        return LocalDate.now().minusDays(jours);
    }

    private static Vehicle bus(Long mileage) {
        return bus(mileage, null);
    }

    /**
     * Le bus du portefeuille, avec le compteur que la porte du compteur y a laissé et
     * son origine : c'est la seule source que le rappel accepte de lire.
     */
    private static Vehicle bus(Long mileage, MileageOrigin origine) {
        Vehicle bus = new Vehicle();
        bus.setId(BUS);
        bus.setClientId(CLIENT);
        bus.setVin("LK1H2A2B3C4D5E6F7");
        bus.setRegistration("CS-1234-26");
        bus.setMileage(mileage);
        bus.setMileageOrigin(origine);
        return bus;
    }

    private static Client client() {
        Client client = new Client();
        client.setId(CLIENT);
        client.setName("Sfax Transport");
        return client;
    }

    /**
     * Un plan à deux paliers : une visite à 10 000 km, une révision à 60 000 km ou
     * 12 mois. Le second porte les deux bornes parce que c'est ce que la grille
     * Higer importe le plus souvent, et c'est donc lui qui teste « au premier
     * atteint ».
     */
    private static MaintenancePlan planAuCatalogue() {
        MaintenancePlan plan = new MaintenancePlan();
        plan.setId("mpl-1");
        plan.setName("Forfait urbain 26 WBE");
        plan.setBusModel("Confort");
        plan.setActive(true);

        MaintenancePlanStep dixMille = new MaintenancePlanStep();
        dixMille.setId("mps-10");
        dixMille.setStepOrder(1);
        dixMille.setLabel("visite des 10 000 km");
        dixMille.setKm(10000L);
        dixMille.setPlan(plan);

        MaintenancePlanStep douzeMois = new MaintenancePlanStep();
        douzeMois.setId("mps-20");
        douzeMois.setStepOrder(2);
        douzeMois.setLabel("révision des 12 mois");
        douzeMois.setKm(60000L);
        douzeMois.setDays(365);
        douzeMois.setPlan(plan);

        plan.getSteps().add(dixMille);
        plan.getSteps().add(douzeMois);
        return plan;
    }

    /**
     * Le forfait vendu et son suivi. Les paliers passent à TODO sauf ceux cités dans
     * {@code dejaFaits} (identifiants du catalogue).
     */
    private static SoldPlan forfait(SoldPlanStatus statut, LocalDate debut, String... dejaFaits) {
        MaintenancePlan plan = planAuCatalogue();
        SoldPlan sold = new SoldPlan();
        sold.setId("sp-1");
        sold.setPlan(plan);
        sold.setClientId(CLIENT);
        sold.setVehicleId(BUS);
        sold.setStartDate(debut);
        sold.setStatus(statut);
        sold.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        for (MaintenancePlanStep palier : plan.getSteps()) {
            SoldPlanStep ligne = new SoldPlanStep();
            ligne.setId("sps-" + palier.getId());
            ligne.setSoldPlan(sold);
            ligne.setStep(palier);
            ligne.setStatus(List.of(dejaFaits).contains(palier.getId())
                    ? SoldPlanStepStatus.DONE : SoldPlanStepStatus.TODO);
            sold.getSteps().add(ligne);
        }
        return sold;
    }

    private static SoldPlanAlert rappelOuvert(String stepId, SoldPlanAlertKind kind) {
        SoldPlanAlert a = new SoldPlanAlert();
        a.setId(ALERT_ID);
        a.setSoldPlanId("sp-1");
        a.setSoldPlanStepId(stepId);
        a.setKind(kind);
        a.setMessage("rappel signalé");
        a.setDetectedAt(LocalDateTime.of(2026, 6, 1, 8, 0));
        a.setResolved(false);
        a.setWaived(false);
        return a;
    }

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(java.util.Locale.ROOT))));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "sav@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    // ------------------------------------------------------------------ la détection

    /**
     * Le compteur qu'une sortie a remonté sur la fiche déclenche le rappel, et le
     * rappel le dit. Le rappel ne relit pas les sorties à son compte : un bus dont la
     * fiche dit 10 120 km « relevés par le technicien » est déjà la preuve du chemin.
     */
    @Test
    void leCompteurRemonteParUneSortieDeclencheLeRappelDuPalier() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30))));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(10120L, MileageOrigin.RELEVE_TECHNICIEN)));

        assertEquals(1, service().scan());

        ArgumentCaptor<SoldPlanAlert> sauvegarde = ArgumentCaptor.forClass(SoldPlanAlert.class);
        verify(alerts).save(sauvegarde.capture());
        SoldPlanAlert a = sauvegarde.getValue();
        assertEquals(SoldPlanAlertKind.KM_ATTEINT, a.getKind());
        assertEquals("sps-mps-10", a.getSoldPlanStepId());
        assertEquals("sp-1", a.getSoldPlanId());
        // Le chiffre qui a décidé et sa provenance : un rappel contesté se discute
        // sur l'origine du compteur, pas sur une valeur sans source.
        assertTrue(a.getMessage().contains("10 120"), a.getMessage());
        assertTrue(a.getMessage().contains("relevé par le technicien sur une sortie"), a.getMessage());
        assertTrue(a.getMessage().contains("visite des 10 000 km"), a.getMessage());
    }

    @Test
    void lesJoursDecidentPourUnBusQuiDortALAtelier() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(800))));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(3000L)));

        assertEquals(1, service().scan());

        ArgumentCaptor<SoldPlanAlert> sauvegarde = ArgumentCaptor.forClass(SoldPlanAlert.class);
        verify(alerts).save(sauvegarde.capture());
        assertEquals(SoldPlanAlertKind.DELAI_ECHU, sauvegarde.getValue().getKind());
        assertTrue(sauvegarde.getValue().getMessage().contains("365 jours"),
                sauvegarde.getValue().getMessage());
        assertTrue(sauvegarde.getValue().getMessage().contains("3 000 km"), sauvegarde.getValue().getMessage());
    }

    /**
     * Le palier porte les deux bornes : c'est celle qui a décidé qui nomme l'alerte,
     * et l'autre reste lisible dans le message. Deux lignes pour une seule visite
     * serait du bruit.
     */
    @Test
    void laBorneAtteinteDecideEtLAutreResteLisible() {
        SoldPlan sold = forfait(SoldPlanStatus.ACTIVE, ilYa(30));
        MaintenancePlanStep dixMille = sold.getSteps().get(0).getStep();
        dixMille.setDays(200);
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE)).thenReturn(List.of(sold));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(12000L)));

        assertEquals(1, service().scan());

        ArgumentCaptor<SoldPlanAlert> sauvegarde = ArgumentCaptor.forClass(SoldPlanAlert.class);
        verify(alerts).save(sauvegarde.capture());
        assertEquals(SoldPlanAlertKind.KM_ATTEINT, sauvegarde.getValue().getKind());
        assertTrue(sauvegarde.getValue().getMessage().contains("200 jours"), sauvegarde.getValue().getMessage());
        assertTrue(sauvegarde.getValue().getMessage().contains("pas encore écoulé"),
                sauvegarde.getValue().getMessage());
    }

    @Test
    void unPalierDejaFaitNestJamaisRappele() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(800), "mps-10", "mps-20")));

        assertEquals(0, service().scan());
        verify(alerts, never()).save(any(SoldPlanAlert.class));
    }

    @Test
    void unForfaitAnnuleNeProduitPlusRien() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE)).thenReturn(List.of());

        assertEquals(0, service().scan());
        // Un seul lecteur de forfaits, et le statut est dans la requête : un forfait
        // clos ne se relit pas pour être écarté après coup.
        verify(soldPlans).findByStatus(SoldPlanStatus.ACTIVE);
        verify(soldPlans, never()).findAllByOrderByStartDateDesc();
    }

    // ------------------------------------------------------------------ la fermeture

    @Test
    void laMemeQuestionNestReposeeDeuxFois() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30))));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(50000L)));
        when(alerts.existsBySoldPlanStepIdAndResolvedFalse("sps-mps-10")).thenReturn(true);

        assertEquals(0, service().scan());
        verify(alerts, never()).save(any(SoldPlanAlert.class));
    }

    /** Un compteur corrigé à la baisse : la borne n'est plus atteinte, le rappel se ferme. */
    @Test
    void ceQuiNestPlusVraiFermeLeRappelSansDecision() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30))));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(4000L)));
        SoldPlanAlert ouvert = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        when(alerts.findBySoldPlanStepIdAndResolvedFalse("sps-mps-10")).thenReturn(List.of(ouvert));

        assertEquals(0, service().scan());

        assertTrue(ouvert.isResolved());
        assertNull(ouvert.getWaivedAt(), "résolue toute seule : personne n'a décidé");
        assertFalse(ouvert.isWaived());
        verify(alerts).save(ouvert);
    }

    /**
     * Ce qui s'est passé à l'atelier : un compteur remis à 4 300 km avec motif alors
     * qu'une sortie ancienne l'a lu à 15 000 km. La sortie reste dans la base, elle ne
     * parle plus au bandeau — le rappel ne reçoit même plus le dépôt des sorties, pour
     * qu'un deuxième calcul du compteur ne puisse pas renaître ici.
     */
    @Test
    void uneSortieAncienneNeContreditPlusLaFicheDuBus() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30))));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(4300L, MileageOrigin.ATELIER)));

        assertEquals(0, service().scan());
        verify(alerts, never()).save(any(SoldPlanAlert.class));
    }

    /** Le rappel d'un palier qui a quitté le forfait (plan retiré, forfait annulé) se ferme. */
    @Test
    void unRappelSansPalierSuiviSeFerme() {
        when(soldPlans.findByStatus(SoldPlanStatus.ACTIVE)).thenReturn(List.of());
        SoldPlanAlert orphelin = rappelOuvert("sps-efface", SoldPlanAlertKind.KM_ATTEINT);
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of(orphelin));
        when(alerts.findBySoldPlanStepIdAndResolvedFalse("sps-efface")).thenReturn(List.of(orphelin));

        service().scan();

        assertTrue(orphelin.isResolved());
    }

    /**
     * Le palier se fait par un document livré : la ligne de suivi passe à DONE et le
     * rappel doit fermer dans le même geste, sinon le suivi dirait « fait » pendant
     * que le bandeau dirait encore « à faire ».
     */
    @Test
    void lePalierQuiSeFermeSonRappelDansLeMemeGeste() {
        SoldPlanAlert ouvert = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        when(alerts.findBySoldPlanStepIdAndResolvedFalse("sps-mps-10")).thenReturn(List.of(ouvert));

        service().palierFait("sps-mps-10");

        assertTrue(ouvert.isResolved());
        assertNotNull(ouvert.getResolvedAt());
    }

    @Test
    void unForfaitFermeNetoieSesRappels() {
        SoldPlanAlert ouvert = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        when(alerts.findBySoldPlanIdOrderByDetectedAtDesc("sp-1")).thenReturn(List.of(ouvert));

        service().forfaitFerme("sp-1");

        assertTrue(ouvert.isResolved());
    }

    // ------------------------------------------------------------------ la lecture

    @Test
    void laListePorteLeBusLeClientLePalierEtLaSourceDuCompteur() {
        SoldPlan sold = forfait(SoldPlanStatus.ACTIVE, ilYa(30));
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc())
                .thenReturn(List.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        when(soldPlans.findAllById(List.of("sp-1"))).thenReturn(List.of(sold));
        when(vehicles.findAllById(List.of(BUS))).thenReturn(List.of(bus(10120L)));
        when(clients.findAllById(List.of(CLIENT))).thenReturn(List.of(client()));
        // La liste relit le compteur au moment où elle répond : le bandeau doit
        // montrer le chiffre d'aujourd'hui, pas celui stocké dans le message.
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(10120L)));

        PlanAlertListResponse l = service().ouverts();

        assertEquals(1, l.openCount());
        PlanAlertDto a = l.alerts().get(0);
        assertEquals("CS-1234-26", a.vehicleRegistration());
        assertEquals("Sfax Transport", a.clientName());
        assertEquals("visite des 10 000 km", a.palierLabel());
        assertEquals(10000L, a.palierKm());
        assertEquals("Forfait urbain 26 WBE", a.planName());
        assertEquals(10120L, a.compteurConnu());
        assertTrue(a.sourceCompteur().contains("fiche du bus"), a.sourceCompteur());
        assertEquals("KM_ATTEINT", a.kind());
        // La règle est récitée avec la liste : une règle cachée se discute, une
        // règle écrite se contrôle.
        assertTrue(l.rules().contains("km"), l.rules());
        assertTrue(l.rules().contains("raison écrite"), l.rules());
    }

    /**
     * Le rappel et le dossier de visite se reconnaissent : la ligne du bandeau nomme
     * le dossier qui court déjà sur CE palier. Sans ce reflet, « visite périodique »
     * voudrait dire deux choses qui ne se connaissent pas — la question posée par le
     * compteur d'un côté, le papier tapé à l'atelier de l'autre — et la visite des
     * 10 000 km pourrait être faite deux fois, ou aucune.
     */
    @Test
    void laLigneDuRappelNommeLeDossierDeVisiteQuiCourtDejaSurCePalier() {
        SoldPlan sold = forfait(SoldPlanStatus.ACTIVE, ilYa(30));
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc())
                .thenReturn(List.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        when(soldPlans.findAllById(List.of("sp-1"))).thenReturn(List.of(sold));
        when(vehicles.findAllById(List.of(BUS))).thenReturn(List.of(bus(10120L)));
        when(clients.findAllById(List.of(CLIENT))).thenReturn(List.of(client()));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(10120L)));
        when(dossiers.findBySoldPlanIdInOrderByCreatedAtDesc(List.of("sp-1")))
                .thenReturn(List.of(dossierVisite(SavCaseStatus.IN_PROGRESS)));

        PlanAlertDto a = service().ouverts().alerts().get(0);

        assertEquals("sav-7", a.dossierVisiteId());
        assertEquals("SAV-VIS-2026-003", a.dossierVisiteNumber());
    }

    /**
     * Le palier se matche sur l'identifiant du catalogue (« mps-10 »), jamais sur la
     * ligne de suivi : deux bus vendus du même forfait ont deux lignes distinctes pour
     * la même visite, et un dossier rattaché à la visite d'un autre palier ne répond
     * pas à ce rappel.
     */
    @Test
    void unDossierRattacheAUnAutrePalierOuClotureNeComptePas() {
        SoldPlan sold = forfait(SoldPlanStatus.ACTIVE, ilYa(30));
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc())
                .thenReturn(List.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        when(soldPlans.findAllById(List.of("sp-1"))).thenReturn(List.of(sold));
        when(vehicles.findAllById(List.of(BUS))).thenReturn(List.of(bus(10120L)));
        when(clients.findAllById(List.of(CLIENT))).thenReturn(List.of(client()));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(10120L)));

        SavCase cloture = dossierVisite(SavCaseStatus.CLOSED);
        SavCase autrePalier = dossierVisite(SavCaseStatus.OPEN);
        autrePalier.setId("sav-8");
        autrePalier.setPlanStepId("mps-20");
        when(dossiers.findBySoldPlanIdInOrderByCreatedAtDesc(List.of("sp-1")))
                .thenReturn(List.of(cloture, autrePalier));

        assertNull(service().ouverts().alerts().get(0).dossierVisiteId(),
                "ni le dossier rendu ni celui d'un autre palier n'éteignent la question posée");
    }

    /** Un dossier de visite de CE forfait, sur le palier des 10 000 km. */
    private static SavCase dossierVisite(SavCaseStatus statut) {
        SavCase c = new SavCase();
        c.setId("sav-7");
        c.setCaseNumber("SAV-VIS-2026-003");
        c.setType(SavCaseType.VISITES);
        c.setStatus(statut);
        c.setSoldPlanId("sp-1");
        c.setPlanStepId("mps-10");
        return c;
    }

    @Test
    void leBusIntrouvableNeFaitPasTomberLaListe() {
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc())
                .thenReturn(List.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        SoldPlan sold = forfait(SoldPlanStatus.ACTIVE, ilYa(30));
        when(soldPlans.findAllById(List.of("sp-1"))).thenReturn(List.of(sold));
        when(vehicles.findAllById(List.of(BUS))).thenReturn(List.of());
        when(clients.findAllById(List.of(CLIENT))).thenReturn(List.of());
        when(vehicles.findById(BUS)).thenReturn(Optional.empty());

        PlanAlertListResponse l = service().ouverts();

        assertEquals(1, l.alerts().size());
        assertNull(l.alerts().get(0).vehicleRegistration());
        assertTrue(l.alerts().get(0).sourceCompteur().contains("aucun compteur"),
                l.alerts().get(0).sourceCompteur());
    }

    // ------------------------------------------------------------------ la levée

    @Test
    void leverUnRappelExigeUneRaisonEcrite() {
        SoldPlanAlert ouvert = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(ouvert));

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().lever(ALERT_ID, new WaiveRequest("   "), jwt(), auth("gestionnaire_sav")));

        assertEquals(HttpStatus.BAD_REQUEST, err.getStatusCode());
        assertTrue(err.getReason().contains("sans écrit"), err.getReason());
        assertFalse(ouvert.isResolved());
    }

    @Test
    void leverUnRappelGardeLaRaisonLAuteurEtLHeure() {
        SoldPlanAlert ouvert = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        SoldPlan sold = forfait(SoldPlanStatus.ACTIVE, ilYa(30));
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(ouvert));
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(10120L)));
        when(clients.findById(CLIENT)).thenReturn(Optional.of(client()));

        PlanAlertDto leve = service().lever(ALERT_ID, new WaiveRequest("bus vendu à Sfax le 12/06"),
                jwt(), auth("gestionnaire_sav"));

        assertEquals("bus vendu à Sfax le 12/06", leve.waiverReason());
        assertEquals("sav@saphir.tn", leve.waivedByEmail());
        assertNotNull(leve.waivedAt());
        assertTrue(leve.waived());
        verify(audit).record(eq("WAIVE"), eq("SOLD_PLAN_ALERT"), eq(ALERT_ID), any(String.class), any(Jwt.class));
    }

    @Test
    void unCompteDeConsultationNeLevePasUnRappel() {
        when(alerts.findById(ALERT_ID))
                .thenReturn(Optional.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().lever(ALERT_ID, new WaiveRequest("à ma tête"), jwt(), auth("consultation")));

        assertEquals(HttpStatus.FORBIDDEN, err.getStatusCode());
    }

    @Test
    void uneDecisionSePrendUneFois() {
        SoldPlanAlert ferme = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        ferme.setResolved(true);
        ferme.setResolvedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(ferme));

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().lever(ALERT_ID, new WaiveRequest("déjà réglé"), jwt(), auth("admin")));

        assertEquals(HttpStatus.BAD_REQUEST, err.getStatusCode());
        assertTrue(err.getReason().contains("déjà fermé"), err.getReason());
    }

    /** Le tour automatique du rappel des paliers est coupable par sa propre propriété. */
    @Test
    void leTourAutomatiqueRespecteSaCoupure() {
        service().scheduledScan();
        verifyNoInteractions(soldPlans, alerts);

        new SoldPlanAlertService(soldPlans, alerts, vehicles, clients, audit, sorties, dossiers, true)
                .scheduledScan();
        verify(soldPlans).findByStatus(SoldPlanStatus.ACTIVE);
    }

    // ---------------------------------------- envoyer une équipe sur un rappel

    @Test
    void leRappelDitSurQuelBusEtQuelPalierLEquipePart() {
        when(alerts.findById(ALERT_ID))
                .thenReturn(Optional.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30))));

        SoldPlanAlertService.EnvoiAutorise envoi = service().rappelAEnvoyer(ALERT_ID);

        assertEquals("sp-1", envoi.soldPlanId());
        assertEquals("mps-10", envoi.planStepId());
        assertEquals("visite des 10 000 km", envoi.palierLabel());
        assertEquals(BUS, envoi.vehicleId(), "le bus du forfait, pas un bus choisi à l'écran");
        assertEquals(CLIENT, envoi.clientId());
        assertEquals("rappel signalé", envoi.messageRappel());
        assertNull(envoi.interventionIdDejaEnvoyee());
    }

    @Test
    void unRappelDejaLeveNEstPlusDonneALaSortie() {
        SoldPlanAlert leve = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        leve.setResolved(true);
        leve.setWaived(true);
        leve.setWaivedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        leve.setWaivedByEmail("sav@saphir.tn");
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(leve));

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().rappelAEnvoyer(ALERT_ID));

        assertEquals(HttpStatus.BAD_REQUEST, err.getStatusCode());
        assertTrue(err.getReason().contains("déjà fermé"), err.getReason());
        assertTrue(err.getReason().contains("question qui n'est plus posée"), err.getReason());
    }

    @Test
    void unPalierDejaFaitNERecoitPasDEquipe() {
        when(alerts.findById(ALERT_ID))
                .thenReturn(Optional.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        when(soldPlans.findById("sp-1"))
                .thenReturn(Optional.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30), "mps-10")));

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().rappelAEnvoyer(ALERT_ID));

        assertTrue(err.getReason().contains("déjà marqué fait"), err.getReason());
    }

    @Test
    void unForfaitQuiNaPlusCourtNEndortPersonne() {
        when(alerts.findById(ALERT_ID))
                .thenReturn(Optional.of(rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT)));
        when(soldPlans.findById("sp-1"))
                .thenReturn(Optional.of(forfait(SoldPlanStatus.CANCELLED, ilYa(30))));

        ResponseStatusException err = assertThrows(ResponseStatusException.class,
                () -> service().rappelAEnvoyer(ALERT_ID));

        assertTrue(err.getReason().contains("n'est plus actif"), err.getReason());
    }

    @Test
    void laPriseEnChargeNommeLaSortieSansFermerLeRappel() {
        SoldPlanAlert ouvert = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(ouvert));

        service().priseEnCharge(ALERT_ID, "itv-5", "DP-2026-002", jwt());

        assertEquals("itv-5", ouvert.getInterventionId());
        assertFalse(ouvert.isResolved(), "une équipe en route n'est pas une visite accomplie");
        verify(alerts).save(ouvert);
        verify(audit).record(eq("ENVOI"), eq("SOLD_PLAN_ALERT"), eq(ALERT_ID),
                eq("Rappel « compteur du bus atteint » pris en charge par la sortie DP-2026-002"), any());
    }

    @Test
    void laListeDonneLeNumeroDeLaSortieEnvoyeeSurLeRappel() {
        SoldPlanAlert suivi = rappelOuvert("sps-mps-10", SoldPlanAlertKind.KM_ATTEINT);
        suivi.setInterventionId("itv-5");
        Intervention envoyee = new Intervention();
        envoyee.setId("itv-5");
        envoyee.setNumber("DP-2026-002");
        when(alerts.findByResolvedFalseOrderByDetectedAtAsc()).thenReturn(List.of(suivi));
        when(soldPlans.findAllById(List.of("sp-1")))
                .thenReturn(List.of(forfait(SoldPlanStatus.ACTIVE, ilYa(30))));
        when(vehicles.findAllById(List.of(BUS))).thenReturn(List.of(bus(10120L)));
        when(clients.findAllById(List.of(CLIENT))).thenReturn(List.of(client()));
        when(vehicles.findById(BUS)).thenReturn(Optional.of(bus(10120L)));
        when(sorties.findAllById(List.of("itv-5"))).thenReturn(List.of(envoyee));

        PlanAlertDto a = service().ouverts().alerts().get(0);

        assertEquals("itv-5", a.sortieId());
        assertEquals("DP-2026-002", a.sortieNumber(), "l'atelier lit qui est envoyé, pas un identifiant");
    }
}
