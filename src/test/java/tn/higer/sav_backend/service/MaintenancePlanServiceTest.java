package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.OperationDto;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanRequest;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanResponse;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.StepDto;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanOperation;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.SoldPlanStatus;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le catalogue des forfaits, côté serveur.
 *
 * Un plan mal formé ne se voit pas le jour où on l'écrit : il se voit six mois
 * plus tard, sur un bon de travail, quand un palier jamais atteint n'a été
 * réclamé à Higer personne et qu'une croix pointe une opération effacée. Ces
 * tests vérifient que chaque cas est refusé avec la ligne en cause, qu'un plan
 * sans prix — l'état normal aujourd'hui — passe, que les identifiants viennent
 * du serveur, et que les montants ne sortent pas vers un compte sans droit.
 */
@ExtendWith(MockitoExtension.class)
class MaintenancePlanServiceTest {

    @Mock private MaintenancePlanRepository plans;
    @Mock private SoldPlanStepRepository soldPlanSteps;
    @Mock private AuditService audit;

    private MaintenancePlanService service() {
        return new MaintenancePlanService(plans, soldPlanSteps, audit);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static OperationDto huile(String ref) {
        return new OperationDto(ref, "replace", "remplacer", "Moteur", "Vidange huile moteur",
                "15W-40", "7,5 L", 10000, null);
    }

    private static OperationDto courroie(String ref) {
        return new OperationDto(ref, "inspect", "contrôler", "Moteur", "Courroie d'accessoires",
                null, null, null, null);
    }

    private static StepDto palier(String ref, String label, Long km, Integer days, Double price,
                                  List<String> croix) {
        return new StepDto(ref, label, km, days, price, croix);
    }

    /** Un plan complet : deux paliers, deux opérations, une croix chacune. */
    private static PlanRequest plan(String nom) {
        return new PlanRequest(nom, "Confort", 80000L, 10000, 45, 3, true,
                List.of(
                        palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1")),
                        palier("P2", "révision des 12 mois", null, 365, null, List.of("O1", "O2"))),
                List.of(huile("O1"), courroie("O2")));
    }

    private static MaintenancePlan planEnBase() {
        MaintenancePlan plan = new MaintenancePlan();
        plan.setId("mpl-1");
        plan.setName("Forfait urbain 26 WBE");
        plan.setBusModel("Confort");
        plan.setActive(true);
        return plan;
    }

    // ----------------------------------------------------------------- création

    @Test
    void unPlanCompletenregistreEtLeServeurDonneLesIdentifiants() {
        PlanResponse created = service().create(plan("Forfait urbain 26 WBE"), jwt(), true);

        assertEquals("Forfait urbain 26 WBE", created.name());
        assertEquals(2, created.steps().size());
        assertEquals(2, created.operations().size());
        // Le serveur pose les ids, la ref de l'écran ne survit pas à l'aller-retour.
        created.steps().forEach(s -> assertTrue(s.ref().startsWith("mps-"), s.ref()));
        created.operations().forEach(o -> assertTrue(o.ref().startsWith("mpo-"), o.ref()));
        // La croix « O1 » du fichier est devenue l'identifiant réel de l'opération.
        String refHuile = created.operations().get(0).ref();
        assertEquals(List.of(refHuile), created.steps().get(0).operationRefs());
        assertTrue(created.steps().get(1).operationRefs().contains(refHuile));
    }

    @Test
    void unPalierSansKilometrageNiDureeEstRefuseParceQuaucunBusNeLatteint() {
        PlanRequest boiteux = new PlanRequest("Sans borne", "Confort", null, null, null, null, true,
                List.of(palier("P1", "on ne sait quand", null, null, null, List.of())),
                List.of(huile("O1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("on ne sait quand"), e.getReason());
        assertTrue(e.getReason().contains("aucun bus ne peut l'atteindre"), e.getReason());
        verify(plans, never()).save(any());
    }

    @Test
    void unPalierSansLibelleEstRefuse() {
        PlanRequest boiteux = new PlanRequest("Sans nom de palier", "Confort", null, null, null, null, true,
                List.of(palier("P1", "   ", 10000L, null, null, List.of())), List.of(huile("O1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("Le palier 1"), e.getReason());
    }

    @Test
    void deuxPaliersQuiSeRessemblentSontRefuses() {
        PlanRequest doublon = new PlanRequest("Deux fois la meme", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of()),
                        palier("P2", "Visite des 10000 km", 20000L, null, null, List.of())),
                List.of(huile("O1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(doublon, jwt(), true));
        assertTrue(e.getReason().contains("même libellé"), e.getReason());
    }

    /**
     * Un plan se lit à ses kilomètres : deux paliers qui ne diffèrent que par ce
     * nombre sont deux visites distinctes. Le contrôle de doublon compare sans
     * accents ni espaces, jamais en jetant les chiffres.
     */
    @Test
    void deuxPaliersQuiNeDifferentQueParLeursKilometresSontAcceptes() {
        PlanRequest progressif = new PlanRequest("Trois paliers", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of()),
                        palier("P2", "visite des 20 000 km", 20000L, null, null, List.of()),
                        palier("P3", "visite des 30 000 km", 30000L, null, null, List.of())),
                List.of(huile("O1")));

        PlanResponse created = service().create(progressif, jwt(), true);

        assertEquals(3, created.steps().size());
        assertEquals(10000L, created.steps().get(0).km());
        assertEquals(30000L, created.steps().get(2).km());
    }

    @Test
    void uneCroixQuiPointeUneOperationInconnueEstRefusee() {
        PlanRequest boiteux = new PlanRequest("Croix orpheline", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O9"))),
                List.of(huile("O1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("O9"), e.getReason());
        assertTrue(e.getReason().contains("opération inconnue"), e.getReason());
    }

    @Test
    void uneActionQuiNestPasUneDesCinqEstRefuseeEnListantLesCinq() {
        OperationDto change = new OperationDto("O1", "changer", null, "Moteur", "Vidange",
                null, null, null, null);
        PlanRequest boiteux = new PlanRequest("Action inventee", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1"))),
                List.of(change));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("ligne 1"), e.getReason());
        List<String> cinq = List.of("remplacer", "contrôler", "graisser", "nettoyer", "régler");
        cinq.forEach(mot -> assertTrue(e.getReason().contains(mot), e.getReason()));
    }

    @Test
    void uneLigneQuiNeDesigneRienEstRefusee() {
        OperationDto vide = new OperationDto("O1", "inspect", null, null, null, null, null, null, null);
        PlanRequest boiteux = new PlanRequest("Ligne vide", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1"))),
                List.of(vide));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("organe ou une désignation"), e.getReason());
    }

    @Test
    void deuxOperationsAvecLaMemeRefSontRefusees() {
        PlanRequest doublon = new PlanRequest("Refs identiques", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1"))),
                List.of(huile("O1"), courroie("O1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(doublon, jwt(), true));
        assertTrue(e.getReason().contains("O1"), e.getReason());
    }

    @Test
    void unNomVideEstRefuse() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(plan("   "), jwt(), true));
        assertTrue(e.getReason().contains("porter un nom"), e.getReason());
    }

    /**
     * La gamme du plan — Confort ou Standard — est ce que la vente compare à celle du
     * bus. Un plan muet sur sa gamme ne peut être vendu à aucun bus : la case se
     * remplit dans la fiche d'import, colonne « modèle ».
     */
    @Test
    void unPlanSansGammeEstRefuse() {
        List<StepDto> paliers = List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of()));
        List<OperationDto> operations = List.of(huile("O1"));

        ResponseStatusException muet = assertThrows(ResponseStatusException.class,
                () -> service().create(new PlanRequest("Plan sans gamme", null, null, null, null, null,
                        true, paliers, operations), jwt(), true));
        assertTrue(muet.getReason().contains("quelle gamme"), muet.getReason());

        ResponseStatusException modeleCommercial = assertThrows(ResponseStatusException.class,
                () -> service().create(new PlanRequest("Plan KLQ", "Higer KLQ6129G (12m Urbain)", null,
                        null, null, null, true, paliers, operations), jwt(), true));
        assertTrue(modeleCommercial.getReason().contains("Confort ou Standard"),
                modeleCommercial.getReason());

        verify(plans, never()).save(any());
    }

    /** Le document Higer écrit le nom de la flotte en toutes lettres ; le plan le range. */
    @Test
    void laGammeDuDocumentSeRangeSurLaValeurDuPortail() {
        PlanResponse created = service().create(new PlanRequest("Confort importé", "BUS HIGER CONFORT",
                80000L, 10000, 45, 3, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of())),
                List.of(huile("O1"))), jwt(), true);

        assertEquals("Confort", created.busModel());
    }

    @Test
    void unNomDejaPrisEstRefuse() {
        when(plans.existsByNameIgnoreCase("Forfait urbain 26 WBE")).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(plan("Forfait urbain 26 WBE"), jwt(), true));
        assertTrue(e.getReason().contains("modifie le plan existant"), e.getReason());
    }

    @Test
    void unChampNegatifEstRefuse() {
        PlanRequest boiteux = new PlanRequest("Km negatif", "Confort", -1L, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of())),
                List.of(huile("O1")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("km annuel"), e.getReason());
    }

    /** L'état normal du catalogue aujourd'hui : les prix ne sont pas encore connus. */
    @Test
    void unPlanSansPrixEnregistreEtSignaleCombienDePrixManquent() {
        service().create(plan("Forfait sans chiffrage"), jwt(), true);

        ArgumentCaptor<MaintenancePlan> captee = ArgumentCaptor.forClass(MaintenancePlan.class);
        verify(plans).save(captee.capture());
        MaintenancePlan enregistre = captee.getValue();
        assertEquals(4, nombreDePrixManquants(enregistre));
        assertTrue(enregistre.getSteps().get(0).getPriceHt() == null);
        verify(audit).record(eq("CREATE"), eq("MAINTENANCE_PLAN"), anyString(),
                contains("2 paliers, 2 opérations"), any());
    }

    private static int nombreDePrixManquants(MaintenancePlan plan) {
        int paliers = (int) plan.getSteps().stream().filter(s -> s.getPriceHt() == null).count();
        int operations = (int) plan.getOperations().stream().filter(o -> o.getUnitPriceHt() == null).count();
        return paliers + operations;
    }

    @Test
    void deuxOperationsSansRefNeSeMarchentPasDessus() {
        PlanRequest sansRefs = new PlanRequest("Sans refs", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of())),
                List.of(huile(null), courroie(null)));

        PlanResponse created = service().create(sansRefs, jwt(), true);
        assertEquals(2, created.operations().size());
        assertNotEquals(created.operations().get(0).ref(), created.operations().get(1).ref());
    }

