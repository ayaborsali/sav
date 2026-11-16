package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SoldPlanDtos.ChargedLineDto;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanRequest;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanResponse;
import tn.higer.sav_backend.dto.SoldPlanDtos.SoldPlanSummary;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanOperation;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PlanOperationType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.SoldPlanStepStatus;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La vente d'un forfait, côté serveur.
 *
 * Trois choses se jouent ici et coûtent cher si elles ratent : le forfait doit se
 * vendre au client qui possède le bus (sinon le suivi part chez un autre et la
 * facturation avec) ; une même période ne peut pas être vendue deux fois sur le
 * même bus (sinon l'atelier doit deux fois les mêmes paliers pour une seule paye) ;
 * et chaque palier du plan doit recevoir sa ligne « à faire » à la vente (sinon
 * « 3 paliers sur 8 » se devine au lieu de se lire).
 *
 * Un forfait vendu ne change ni de plan, ni de client, ni de bus, et ne s'efface
 * pas : ces tests vérifient aussi que la correction refuse ce qu'elle ne peut pas
 * refaire, et qu'une annulation laisse les paliers déjà faits à leur place.
 */
@ExtendWith(MockitoExtension.class)
class SoldPlanServiceTest {

    @Mock private SoldPlanRepository soldPlans;
    @Mock private MaintenancePlanRepository plans;
    @Mock private VehicleRepository vehicles;
    @Mock private ClientRepository clients;
    @Mock private MaintenanceRepository maintenance;
    @Mock private AuditService audit;
    /** Le rappel des paliers : un service mocké, ses propres règles ont leur fichier de tests. */
    @Mock private SoldPlanAlertService rappels;

    private static final LocalDate DEBUT = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIN = LocalDate.of(2029, 1, 1);

    private SoldPlanService service() {
        return new SoldPlanService(soldPlans, plans, vehicles, clients, maintenance, audit, rappels, 19);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "sav@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    // ------------------------------------------------------------------ les pièces

    private static Client client() {
        Client client = new Client();
        client.setId("cli-1");
        client.setName("Sfax Transport");
        return client;
    }

    private static Vehicle bus(String clientId) {
        Vehicle bus = new Vehicle();
        bus.setId("veh-1");
        bus.setClientId(clientId);
        bus.setVin("LK1H2A2B3C4D5E6F7");
        bus.setRegistration("CS-1234-26");
        bus.setModel("Confort");
        return bus;
    }

    private static MaintenancePlanStep palier(String id, int order, String label, Long km, Integer days) {
        MaintenancePlanStep etape = new MaintenancePlanStep();
        etape.setId(id);
        etape.setStepOrder(order);
        etape.setLabel(label);
        etape.setKm(km);
        etape.setDays(days);
        return etape;
    }

    /** Le plan du catalogue : deux paliers, dont un chiffré et un qui attend toujours son prix. */
    private static MaintenancePlan planAuCatalogue() {
        MaintenancePlan plan = new MaintenancePlan();
        plan.setId("mpl-1");
        plan.setName("Forfait urbain 26 WBE");
        plan.setBusModel("Confort");
        plan.setActive(true);
        MaintenancePlanStep dixMille = palier("mps-10", 1, "visite des 10 000 km", 10000L, null);
        dixMille.setPriceHt(new BigDecimal("450"));
        MaintenancePlanStep douzeMois = palier("mps-20", 2, "révision des 12 mois", null, 365);
        dixMille.setPlan(plan);
        douzeMois.setPlan(plan);
        plan.getSteps().add(dixMille);
        plan.getSteps().add(douzeMois);
        return plan;
    }

    private static SoldPlanRequest vente(Double prix, String statut) {
        return new SoldPlanRequest("mpl-1", "cli-1", "veh-1", DEBUT, FIN, prix, statut);
    }

    /** Un forfait déjà en base, avec son suivi, pour tester les corrections. */
    private static SoldPlan forfaitEnBase(SoldPlanStatus statut, LocalDate debut, LocalDate fin) {
        MaintenancePlan plan = planAuCatalogue();
        SoldPlan sold = new SoldPlan();
        sold.setId("sp-1");
        sold.setPlan(plan);
        sold.setClientId("cli-1");
        sold.setVehicleId("veh-1");
        sold.setStartDate(debut);
        sold.setEndDate(fin);
        sold.setPriceHt(new BigDecimal("12500"));
        sold.setStatus(statut);
        sold.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        for (MaintenancePlanStep palier : plan.getSteps()) {
            SoldPlanStep ligne = new SoldPlanStep();
            ligne.setId("sps-" + palier.getId());
            ligne.setSoldPlan(sold);
            ligne.setStep(palier);
            ligne.setStatus(statut == SoldPlanStatus.ACTIVE && palier.getStepOrder() == 1
                    ? SoldPlanStepStatus.DONE : SoldPlanStepStatus.TODO);
            if (ligne.getStatus() == SoldPlanStepStatus.DONE) {
                ligne.setDoneAt(LocalDateTime.of(2026, 3, 2, 10, 0));
                ligne.setMaintenanceItemId("mt-77");
                ligne.setMileageAtService(10120L);
            }
            sold.getSteps().add(ligne);
        }
        return sold;
    }

    /** Les trois fiches que la vente consulte avant toute règle. */
    private void auCatalogue(MaintenancePlan plan, Vehicle bus) {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus));
        when(plans.findById("mpl-1")).thenReturn(Optional.of(plan));
    }

    private void aucunForfaitActif() {
        when(soldPlans.findByVehicleIdAndPlanIdAndStatus("veh-1", "mpl-1", SoldPlanStatus.ACTIVE))
                .thenReturn(List.of());
    }

    // -------------------------------------------------------------------- la vente

    @Test
    void laVenteGenereUneLigneAFaireParPalierDuPlan() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        aucunForfaitActif();

        SoldPlanResponse created = service().create(vente(12500.0, null), jwt(), true);

        assertEquals(2, created.steps().size());
        assertEquals(List.of("visite des 10 000 km", "révision des 12 mois"),
                created.steps().stream().map(s -> s.label()).toList());
        created.steps().forEach(s -> assertEquals("todo", s.status()));
        created.steps().forEach(s -> assertTrue(s.ref().startsWith("sps-"), s.ref()));
        // Le palier du plan n'est pas recopié, il est référencé : le suivi dit « visite des
        // 10 000 km » parce qu'il pointe le bon palier.
        assertEquals(List.of("mps-10", "mps-20"), created.steps().stream()
                .map(s -> s.planStepRef()).toList());
        created.steps().forEach(s -> assertNull(s.doneAt()));
        created.steps().forEach(s -> assertNull(s.maintenanceItemId()));
        created.steps().forEach(s -> assertNull(s.mileageAtService()));
        assertEquals(2, created.stepCount());
        assertEquals(0, created.doneStepCount());
    }

    @Test
    void leForfaitNaitActifEtSonIdentifiantVientDuServeur() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        aucunForfaitActif();

        service().create(vente(null, null), jwt(), true);

        ArgumentCaptor<SoldPlan> capte = ArgumentCaptor.forClass(SoldPlan.class);
        verify(soldPlans).save(capte.capture());
        SoldPlan sold = capte.getValue();
        assertTrue(sold.getId().startsWith("sp-"), sold.getId());
        assertEquals(SoldPlanStatus.ACTIVE, sold.getStatus());
        assertNotNull(sold.getCreatedAt());
        assertNull(sold.getPriceHt());
        // La vente est journalisée, avec le bus et le client nommés.
        verify(audit).record(eq("CREATE"), eq("SOLD_PLAN"), eq(sold.getId()), anyString(), any());
    }

    @Test
    void leSuiviEstRangeDansLordreDeLaGrilleDuPlan() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        // L'atelier ouvre la fiche : les lignes arrivent dans le désordre de base.
        java.util.Collections.reverse(sold.getSteps());
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        SoldPlanResponse vue = service().get("sp-1", true);

        assertEquals(List.of("mps-10", "mps-20"), vue.steps().stream().map(s -> s.planStepRef()).toList());
        assertEquals(List.of(1, 2), vue.steps().stream().map(s -> s.stepOrder()).toList());
    }

    @Test
    void unPalierSansPrixDuCatalogueResteVenduSansPrix() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        aucunForfaitActif();

        SoldPlanResponse created = service().create(vente(null, null), jwt(), true);

        // Le palier « révision des 12 mois » n'a jamais été chiffré : rien n'est inventé.
        assertNull(created.steps().get(1).priceHt());
        assertEquals(450.0, created.steps().get(0).priceHt());
    }

    // ---------------------------------------------------------- les règles de vente

    @Test
    void leBusDoitAppartenirAuClientAQuiOnVend() {
        auCatalogue(planAuCatalogue(), bus("cli-9"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("n'appartient pas"), e.getReason());
        assertTrue(e.getReason().contains("CS-1234-26"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    /**
     * Les deux gammes d'autobus n'ont pas la même grille d'entretien. Vendre du
     * Standard sur un bus Confort ferait réclamer, palier après palier, des
     * opérations que ce bus n'a jamais dû faire — et le rappel serait juste pour un
     * autre bus. Le contrôle se fait à la vente, pas six mois plus tard.
     */
    @Test
    void unForfaitNeSeVendPasSurUnBusDeLautreGamme() {
        MaintenancePlan standard = planAuCatalogue();
        standard.setBusModel("Standard");
        auCatalogue(standard, bus("cli-1"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("Standard"), e.getReason());
        assertTrue(e.getReason().contains("Confort"), e.getReason());
        assertTrue(e.getReason().contains("CS-1234-26"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void unBusSansGammeNePeutPasAcheterDeForfait() {
        Vehicle ancienModele = bus("cli-1");
        ancienModele.setModel("Higer KLQ6129G (12m Urbain)");
        auCatalogue(planAuCatalogue(), ancienModele);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("Confort ou Standard"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void unPlanSansGammeNeSeVendPas() {
        MaintenancePlan muet = planAuCatalogue();
        muet.setBusModel(null);
        auCatalogue(muet, bus("cli-1"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("ne dit pas quelle gamme"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void unBusInconnuNePeutPasRecevoirUnForfait() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(vehicles.findById("veh-1")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("bus est introuvable"), e.getReason());
    }

    @Test
    void unClientInconnuEstRefuse() {
        when(clients.findById("cli-1")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("client est introuvable"), e.getReason());
    }

    @Test
    void unPlanInconnuEstRefuse() {
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus("cli-1")));
        when(plans.findById("mpl-1")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("catalogue"), e.getReason());
    }

    @Test
    void unPlanSansPalierNeSeVendPas() {
        MaintenancePlan vide = planAuCatalogue();
        vide.getSteps().clear();
        auCatalogue(vide, bus("cli-1"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("aucun palier"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void unPlanRetireDuCatalogueNeSeVendPlusMaisResteLisible() {
        MaintenancePlan retire = planAuCatalogue();
        retire.setActive(false);
        auCatalogue(retire, bus("cli-1"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertTrue(e.getReason().contains("retiré du catalogue"), e.getReason());
    }

    @Test
    void ilFautUnBusUnClientUnPlanEtUneDateDeDebut() {
        ResponseStatusException sansBus = assertThrows(ResponseStatusException.class,
                () -> service().create(new SoldPlanRequest("mpl-1", "cli-1", "  ", DEBUT, FIN, null, null),
                        jwt(), true));
        assertTrue(sansBus.getReason().contains("le bus"), sansBus.getReason());

        ResponseStatusException sansDate = assertThrows(ResponseStatusException.class,
                () -> service().create(new SoldPlanRequest("mpl-1", "cli-1", "veh-1", null, null, null, null),
                        jwt(), true));
        assertTrue(sansDate.getReason().contains("date de début"), sansDate.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void uneFinAvantLeDebutEstRefusee() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(new SoldPlanRequest("mpl-1", "cli-1", "veh-1", DEBUT,
                        DEBUT.minusDays(1), null, null), jwt(), true));

        assertTrue(e.getReason().contains("avant son début"), e.getReason());
    }

    @Test
    void deuxForfaitsActifsSurLaMemePeriodeSontRefuses() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        when(soldPlans.findByVehicleIdAndPlanIdAndStatus("veh-1", "mpl-1", SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfaitEnBase(SoldPlanStatus.ACTIVE,
                        LocalDate.of(2026, 6, 1), LocalDate.of(2027, 6, 1))));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("déjà un forfait actif"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    /** Le jour commun aux deux forfaits compte comme un chevauchement : deux paliers pourraient courir le même jour. */
    @Test
    void leJourOuUnForfaitFinitEtOuLeSuivantCommenceSeRefuse() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        when(soldPlans.findByVehicleIdAndPlanIdAndStatus("veh-1", "mpl-1", SoldPlanStatus.ACTIVE))
                .thenReturn(List.of(forfaitEnBase(SoldPlanStatus.ACTIVE,
                        LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1))));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, null), jwt(), true));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("change les dates"), e.getReason());
    }

    @Test
    void reprendreUnForfaitTermineLaisseLaPeriodeLibre() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        // La requête ne cherche que les forfaits ACTIFS : un forfait clos n'est pas
        // revenu dans la course, la période peut être revendue.
        aucunForfaitActif();

        assertDoesNotThrow(() -> service().create(vente(null, null), jwt(), true));
    }

    @Test
    void lePrixAPlusDeDeuxDecimalesEstRefuse() {
        auCatalogue(planAuCatalogue(), bus("cli-1"));
        aucunForfaitActif();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(12500.007, null), jwt(), true));

        assertTrue(e.getReason().contains("deux décimales"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void unStatutInconnuEstRefuseParUnePhraseEtPasParJackson() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, "fermé"), jwt(), true));

        assertTrue(e.getReason().contains("n'est pas un statut de forfait"), e.getReason());
        assertTrue(e.getReason().contains("actif, terminé ou annulé"), e.getReason());
    }

    @Test
    void onNeVendPasUnForfaitDejaAnnule() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(vente(null, "cancelled"), jwt(), true));

        assertTrue(e.getReason().contains("nait actif"), e.getReason());
    }

    // ---------------------------------------------------------------- la correction

    @Test
    void lesDatesLePrixSeCorrigent() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus("cli-1")));

        SoldPlanResponse after = service().update("sp-1", new SoldPlanRequest(null, null, null,
                DEBUT, LocalDate.of(2030, 1, 1), 13000.0, null), jwt(), true);

        assertEquals(LocalDate.of(2030, 1, 1), after.endDate());
        assertEquals(13000.0, after.priceHt());
        assertEquals("active", after.status());
        verify(audit).record(eq("UPDATE"), eq("SOLD_PLAN"), eq("sp-1"), anyString(), any());
    }

    @Test
    void onNeChangePasLeBusDunForfaitVendu() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("sp-1", new SoldPlanRequest(null, null, "veh-2", DEBUT, FIN, null, null),
                        jwt(), true));

        assertTrue(e.getReason().contains("le bus"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void onNeChangePasLePlanNiLeClient() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        ResponseStatusException plan = assertThrows(ResponseStatusException.class,
                () -> service().update("sp-1", new SoldPlanRequest("mpl-2", "cli-1", "veh-1", DEBUT, FIN, null, null),
                        jwt(), true));
        assertTrue(plan.getReason().contains("le plan"), plan.getReason());

        ResponseStatusException client = assertThrows(ResponseStatusException.class,
                () -> service().update("sp-1", new SoldPlanRequest("mpl-1", "cli-8", "veh-1", DEBUT, FIN, null, null),
                        jwt(), true));
        assertTrue(client.getReason().contains("le client"), client.getReason());
    }

    @Test
    void unForfaitActifSeCLOTUREEnTermine() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        SoldPlanResponse after = service().update("sp-1", new SoldPlanRequest(null, null, null,
                DEBUT, FIN, null, "completed"), jwt(), true);

        assertEquals(SoldPlanStatus.COMPLETED, sold.getStatus());
        assertEquals("completed", after.status());
        // Clôturer ne touche pas au suivi : le palier fait le reste, les autres attendent.
        assertEquals(1, after.doneStepCount());
        assertEquals(2, after.stepCount());
    }

    @Test
    void unForfaitFermeNeSeRouvrePas() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.CANCELLED, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("sp-1", new SoldPlanRequest(null, null, null, DEBUT, FIN, null, "active"),
                        jwt(), true));

        assertTrue(e.getReason().contains("déjà annulé"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void uneFinAvantLeDebutRefuseLaCorrection() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("sp-1", new SoldPlanRequest(null, null, null, FIN, DEBUT, null, null),
                        jwt(), true));

        assertTrue(e.getReason().contains("avant son début"), e.getReason());
    }

    @Test
    void forfaitIntrouvable() {
        when(soldPlans.findById("sp-x")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().get("sp-x", true));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ------------------------------------------------------------------ l'annulation

    @Test
    void annulerEstUnStatutPasUneSuppression() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        service().cancel("sp-1", jwt());

        assertEquals(SoldPlanStatus.CANCELLED, sold.getStatus());
        verify(soldPlans, never()).delete(any());
        verify(soldPlans).save(sold);
        // Le palier déjà fait garde sa date, son compteur et son document.
        SoldPlanStep fait = sold.getSteps().get(0);
        assertEquals(SoldPlanStepStatus.DONE, fait.getStatus());
        assertEquals(10120L, fait.getMileageAtService());
        assertEquals("mt-77", fait.getMaintenanceItemId());
        verify(audit).record(eq("CANCEL"), eq("SOLD_PLAN"), eq("sp-1"), anyString(), any());
    }

    @Test
    void onNannulePasDeuxFois() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.CANCELLED, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service().cancel("sp-1", jwt()));

        assertTrue(e.getReason().contains("rien à annuler"), e.getReason());
        verify(soldPlans, never()).save(any());
    }

    @Test
    void unForfaitTermineNeSannulePas() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.COMPLETED, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service().cancel("sp-1", jwt()));

        assertTrue(e.getReason().contains("déjà terminé"), e.getReason());
    }

    // ---------------------------------------------------------------------- la liste

    @Test
    void laListeFiltreParBusEtDonneLesNomsSansDoublonnerLesRequetes() {
        SoldPlan auBus = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        SoldPlan ailleurs = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        ailleurs.setId("sp-2");
        ailleurs.setVehicleId("veh-9");
        ailleurs.setClientId("cli-9");
        when(soldPlans.findAllByOrderByStartDateDesc()).thenReturn(List.of(auBus, ailleurs));
        when(clients.findAllById(any())).thenReturn(List.of(client()));
        when(vehicles.findAllById(any())).thenReturn(List.of(bus("cli-1")));

        List<SoldPlanSummary> rows = service().list("veh-1", null, null, true);

        assertEquals(1, rows.size());
        assertEquals("sp-1", rows.get(0).id());
        assertEquals("Sfax Transport", rows.get(0).clientName());
        assertEquals("CS-1234-26", rows.get(0).vehicleRegistration());
        assertEquals("Forfait urbain 26 WBE", rows.get(0).planName());
        assertEquals(1, rows.get(0).doneStepCount());
        // Deux requêtes pour les noms, pas une par ligne.
        verify(clients).findAllById(any());
        verify(vehicles).findAllById(any());
    }

    @Test
    void laListeFiltreParStatutEnMinuscules() {
        SoldPlan actif = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        SoldPlan annule = forfaitEnBase(SoldPlanStatus.CANCELLED, DEBUT, FIN);
        annule.setId("sp-2");
        when(soldPlans.findAllByOrderByStartDateDesc()).thenReturn(List.of(actif, annule));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(vehicles.findAllById(any())).thenReturn(List.of());

        assertEquals(1, service().list(null, null, "cancelled", true).size());
        assertEquals(2, service().list(null, null, null, true).size());
        assertEquals(1, service().list(null, "cli-1", "active", true).size());
    }

    @Test
    void laListeDonneLeProchainPalierAParirDeLOrdreDeLaGrille() {
        // Le fixture a deux paliers : celui des 10 000 km fait, celui des 12 mois à faire.
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findAllByOrderByStartDateDesc()).thenReturn(List.of(sold));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(vehicles.findAllById(any())).thenReturn(List.of());

        SoldPlanSummary row = service().list("veh-1", null, null, true).get(0);

        assertEquals("révision des 12 mois", row.nextStepLabel());
        // Le prochain palier se lit « 365 jours » : il n'a pas de borne en kilomètres.
        assertNull(row.nextStepKm());
        assertEquals(365, row.nextStepDays());
    }

    @Test
    void laListeDitQuePlusRienNEstAFaireQuandLesPaliersSontTousFaits() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        // Le second palier vient d'être fait à son tour : le forfait n'a plus de suite.
        SoldPlanStep aFaire = sold.getSteps().stream()
                .filter(s -> s.getStatus() == SoldPlanStepStatus.TODO)
                .findFirst().orElseThrow();
        aFaire.setStatus(SoldPlanStepStatus.DONE);
        when(soldPlans.findAllByOrderByStartDateDesc()).thenReturn(List.of(sold));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(vehicles.findAllById(any())).thenReturn(List.of());

        SoldPlanSummary row = service().list("veh-1", null, null, true).get(0);

        // Rien, et pas « 0 km » ni un libellé vide : l'écran doit pouvoir l'écrire en clair.
        assertNull(row.nextStepLabel());
        assertNull(row.nextStepKm());
        assertNull(row.nextStepDays());
        assertEquals(2, row.doneStepCount());
    }

    @Test
    void leCompteSansDroitAuxChiffresNeVoitNiLePrixDuForfaitNiCeluiDesPaliers() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        SoldPlanResponse masque = service().get("sp-1", false);
        SoldPlanResponse visible = service().get("sp-1", true);

        assertNull(masque.priceHt());
        masque.steps().forEach(s -> assertNull(s.priceHt()));
        assertEquals(12500.0, visible.priceHt());
        assertEquals(450.0, visible.steps().get(0).priceHt());
        // Le travail et le suivi restent lisibles : seul l'argent disparaît du JSON.
        assertEquals("visite des 10 000 km", masque.steps().get(0).label());
        assertEquals(10000L, masque.steps().get(0).km());
        assertEquals(1, masque.doneStepCount());
        assertEquals("mt-77", masque.steps().get(0).maintenanceItemId());
        // La fiche complète annonce le même prochain palier que la ligne de liste :
        // un écran qui change d'avis selon l'URL appelée se contredit lui-même.
        assertEquals("révision des 12 mois", visible.nextStepLabel());
        assertEquals(visible.nextStepLabel(), masque.nextStepLabel());
    }

    @Test
    void laListeMasqueAussiLesPrix() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findAllByOrderByStartDateDesc()).thenReturn(List.of(sold));
        when(clients.findAllById(any())).thenReturn(List.of());
        when(vehicles.findAllById(any())).thenReturn(List.of());

        SoldPlanSummary row = service().list(null, null, null, false).get(0);

        assertNull(row.priceHt());
        assertEquals("Forfait urbain 26 WBE", row.planName());
    }

    // ----------------------------------------------------------------- les dates

    @Test
    void lesPeriodesSeSuiventSansSeChevaucher() {
        assertFalse(SoldPlanService.overlaps(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 1, 1), null));
        assertTrue(SoldPlanService.overlaps(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 12, 31), null));
    }

    @Test
    void unForfaitSansFinOccupeToutCeQuiSuit() {
        assertTrue(SoldPlanService.overlaps(LocalDate.of(2026, 1, 1), null, LocalDate.of(2029, 6, 1),
                LocalDate.of(2030, 6, 1)));
        assertTrue(SoldPlanService.overlaps(LocalDate.of(2029, 6, 1), LocalDate.of(2030, 6, 1),
                LocalDate.of(2026, 1, 1), null));
        assertFalse(SoldPlanService.overlaps(LocalDate.of(2029, 6, 1), LocalDate.of(2030, 6, 1),
                LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1)));
    }

    // ---------------------------------------------------- les lignes à charger

    /** Une ligne de la grille : la quantité est le texte du document Higer, unité comprise. */
    private static MaintenancePlanOperation operation(String id, PlanOperationType type, String organ,
                                                      String article, String reference,
                                                      String quantite, String prixHt) {
        MaintenancePlanOperation op = new MaintenancePlanOperation();
        op.setId(id);
        op.setOperationType(type);
        op.setOrgan(organ);
        op.setArticle(article);
        op.setReference(reference);
        op.setQuantityText(quantite);
        op.setUnitPriceHt(prixHt == null ? null : new BigDecimal(prixHt));
        return op;
    }

    private static MaintenancePlanStep palierDuPlan(MaintenancePlan plan, String id) {
        return plan.getSteps().stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Le plan avec sa grille : trois opérations au catalogue, deux croix au premier
     * palier (volontairement dans le désordre) et une au second, sans prix.
     */
    private static MaintenancePlan planAvecGrille() {
        MaintenancePlan plan = planAuCatalogue();
        MaintenancePlanOperation huile = operation("mop-1", PlanOperationType.REPLACE,
                "moteur", "Filtre à huile", "HGR-FLT-OIL", "1", "110");
        MaintenancePlanOperation volume = operation("mop-2", PlanOperationType.REPLACE,
                "moteur", "Huile 15W-40", "15W-40", "23 L", "12");
        MaintenancePlanOperation plaquettes = operation("mop-3", PlanOperationType.INSPECT,
                "freins", "Usure des plaquettes", "MO-FREIN", "5-6 mm", null);
        plan.getOperations().add(huile);
        plan.getOperations().add(volume);
        plan.getOperations().add(plaquettes);
        palierDuPlan(plan, "mps-10").getOperations().add(volume);
        palierDuPlan(plan, "mps-10").getOperations().add(huile);
        palierDuPlan(plan, "mps-20").getOperations().add(plaquettes);
        return plan;
    }

    /** Le forfait vendu sur le plan grille, avec son suivi. */
    private SoldPlan forfaitAvecGrille() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        MaintenancePlan grille = planAvecGrille();
        sold.setPlan(grille);
        sold.getSteps().forEach(ligne -> ligne.setStep(palierDuPlan(grille, ligne.getStep().getId())));
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));
        return sold;
    }

    @Test
    void lesLignesDuPalierSuiventLordreDuPlanEtNonCeluiDeLaGrille() {
        forfaitAvecGrille();

        List<ChargedLineDto> lignes = service().lignesACharger("sp-1", "mps-10", true);

        // La croix a été posée dans le désordre ; le document doit se lire dans l'ordre
        // du classeur Higer : filtre d'abord, puis l'huile.
        assertEquals(List.of("remplacer — Filtre à huile", "remplacer — Huile 15W-40"),
                lignes.stream().map(ChargedLineDto::label).toList());
        assertEquals(List.of("HGR-FLT-OIL", "15W-40"), lignes.stream().map(ChargedLineDto::reference).toList());
        assertEquals(List.of(1.0, 23.0), lignes.stream().map(ChargedLineDto::quantity).toList());
        assertEquals(List.of(110.0, 12.0), lignes.stream().map(ChargedLineDto::unitPrice).toList());
        assertEquals(List.of("1", "23 L"), lignes.stream().map(ChargedLineDto::quantityText).toList());
        lignes.forEach(l -> assertFalse(l.priceMissing(), l.label()));
    }

    @Test
    void unPalierSansPrixEstSignaleEtResteChargeable() {
        forfaitAvecGrille();

        List<ChargedLineDto> lignes = service().lignesACharger("sp-1", "mps-20", true);

        assertEquals(1, lignes.size());
        assertEquals("contrôler — Usure des plaquettes", lignes.get(0).label());
        assertTrue(lignes.get(0).priceMissing());
        assertNull(lignes.get(0).unitPrice());
        // « 5-6 mm » n'est pas un nombre : la quantité du total reste vide, le texte garde
        // ce que l'atelier doit lire.
        assertNull(lignes.get(0).quantity());
        assertEquals("5-6 mm", lignes.get(0).quantityText());
    }

    @Test
    void unCompteSansDroitAuxChiffresNeVoitNiPrixNiFausseAlerte() {
        forfaitAvecGrille();

        List<ChargedLineDto> lignes = service().lignesACharger("sp-1", "mps-10", false);

        lignes.forEach(l -> assertNull(l.unitPrice()));
        // Le rôle consultation ne chiffrera pas : « prix à définir » est ce qu'il peut lire.
        lignes.forEach(l -> assertTrue(l.priceMissing()));
        assertEquals(List.of(1.0, 23.0), lignes.stream().map(ChargedLineDto::quantity).toList());
    }

    @Test
    void unPalierQuiNestPasDansCeForfaitNeSeChargePas() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().lignesACharger("sp-1", "mps-99", true));
        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("n'appartient pas au forfait"), refuse.getReason());
    }

    @Test
    void unForfaitInconnuNeRendAucuneLigne() {
        when(soldPlans.findById("sp-0")).thenReturn(Optional.empty());

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().lignesACharger("sp-0", "mps-10", true));
        assertEquals(HttpStatus.NOT_FOUND, refuse.getStatusCode());
    }

    @Test
    void uneSeuleReglePourLireUneQuantite() {
        assertEquals(23.0, SoldPlanService.quantite("23 L"));
        assertEquals(0.5, SoldPlanService.quantite("0,5 L"));
        assertEquals(6.5, SoldPlanService.quantite("6.5 kg"));
        assertEquals(2.0, SoldPlanService.quantite("2"));
        // Rien à deviner : pas de nombre, deux nombres, ou pas de case du tout.
        assertNull(SoldPlanService.quantite("à discrétion"));
        assertNull(SoldPlanService.quantite("5-6 mm"));
        assertNull(SoldPlanService.quantite(""));
        assertNull(SoldPlanService.quantite(null));
    }

    // --------------------------------------------- le document fait avancer le palier

    private static MaintenanceItem document(String id, String numero, MaintenanceStatus statut,
                                            String soldPlanId, String planStepId) {
        MaintenanceItem m = new MaintenanceItem();
        m.setId(id);
        m.setNumber(numero);
        m.setType(MaintenanceType.BL);
        m.setVehicleId("veh-1");
        m.setStatus(statut);
        m.setSoldPlanId(soldPlanId);
        m.setPlanStepId(planStepId);
        return m;
    }

    @Test
    void unDocumentHorsForfaitNeBougeRienNullePart() {
        MaintenanceItem sansForfait = document("mt-1", "BL-1", MaintenanceStatus.DELIVERED, null, null);

        service().verifierRattachement(sansForfait);
        service().appliquerSuivi(sansForfait, null, null, jwt());

        verifyNoInteractions(soldPlans, maintenance, audit);
    }

    @Test
    void unPalierSeChoisitDansUnForfait() {
        MaintenanceItem orphelin = document("mt-1", "BL-1", MaintenanceStatus.DRAFT, null, "mps-10");

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().verifierRattachement(orphelin));
        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("se choisit dans un forfait"), refuse.getReason());
    }

    @Test
    void leBusDuDocumentDoitEtreCeluiDuForfait() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus("cli-1")));
        MaintenanceItem autreBus = document("mt-1", "BL-1", MaintenanceStatus.DRAFT, "sp-1", "mps-20");
        autreBus.setVehicleId("veh-2");

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().verifierRattachement(autreBus));
        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        // Le matricule, pas l'identifiant : c'est ce que l'atelier lit sur le bus.
        assertTrue(refuse.getReason().contains("CS-1234-26"), refuse.getReason());
    }

    @Test
    void unForfaitAnnuleNeRecoitPlusDeDocument() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.CANCELLED, DEBUT, FIN)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().verifierRattachement(
                        document("mt-1", "BL-1", MaintenanceStatus.DRAFT, "sp-1", "mps-20")));
        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("annulé"), refuse.getReason());
    }

    @Test
    void unPalierDejaFaitParUnAutreDocumentEstRefuse() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        when(maintenance.findById("mt-77")).thenReturn(Optional.of(
                document("mt-77", "BL-2026-0087", MaintenanceStatus.DELIVERED, "sp-1", "mps-10")));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().verifierRattachement(
                        document("mt-2", "BL-2", MaintenanceStatus.DRAFT, "sp-1", "mps-10")));
        assertEquals(HttpStatus.CONFLICT, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("BL-2026-0087"), refuse.getReason());
        assertTrue(refuse.getReason().contains("visite des 10 000 km"), refuse.getReason());
    }

    /**
     * La règle, corrigée le 02/11 par le porteur du portail : le forfait ne vend que la
     * <b>main-d'œuvre</b>. Ce que l'article 5 exclut — la pièce fournie par Saphir, le
     * consommable, le déplacement, le travail correctif, la prestation en plus — se
     * chiffre au client, donc un devis <b>peut nommer le palier</b> dont il facture ce
     * supplément. Et il le nomme même quand la visite est déjà faite par un autre
     * papier : le devis ne réclame pas le palier, il ne le décompte pas — la preuve est
     * qu'aucun document voisin n'est consulté pour le décider.
     */
    @Test
    void unDevisPeutNommerUnPalierSansLeReclamer() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        MaintenanceItem devis = document("mt-3", "DV-2026-0003", MaintenanceStatus.DRAFT, "sp-1", "mps-10");
        devis.setType(MaintenanceType.DEVIS);

        service().verifierRattachement(devis);

        verify(maintenance, never()).findById(any());
    }

    /**
     * Le devis ne décompte pas le palier : passé à « livré », il ne touche aucun suivi.
     * Sans cette garde, facturer une pièce de la visite des 10 000 km éteindrait le
     * rappel d'une visite que personne n'a encore faite.
     */
    @Test
    void livrerUnDevisNeCochePasLePalier() {
        MaintenanceItem devis = document("mt-3", "DV-2026-0003", MaintenanceStatus.DELIVERED, "sp-1", "mps-20");
        devis.setType(MaintenanceType.DEVIS);

        service().appliquerSuivi(devis, null, null, jwt());

        verifyNoInteractions(soldPlans, maintenance, audit, rappels);
    }

    /** Le palier se nomme aussi depuis un devis, mais il doit rester un palier DE CE forfait. */
    @Test
    void unDevisNePeutPasNommerLePalier_dUnAutreForfait() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        MaintenanceItem devis = document("mt-3", "DV-2026-0004", MaintenanceStatus.DRAFT, "sp-1", "mps-inconnu");
        devis.setType(MaintenanceType.DEVIS);

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().verifierRattachement(devis));

        assertTrue(refuse.getReason().contains("n'appartient pas au forfait"), refuse.getReason());
    }

    /** L'ordre de travail, lui, réclame le palier : deux papiers pour une même visite se refusent encore. */
    @Test
    void unOrdreQuiReclameUnPalierDejaFaitResteRefuse() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        when(maintenance.findById("mt-77")).thenReturn(Optional.of(
                document("mt-77", "OR-2026-0087", MaintenanceStatus.DELIVERED, "sp-1", "mps-10")));
        MaintenanceItem ordre = document("mt-4", "OR-2026-0004", MaintenanceStatus.DRAFT, "sp-1", "mps-10");
        ordre.setType(MaintenanceType.OPERATION);

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().verifierRattachement(ordre));

        assertEquals(HttpStatus.CONFLICT, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("OR-2026-0087"), refuse.getReason());
    }

    @Test
    void unPalierFaitParUnDocumentSupprimePeutEtreRepris() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        when(maintenance.findById("mt-77")).thenReturn(Optional.empty());

        service().verifierRattachement(document("mt-2", "BL-2", MaintenanceStatus.DRAFT, "sp-1", "mps-10"));
    }

    @Test
    void unPalierFaitParUnDocumentAnnulePeutEtreRepris() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN)));
        when(maintenance.findById("mt-77")).thenReturn(Optional.of(
                document("mt-77", "BL-2026-0087", MaintenanceStatus.CANCELLED, "sp-1", "mps-10")));

        service().verifierRattachement(document("mt-2", "BL-2", MaintenanceStatus.DRAFT, "sp-1", "mps-10"));
    }

    @Test
    void livrerLeDocumentPasseLePalierAFait() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));
        MaintenanceItem bl = document("mt-50", "BL-2026-0100", MaintenanceStatus.DELIVERED, "sp-1", "mps-20");

        service().appliquerSuivi(bl, null, null, jwt());

        SoldPlanStep suivi = sold.getSteps().stream()
                .filter(s -> s.getStep().getId().equals("mps-20")).findFirst().orElseThrow();
        assertEquals(SoldPlanStepStatus.DONE, suivi.getStatus());
        assertEquals("mt-50", suivi.getMaintenanceItemId());
        assertNotNull(suivi.getDoneAt());
        verify(audit).record(eq("PALIER_FAIT"), eq("SOLD_PLAN_STEP"), eq(suivi.getId()), anyString(), any());
    }

    @Test
    void terminerLeDocumentPasseAussiLePalierAFait() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        service().appliquerSuivi(document("mt-50", "OR-1", MaintenanceStatus.COMPLETED, "sp-1", "mps-20"),
                null, null, jwt());

        SoldPlanStep suivi = sold.getSteps().stream()
                .filter(s -> s.getStep().getId().equals("mps-20")).findFirst().orElseThrow();
        assertEquals(SoldPlanStepStatus.DONE, suivi.getStatus());
    }

    /** Le palier est déjà fait par ce document : le second enregistrement ne doit pas le recompter. */
    @Test
    void relivrerLeMemeDocumentNeDeplaceRien() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));
        MaintenanceItem bl = document("mt-77", "BL-2026-0087", MaintenanceStatus.DELIVERED, "sp-1", "mps-10");

        service().appliquerSuivi(bl, "sp-1", "mps-10", jwt());

        assertEquals(LocalDateTime.of(2026, 3, 2, 10, 0), sold.getSteps().get(0).getDoneAt());
        verifyNoInteractions(audit);
    }

    @Test
    void unDocumentBrouillonNeFaitPasPasserLePalierAFait() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        service().appliquerSuivi(document("mt-51", "DEV-9", MaintenanceStatus.DRAFT, "sp-1", "mps-20"),
                null, null, jwt());

        assertEquals(SoldPlanStepStatus.TODO, sold.getSteps().get(1).getStatus());
        verify(soldPlans, never()).save(any());
    }

    /**
     * Le point financer du module : annuler le BL rend le palier à faire — sinon
     * l'atelier aurait une visite de moins à passer chez un client qui n'en a pas
     * payé deux. Le compteur relevé reste : il décrit le bus, pas le papier.
     */
    @Test
    void annulerLeDocumentRemetLePalierAFaireEtGardeLeCompteur() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        service().appliquerSuivi(document("mt-77", "BL-2026-0087", MaintenanceStatus.CANCELLED,
                "sp-1", "mps-10"), "sp-1", "mps-10", jwt());

        SoldPlanStep suivi = sold.getSteps().get(0);
        assertEquals(SoldPlanStepStatus.TODO, suivi.getStatus());
        assertNull(suivi.getDoneAt());
        assertNull(suivi.getMaintenanceItemId());
        assertEquals(10120L, suivi.getMileageAtService());
        verify(audit).record(eq("PALIER_LIBRE"), eq("SOLD_PLAN_STEP"), eq(suivi.getId()), anyString(), any());
    }

    @Test
    void changerDePalierLibereLancien() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));
        MaintenanceItem bl = document("mt-77", "BL-2026-0087", MaintenanceStatus.DRAFT, "sp-1", "mps-20");

        service().appliquerSuivi(bl, "sp-1", "mps-10", jwt());

        assertEquals(SoldPlanStepStatus.TODO, sold.getSteps().get(0).getStatus());
        assertEquals(SoldPlanStepStatus.TODO, sold.getSteps().get(1).getStatus());
    }

    @Test
    void supprimerLeDocumentLibereLePalier() {
        SoldPlan sold = forfaitEnBase(SoldPlanStatus.ACTIVE, DEBUT, FIN);
        when(soldPlans.findById("sp-1")).thenReturn(Optional.of(sold));

        service().relacherLiens(document("mt-77", "BL-2026-0087", MaintenanceStatus.DELIVERED,
                "sp-1", "mps-10"), jwt());

        assertEquals(SoldPlanStepStatus.TODO, sold.getSteps().get(0).getStatus());
    }

    /**
     * Un document qui n'a plus de forfait en base (le service est appelé après une
     * suppression concurrente) ne doit pas faire échouer l'écriture du BL : le suivi
     * se relâche s'il existe, sinon on passe.
     */
    @Test
    void unForfaitDisparuNeBloquePasLaSuppressionDuDocument() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.empty());

        service().relacherLiens(document("mt-77", "BL-1", MaintenanceStatus.DELIVERED, "sp-1", "mps-10"), jwt());

        verify(soldPlans, never()).save(any());
    }

    // -------------------------------------- la sortie d'entretien fait le palier

    /** La sortie qui rentre : son bus, son palier, le compteur relu dessus. */
    private static SoldPlanService.SortiePourPalier sortieQuiRentre(String palier, Long compteur) {
        return new SoldPlanService.SortiePourPalier("sp-1", palier, "veh-1", "cli-1",
                "DP-2026-004", "Ali Trabelsi", compteur);
    }

    @Test
    void laSortieDEntretienCreeUnOrdreDeReparationInclusAuForfait() {
        SoldPlan sold = forfaitAvecGrille();
        when(maintenance.findNumbersByPrefix(anyString())).thenReturn(List.of());

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(sortieQuiRentre("mps-20", 61230L), jwt());

        ArgumentCaptor<MaintenanceItem> capte = ArgumentCaptor.forClass(MaintenanceItem.class);
        verify(maintenance).save(capte.capture());
        MaintenanceItem m = capte.getValue();
        assertEquals(MaintenanceType.OPERATION, m.getType());
        assertEquals(MaintenanceStatus.DELIVERED, m.getStatus(), "livré : c'est ce statut qui coche un palier");
        assertTrue(m.getNumber().startsWith("OR-" + LocalDate.now().getYear() + "-"), m.getNumber());
        assertEquals("Ali Trabelsi", m.getTechnician());
        assertEquals("sp-1", m.getSoldPlanId());
        assertEquals("mps-20", m.getPlanStepId());
        assertTrue(m.getDescription().contains("DP-2026-004"), m.getDescription());
        assertTrue(m.getDescription().contains("incluse au forfait"), m.getDescription());
        // Le client a payé le forfait d'avance : le papier porte les opérations, pas un prix.
        assertEquals(1, m.getDetails().size());
        assertEquals(0.0, m.getDetails().get(0).getUnitPrice());
        assertNull(m.getTotalHT(), "un ordre de travail ne chiffre pas : il acquitte un palier");
        assertNull(m.getTotalTTC(), "et le forfait est déjà payé d'avance, en une fois");

        SoldPlanStep suivi = sold.getSteps().get(1);
        assertEquals(SoldPlanStepStatus.DONE, suivi.getStatus());
        assertEquals(m.getId(), suivi.getMaintenanceItemId());
        assertEquals(61230L, suivi.getMileageAtService(), "le compteur relu dehors reste sur la ligne du palier");
        assertEquals(m.getId(), papier.documentId());
        assertTrue(papier.note().contains("coché par l'opération " + m.getNumber()), papier.note());
        verify(rappels).palierFait(suivi.getId());
    }

    @Test
    void leNumeroDeLOperationSuitLaSuiteEtSautCeQuiEstDejaTape() {
        forfaitAvecGrille();
        String prefix = "OR-" + LocalDate.now().getYear() + "-";
        when(maintenance.findNumbersByPrefix(anyString())).thenReturn(List.of(prefix + "007"));
        // Un OR-…-008 saisi à la main depuis l'écran Maintenance : le papier créé par la
        // sortie prend le suivant libre, jamais une écriture qui échouerait sur l'unicité.
        when(maintenance.existsByNumberIgnoreCase(prefix + "008")).thenReturn(true);

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(sortieQuiRentre("mps-20", null), jwt());

        assertEquals(prefix + "009", papier.documentNumber());
    }

    @Test
    void unPalierDejaFaitParUnDocumentNeRecreePasUneOperation() {
        SoldPlan sold = forfaitAvecGrille();
        when(maintenance.findById("mt-77")).thenReturn(Optional.of(
                document("mt-77", "BL-2026-0087", MaintenanceStatus.DELIVERED, "sp-1", "mps-10")));

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(sortieQuiRentre("mps-10", null), jwt());

        assertNull(papier.documentId());
        assertTrue(papier.note().contains("BL-2026-0087"), papier.note());
        assertTrue(papier.note().contains("une même visite ne se paie pas deux fois"), papier.note());
        assertEquals(SoldPlanStepStatus.DONE, sold.getSteps().get(0).getStatus());
        verify(maintenance, never()).save(any());
    }

    @Test
    void unForfaitAnnuleNeSeCochePasParUneSortie() {
        when(soldPlans.findById("sp-1"))
                .thenReturn(Optional.of(forfaitEnBase(SoldPlanStatus.CANCELLED, DEBUT, FIN)));

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(sortieQuiRentre("mps-20", null), jwt());

        assertNull(papier.documentId());
        assertTrue(papier.note().contains("est annulé"), papier.note());
        verify(maintenance, never()).save(any());
    }

    @Test
    void unPalierNeSeDecompteQueSurLeBusEngage() {
        forfaitAvecGrille();
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus("cli-1")));

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(
                new SoldPlanService.SortiePourPalier("sp-1", "mps-20", "veh-9", "cli-1",
                        "DP-2026-005", "Ali Trabelsi", 61230L), jwt());

        assertNull(papier.documentId());
        assertTrue(papier.note().contains("CS-1234-26"), papier.note());
        verify(maintenance, never()).save(any());
    }

    @Test
    void unPalierQuiAQuitteLeForfaitNeCreeRien() {
        SoldPlan sold = forfaitAvecGrille();
        sold.getSteps().remove(1);

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(sortieQuiRentre("mps-20", null), jwt());

        assertNull(papier.documentId());
        assertTrue(papier.note().contains("n'appartient plus au forfait"), papier.note());
        verify(maintenance, never()).save(any());
    }

    @Test
    void unForfaitSupprimeNEcritPasDePapier() {
        when(soldPlans.findById("sp-1")).thenReturn(Optional.empty());

        SoldPlanService.PapierDePalier papier = service().papierPourPalier(sortieQuiRentre("mps-20", null), jwt());

        assertNull(papier.documentId());
        assertTrue(papier.note().contains("n'existe plus"), papier.note());
        verify(maintenance, never()).save(any());
    }
}