    // ------------------------------------------------------------- modification

    @Test
    void uneOperationRetireeFaitDisparaitreSesCroix() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planEnBase()));
        PlanRequest avecUneSeuleLigne = new PlanRequest("Forfait urbain 26 WBE", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1"))),
                List.of(huile("O1")));

        PlanResponse after = service().update("mpl-1", avecUneSeuleLigne, jwt(), true);

        assertEquals(1, after.operations().size());
        assertEquals(1, after.steps().get(0).operationRefs().size());
        verify(audit).record(eq("UPDATE"), eq("MAINTENANCE_PLAN"), eq("mpl-1"), anyString(), any());
    }

    @Test
    void renommerUnPlanSurUnAutreNomEstRefuse() {
        MaintenancePlan existant = planEnBase();
        when(plans.findById("mpl-1")).thenReturn(Optional.of(existant));
        when(plans.existsByNameIgnoreCaseAndIdNot("Autre plan", "mpl-1")).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("mpl-1", plan("Autre plan"), jwt(), true));
        assertTrue(e.getReason().contains("Un autre plan"), e.getReason());
    }

    @Test
    void unPlanIntrouvableEstSignale() {
        when(plans.findById("mpl-inconnu")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("mpl-inconnu", plan("Nouveau"), jwt(), true));
        assertEquals(404, e.getStatusCode().value());
    }

    // ------------------------------------------------------------------ retrait

    @Test
    void retirerUnPlanDuCatalogueNeLeffacePas() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planEnBase()));

        service().deactivate("mpl-1", jwt());

        ArgumentCaptor<MaintenancePlan> captee = ArgumentCaptor.forClass(MaintenancePlan.class);
        verify(plans).save(captee.capture());
        assertFalse(captee.getValue().isActive());
        verify(plans, never()).delete(any());
        verify(audit).record(eq("DEACTIVATE"), eq("MAINTENANCE_PLAN"), eq("mpl-1"), anyString(), any());
    }

    /**
     * La colonne s'arrête au centime : un prix à trois décimales serait arrondi
     * par la base sans que personne ne le voie. Le refus est posé ici, avec la
     * ligne en cause, pour que l'atelier corrige sa saisie au lieu de retrouver un
     * montant différent six mois plus tard.
     */
    @Test
    void unPrixAPLusDeDeuxDecimalesEstRefuse() {
        OperationDto prixBoiteux = new OperationDto("O1", "replace", null, "Moteur", "Filtre à huile",
                null, null, null, 85.567);
        PlanRequest boiteux = new PlanRequest("Prix trop fin", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of())),
                List.of(prixBoiteux));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().create(boiteux, jwt(), true));
        assertTrue(e.getReason().contains("ligne 1"), e.getReason());
        assertTrue(e.getReason().contains("deux décimales"), e.getReason());
    }

    @Test
    void unPrixACentimesPasseTelQuel() {
        OperationDto prix = new OperationDto("O1", "replace", null, "Moteur", "Filtre à huile",
                null, null, null, 85.5);
        StepDto palierChiffre = palier("P1", "visite des 10 000 km", 10000L, null, 1250.75, List.of("O1"));
        PlanRequest demande = new PlanRequest("Forfait chiffré", "Confort", null, null, null, null, true,
                List.of(palierChiffre), List.of(prix));

        service().create(demande, jwt(), true);

        ArgumentCaptor<MaintenancePlan> captee = ArgumentCaptor.forClass(MaintenancePlan.class);
        verify(plans).save(captee.capture());
        assertEquals(new BigDecimal("85.5"), captee.getValue().getOperations().get(0).getUnitPriceHt());
        assertEquals(new BigDecimal("1250.75"), captee.getValue().getSteps().get(0).getPriceHt());
    }

    // ----------------------------------------------------------------- les prix

    /**
     * Le prix ne sort pas du serveur pour un compte qui n'a pas droit aux chiffres
     * commerciaux. Un « masqué » affiché à l'écran ne protège rien dès que la
     * requête est lue dans l'onglet réseau : c'est le JSON qui doit être vide.
     */
    @Test
    void unCompteSansDroitAuxChiffresNeRecoitAucunMontant() {
        MaintenancePlan plan = planEnBase();
        MaintenancePlanStep palier = new MaintenancePlanStep();
        palier.setId("mps-1");
        palier.setPlan(plan);
        palier.setStepOrder(1);
        palier.setLabel("visite des 10 000 km");
        palier.setKm(10000L);
        palier.setPriceHt(BigDecimal.valueOf(1250.0));
        plan.getSteps().add(palier);
        MaintenancePlanOperation operation = new MaintenancePlanOperation();
        operation.setId("mpo-1");
        operation.setPlan(plan);
        operation.setOrgan("Moteur");
        operation.setArticle("Vidange huile moteur");
        operation.setUnitPriceHt(BigDecimal.valueOf(85.5));
        palier.getOperations().add(operation);
        plan.getOperations().add(operation);
        when(plans.findById("mpl-1")).thenReturn(Optional.of(plan));

        PlanResponse masque = service().get("mpl-1", false);
        PlanResponse visible = service().get("mpl-1", true);

        assertNull(masque.steps().get(0).priceHt());
        assertNull(masque.operations().get(0).unitPriceHt());
        assertEquals(1250.0, visible.steps().get(0).priceHt());
        assertEquals(85.5, visible.operations().get(0).unitPriceHt());
        // Le travail reste lisible : seul le montant disparaît.
        assertEquals("visite des 10 000 km", masque.steps().get(0).label());
        assertEquals("Vidange huile moteur", masque.operations().get(0).article());
        assertEquals(List.of("mpo-1"), masque.steps().get(0).operationRefs());
    }

    // ------------------------------------------------- les paliers et les ventes

    /**
     * Un forfait vendu suit un palier par son identifiant (V14). Modifier la grille
     * ne doit donc pas redistribuer d'identifiants : sinon corriger « 10 000 km » en
     * « 12 000 km » laisserait le suivi de trois bus pointer sur des paliers
     * supprimés, et l'atelier ne saurait plus quel palier son client a déjà fait.
     */
    @Test
    void unPalierCorrigeGardeSonIdentifiant() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planAvecDeuxPaliers()));
        PlanRequest corrige = new PlanRequest("Forfait urbain 26 WBE", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 12000L, null, null, List.of("O1")),
                        palier("P2", "révision des 12 mois", null, 400, null, List.of("O1", "O2"))),
                List.of(huile("O1"), courroie("O2")));

        PlanResponse after = service().update("mpl-1", corrige, jwt(), true);

        assertEquals(List.of("mps-10", "mps-20"), after.steps().stream().map(StepDto::ref).toList());
        assertEquals(12000L, after.steps().get(0).km());
        assertEquals(400, after.steps().get(1).days());
    }

    @Test
    void unNouveauPalierRecoitUnIdentifiantDuServeurSansBougerLesAutres() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planAvecDeuxPaliers()));
        PlanRequest avecTroisieme = new PlanRequest("Forfait urbain 26 WBE", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1")),
                        palier("P2", "révision des 12 mois", null, 365, null, List.of("O1")),
                        palier("P3", "visite des 30 000 km", 30000L, null, null, List.of("O2"))),
                List.of(huile("O1"), courroie("O2")));

        PlanResponse after = service().update("mpl-1", avecTroisieme, jwt(), true);

        assertEquals("mps-10", after.steps().get(0).ref());
        assertEquals("mps-20", after.steps().get(1).ref());
        assertTrue(after.steps().get(2).ref().startsWith("mps-"), after.steps().get(2).ref());
        assertNotEquals("mps-10", after.steps().get(2).ref());
        assertNotEquals("mps-20", after.steps().get(2).ref());
    }

    /** Le cas fréquent : le palier est déjà vendu, et on ne change que ses chiffres. */
    @Test
    void corrigerUnPalierDejaVenduRestePossible() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planAvecDeuxPaliers()));
        when(soldPlanSteps.findEngagedStepIds(SoldPlanStatus.CANCELLED)).thenReturn(List.of("mps-10"));
        PlanRequest corrige = new PlanRequest("Forfait urbain 26 WBE", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 15000L, null, null, List.of("O1")),
                        palier("P2", "révision des 12 mois", null, 365, null, List.of())),
                List.of(huile("O1"), courroie("O2")));

        PlanResponse after = service().update("mpl-1", corrige, jwt(), true);

        assertEquals("mps-10", after.steps().get(0).ref());
        assertEquals(15000L, after.steps().get(0).km());
    }

    @Test
    void retirerUnPalierDejaVenduEstRefuse() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planAvecDeuxPaliers()));
        when(soldPlanSteps.findEngagedStepIds(SoldPlanStatus.CANCELLED)).thenReturn(List.of("mps-20"));
        PlanRequest ampute = new PlanRequest("Forfait urbain 26 WBE", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1"))),
                List.of(huile("O1"), courroie("O2")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("mpl-1", ampute, jwt(), true));

        assertTrue(e.getReason().contains("révision des 12 mois"), e.getReason());
        assertTrue(e.getReason().contains("engagé chez un client"), e.getReason());
        verify(plans, never()).save(any());
    }

    /** Renommer revient à retirer l'ancien et à en créer un neuf : le suivi du client est orphelin de la même façon. */
    @Test
    void renommerUnPalierDejaVenduEstRefuse() {
        when(plans.findById("mpl-1")).thenReturn(Optional.of(planAvecDeuxPaliers()));
        when(soldPlanSteps.findEngagedStepIds(SoldPlanStatus.CANCELLED)).thenReturn(List.of("mps-20"));
        PlanRequest renomme = new PlanRequest("Forfait urbain 26 WBE", "Confort", null, null, null, null, true,
                List.of(palier("P1", "visite des 10 000 km", 10000L, null, null, List.of("O1")),
                        palier("P2", "révision des 13 mois", null, 365, null, List.of())),
                List.of(huile("O1"), courroie("O2")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().update("mpl-1", renomme, jwt(), true));

        assertTrue(e.getReason().contains("révision des 12 mois"), e.getReason());
        verify(plans, never()).save(any());
    }

    private static MaintenancePlan planAvecDeuxPaliers() {
        MaintenancePlan plan = planEnBase();
        MaintenancePlanStep dixMille = new MaintenancePlanStep();
        dixMille.setId("mps-10");
        dixMille.setPlan(plan);
        dixMille.setStepOrder(1);
        dixMille.setLabel("visite des 10 000 km");
        dixMille.setKm(10000L);
        MaintenancePlanStep douzeMois = new MaintenancePlanStep();
        douzeMois.setId("mps-20");
        douzeMois.setPlan(plan);
        douzeMois.setStepOrder(2);
        douzeMois.setLabel("révision des 12 mois");
        douzeMois.setDays(365);
        plan.getSteps().add(dixMille);
        plan.getSteps().add(douzeMois);
        return plan;
    }
}
