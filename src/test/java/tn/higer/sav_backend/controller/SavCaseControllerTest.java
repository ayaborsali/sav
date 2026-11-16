package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPaperRequest;
import tn.higer.sav_backend.dto.SavCaseDtos.PartOrderRequest;
import tn.higer.sav_backend.dto.SavCaseDtos.SavCaseRequest;
import tn.higer.sav_backend.dto.SavCaseDtos.SavCaseResponse;
import tn.higer.sav_backend.model.CallerChannel;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.IncidentNature;
import tn.higer.sav_backend.model.MaintenancePlan;
import tn.higer.sav_backend.model.MaintenancePlanStep;
import tn.higer.sav_backend.model.PartOrderState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SoldPlan;
import tn.higer.sav_backend.model.SoldPlanStep;
import tn.higer.sav_backend.model.VisitState;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.InterventionService;
import tn.higer.sav_backend.service.ItemPartService;
import tn.higer.sav_backend.service.MissionService;
import tn.higer.sav_backend.service.SavCaseService;
import tn.higer.sav_backend.service.TechnicianDirectory;
import tn.higer.sav_backend.service.WarrantyClaimService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du graphe de transitions de statut, des flux validation / refus et
 * des deux portes que le dossier a changé : la case « un technicien doit aller sur
 * place » enregistre une demande (MT-), elle ne fait plus sortir personne, et une
 * visite périodique se rattache au palier du forfait vendu.
 * Les repositories et AuditService sont mockés ; le contrôleur est instancié directement.
 */
@ExtendWith(MockitoExtension.class)
class SavCaseControllerTest {

    @Mock
    private SavCaseRepository savCases;

    @Mock
    private InterventionRepository sorties;

    /** Les demandes qui répondent au dossier, lues par la garde de suppression. */
    @Mock
    private MissionRepository demandes;

    @Mock
    private TechnicianRepository technicians;

    @Mock
    private TechnicianDirectory directory;

    @Mock
    private ItemPartService parts;

    @Mock
    private WarrantyClaimService claims;

    @Mock
    private InterventionService interventions;

    /** La porte unique vers une sortie : le dossier ne l'appelle plus, la mission oui. */
    @Mock
    private MissionService missions;

    @Mock
    private SoldPlanRepository forfaits;

    @Mock
    private SoldPlanStepRepository paliers;

    /**
     * Les fiches du bus et du client : le contrôleur les lit pour nommer le bus et le
     * client dans la réponse, l'écran de consultation ne portant que des identifiants.
     * Mockés vides ici : les essais qui vérifient les noms écrits par le serveur les
     * remplissent, et un dossier d'essai sans fiche doit rendre une case vide, pas un « N/A ».
     */
    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ClientRepository clients;

    @Mock
    private AuditService audit;

    private static final String ID = "sav-1";

    private SavCaseController controller() {
        // Le service de numérotation et le service de filiation sont réels : c'est
        // eux qui portent les règles testées, branchés sur les dépôts mockés.
        // La demande que la case cochée fait naître est mockée : son numéro doit
        // seulement arriver à l'écran, et c'est MissionServiceTest qui la vérifie.
        Mission demande = new Mission();
        demande.setId("mss-1");
        demande.setNumber("MT-2026-001");
        lenient().when(missions.enregistrerDepuisDossier(any(), any())).thenReturn(demande);
        return new SavCaseController(savCases, vehicles, clients,
                new SavCaseService(savCases, sorties, demandes, technicians, directory, parts, audit,
                        forfaits, paliers),
                claims, interventions, missions, audit);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "x@y.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static SavCase dossier(SavCaseStatus statut) {
        SavCase c = new SavCase();
        c.setId(ID);
        c.setCaseNumber("CAS-001");
        c.setType(SavCaseType.RECLAMATIONS);
        c.setTitle("Panne climatisation");
        c.setVehicleId("veh-1");
        c.setStatus(statut);
        return c;
    }

    /**
     * La saisie d'un dossier, avec ou sans « un technicien doit aller sur place ».
     * Cette case n'ouvre plus une sortie : elle enregistre la demande MT-, que
     * l'atelier tranchera sur l'écran Missions.
     */
    private static SavCaseRequest request(SavCaseStatus statut, boolean besoinDeSortie) {
        return new SavCaseRequest("CAS-001", SavCaseType.RECLAMATIONS, "Panne climatisation",
                "veh-1", "cli-1", statut, null, null, null,
                "Description du dossier", null, null, besoinDeSortie, null, null,
                "M. Bouazizi", CallerChannel.TELEPHONE, null, "Intervention avant vendredi",
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SavCaseRequest request(SavCaseStatus statut) {
        return request(statut, false);
    }

    /** Le même dossier, sans bus : la demande ne peut pas répondre à un bus inconnu. */
    private static SavCaseRequest requestSansBus() {
        SavCaseRequest base = request(SavCaseStatus.OPEN, true);
        return recopie(base, base.caseNumber(), "  ");
    }

    /**
     * La même saisie, deux cases près. Le record n'a pas de « copie avec », et le
     * laisser écrire ses trente-deux cases ici évite que le jour où une case
     * s'ajoute, un test passe en écrasant sans le dire la bonne valeur.
     */
    private static SavCaseRequest recopie(SavCaseRequest b, String caseNumber, String vehicleId) {
        return new SavCaseRequest(caseNumber, b.type(), b.title(), vehicleId,
                b.clientId(), b.status(), b.priority(), b.createdAt(), b.updatedAt(),
                b.description(), b.resolution(), b.technicianUserId(), b.requiresIntervention(),
                b.warrantyApprovalNumber(), b.costEstimate(), b.callerName(), b.callerChannel(),
                b.calledAt(), b.expectedAction(),
                b.plannedDate(), b.visitDate(), b.visitState(), b.nextActions(),
                b.soldPlanId(), b.planStepId(),
                b.declarationDate(), b.classification(), b.severity(),
                b.correctiveActions(), b.preventiveActions(), b.order(), b.agPaper());
    }

    /** Un dossier de la famille « visites », avec ses cases du papier de référence. */
    private static SavCaseRequest requestVisite(LocalDate planifiee, VisitState etat) {
        return visiteAvecRattachement(planifiee, etat, null, null);
    }

    /**
     * La visite périodique, rattachée ou non au forfait vendu. Les deux cases voyagent
     * ensemble : le serveur refuse un forfait sans palier, un palier sans forfait, un
     * palier qui n'est pas celui de CE forfait, et un forfait vendu sur un autre bus.
     */
    private static SavCaseRequest visiteAvecRattachement(LocalDate planifiee, VisitState etat,
                                                         String soldPlanId, String planStepId) {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        return new SavCaseRequest(base.caseNumber(), SavCaseType.VISITES, base.title(), base.vehicleId(),
                base.clientId(), base.status(), base.priority(), base.createdAt(), base.updatedAt(),
                base.description(), "Compte rendu lu au client", base.technicianUserId(), false,
                base.warrantyApprovalNumber(), base.costEstimate(), base.callerName(),
                base.callerChannel(), base.calledAt(), base.expectedAction(),
                planifiee, LocalDate.of(2026, 10, 26), etat, "Rappeler le client le 3",
                soldPlanId, planStepId, null, null, null, null, null, null, null);
    }

    /** Un dossier de la famille « qualité », avec sa criticité et ses deux actions. */
    private static SavCaseRequest requestQualite(String criticite) {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        return new SavCaseRequest(base.caseNumber(), SavCaseType.QUALITE, base.title(), base.vehicleId(),
                base.clientId(), base.status(), base.priority(), base.createdAt(), base.updatedAt(),
                "Vernis qui cloque sur trois bus", base.resolution(), base.technicianUserId(), false,
                base.warrantyApprovalNumber(), base.costEstimate(), base.callerName(),
                base.callerChannel(), base.calledAt(), base.expectedAction(),
                null, null, null, null, null, null,
                LocalDate.of(2026, 10, 21), "Peinture", criticite, "Reprise du joint", "Sécher à 40°",
                null, null);
    }

    /** Le dossier « visites » avec une case de la famille qualité dessus : refusé. */
    private static SavCaseRequest requestVisiteAvecCriticite(String severity) {
        return avecCasseQualite(requestVisite(LocalDate.of(2026, 10, 20), VisitState.REALISEE),
                null, severity);
    }

    /** Une seule case qualité sur une saisie d'une autre famille — le refus doit la nommer. */
    private static SavCaseRequest avecCasseQualite(SavCaseRequest b, LocalDate declaration, String criticite) {
        return new SavCaseRequest(b.caseNumber(), b.type(), b.title(), b.vehicleId(),
                b.clientId(), b.status(), b.priority(), b.createdAt(), b.updatedAt(),
                b.description(), b.resolution(), b.technicianUserId(), b.requiresIntervention(),
                b.warrantyApprovalNumber(), b.costEstimate(), b.callerName(), b.callerChannel(),
                b.calledAt(), b.expectedAction(),
                b.plannedDate(), b.visitDate(), b.visitState(), b.nextActions(),
                b.soldPlanId(), b.planStepId(),
                declaration, null, criticite, null, null,
                b.order(), b.agPaper());
    }

    @Test
    void transitionLegale_openVersInProgress_estAcceptee() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.OPEN)));
        SavCaseResponse response = controller().update(ID, request(SavCaseStatus.IN_PROGRESS), jwt());
        assertEquals("in_progress", response.status());
        verify(audit).record(eq("UPDATE"), eq("SAV_CASE"), eq(ID), anyString(), any(Jwt.class));
    }

    @Test
    void transitionIlleegale_depuisClosed_estRefuseeEn400() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.CLOSED)));
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().update(ID, request(SavCaseStatus.IN_PROGRESS), jwt()));
        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        // Message et statuts rendus en minuscules (label de l'enum).
        assertTrue(erreur.getReason().contains("Transition de statut interdite"),
                "Le message doit refuser explicitement la transition");
        assertTrue(erreur.getReason().contains("closed") && erreur.getReason().contains("in_progress"),
                "Le message doit nommer les statuts source et cible");
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void putMemeStatut_estToujoursAutorise() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.IN_PROGRESS)));
        SavCaseResponse response = controller().update(ID, request(SavCaseStatus.IN_PROGRESS), jwt());
        assertEquals("in_progress", response.status());
        verify(savCases).save(any(SavCase.class));
    }

    @Test
    void validate_fixeApprouveEtJournalise() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.OPEN)));
        ArgumentCaptor<SavCase> capture = ArgumentCaptor.forClass(SavCase.class);

        SavCaseResponse response = controller().validate(ID, jwt());

        assertEquals("approved", response.status());
        assertTrue(response.resolution().contains("validée"),
                "La résolution en français doit mentionner la validation");
        verify(savCases).save(capture.capture());
        assertEquals(SavCaseStatus.APPROVED, capture.getValue().getStatus());
        verify(audit).record(eq("VALIDATE"), eq("SAV_CASE"), eq(ID), anyString(), any(Jwt.class));
    }

    @Test
    void reject_fixeRefuseEtJournalise() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.IN_PROGRESS)));
        ArgumentCaptor<SavCase> capture = ArgumentCaptor.forClass(SavCase.class);

        SavCaseResponse response = controller().reject(ID, jwt());

        assertEquals("rejected", response.status());
        assertTrue(response.resolution().contains("refusée"),
                "La résolution en français doit mentionner le refus");
        verify(savCases).save(capture.capture());
        assertEquals(SavCaseStatus.REJECTED, capture.getValue().getStatus());
        verify(audit).record(eq("REJECT"), eq("SAV_CASE"), eq(ID), anyString(), any(Jwt.class));
    }

    @Test
    void numeroLaisseVide_estDonneParLeServeur() {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        SavCaseRequest sansNumero = recopie(base, null, base.vehicleId());

        SavCaseResponse response = controller().create(sansNumero, jwt());

        assertTrue(response.caseNumber().matches("SAV-REC-\\d{4}-001"),
                "numéro obtenu : " + response.caseNumber());
    }

    @Test
    void numeroFourni_estConserve() {
        SavCaseResponse response = controller().create(request(SavCaseStatus.OPEN), jwt());

        assertEquals("CAS-001", response.caseNumber());
        verify(savCases).save(any(SavCase.class));
    }

    @Test
    void numeroDejaPris_estRefuseALaCreation() {
        when(savCases.existsByCaseNumberIgnoreCase("CAS-001")).thenReturn(true);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(request(SavCaseStatus.OPEN), jwt()));
        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("existe déjà"), erreur.getReason());
        verify(savCases, never()).save(any());
    }

    /**
     * La case « un technicien doit aller sur place » n'est plus une porte vers une
     * sortie : elle enregistre la demande MT-, et c'est l'atelier qui décide d'envoyer
     * un homme. C'est la seconde des trois portes refermées du lot N2 — la preuve
     * tient en un « jamais appelé » sur {@code createFromSavCase}.
     */
    @Test
    void dossierCochéEnregistreLaDemande_etNeFaitSortirPersonne() {
        SavCaseResponse response = controller().create(request(SavCaseStatus.OPEN, true), jwt());

        ArgumentCaptor<SavCase> dossier = ArgumentCaptor.forClass(SavCase.class);
        verify(missions).enregistrerDepuisDossier(dossier.capture(), any(Jwt.class));
        assertTrue(dossier.getValue().getId().startsWith("sav-"),
                "la demande répond au dossier que le serveur vient de créer, pas d'un id inventé");
        assertEquals("MT-2026-001", response.missionNumber(),
                "le poste doit voir le numéro posé : sans preuve à l'écran, il croit n'avoir rien fait");
        verify(interventions, never()).createFromSavCase(anyString(), any(), any());
        verify(savCases).save(any(SavCase.class));
    }

    @Test
    void dossierSansCoché_nEnregistreRien() {
        SavCaseResponse response = controller().create(request(SavCaseStatus.OPEN, false), jwt());

        verify(interventions, never()).createFromSavCase(anyString(), any(), any());
        verify(missions, never()).enregistrerDepuisDossier(any(), any());
        assertNull(response.missionNumber(), "personne n'a demandé de sortie : aucune mission");
    }

    @Test
    void miseÀjourCochéeTardivement_enregistreLaDemandeDeCeDossier() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.OPEN)));

        controller().update(ID, request(SavCaseStatus.OPEN, true), jwt());

        ArgumentCaptor<SavCase> dossier = ArgumentCaptor.forClass(SavCase.class);
        verify(missions).enregistrerDepuisDossier(dossier.capture(), any(Jwt.class));
        assertEquals(ID, dossier.getValue().getId());
        verify(interventions, never()).createFromSavCase(anyString(), any(), any());
    }

    /**
     * Un dossier qui ne nomme aucun bus ne peut pas envoyer quelqu'un : la règle est
     * écrite côté mission. Ce test fige que le contrôleur ne l'avale pas et qu'aucune
     * sortie n'est ouverte en contournant la demande. La garde {@code @Transactional}
     * annule le dossier écrit dans la même passe — ici le dépôt est un mock, il ne le
     * sait pas.
     */
    @Test
    void dossierSansBus_leRefusRemonteSansOuvrirDeSortie() {
        SavCaseController controller = controller();
        when(missions.enregistrerDepuisDossier(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ce dossier ne dit pas de quel bus il s'agit : nommez le bus."));

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller.create(requestSansBus(), jwt()));

        assertTrue(erreur.getReason().contains("de quel bus"), erreur.getReason());
        verify(interventions, never()).createFromSavCase(anyString(), any(), any());
    }

    // ------------------------------------------------------ la trace de l'appel du client

    @Test
    void laDemandeDuClientEstGardeeAvecLeDossier() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.OPEN)));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        SavCaseResponse response = controller().update(ID, request(SavCaseStatus.OPEN), jwt());

        verify(savCases).save(ecrit.capture());
        assertEquals("M. Bouazizi", ecrit.getValue().getCallerName());
        assertEquals(CallerChannel.TELEPHONE, ecrit.getValue().getCallerChannel());
        assertEquals("Intervention avant vendredi", ecrit.getValue().getExpectedAction());
        // L'écran lit le mot français, pas l'enum : « téléphone » se relit au téléphone.
        assertEquals("telephone", response.callerChannel());
        assertEquals("Téléphone", response.callerChannelLabel());
        assertEquals("M. Bouazizi", response.callerName());
    }

    @Test
    void unePiecePoseeNestPlusUnChampDuDossier() {
        // Le dossier ne porte plus la pièce : elle est une ligne du papier (V21).
        // Une requête d'un front d'avant, qui enverrait encore ces deux clés, les perd.
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.OPEN)));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        controller().update(ID, base, jwt());

        verify(savCases).save(ecrit.capture());
        assertNull(ecrit.getValue().getPartReference());
        assertNull(ecrit.getValue().getQuantity());
    }

    // ------------------------------------------------------ la filiation réclamation → claim

    @Test
    void leDossierGarantieOuvertDepuisLaReclamationPorteSonNumero() {
        SavCase parent = dossier(SavCaseStatus.CLOSED);
        when(savCases.findById(ID)).thenReturn(Optional.of(parent));
        when(savCases.findFirstByParentCaseIdAndType(ID, SavCaseType.GARANTIES)).thenReturn(Optional.empty());
        when(savCases.findNumbersByPrefix(any())).thenReturn(List.of());
        when(sorties.findBySavCaseIdOrderByReportedAtAsc(ID)).thenReturn(List.of());

        SavCaseResponse claim = controller().openClaim(ID, jwt(), null);

        assertEquals("garanties", claim.type(), "la famille du claim : garanties");
        assertEquals("a_deposer", claim.claimState());
        assertEquals("À déposer", claim.claimStateLabel());
        assertEquals(ID, claim.parentCaseId());
        assertEquals("CAS-001", claim.parentCaseNumber(), "la ligne doit dire de quoi elle est née");
        assertTrue(claim.title().startsWith("Claim garantie — "), claim.title());
    }

    @Test
    void laListeDitDeQuelDossierLeClaimEstNeEtCeQueLaReclamationADonne() {
        SavCase parent = dossier(SavCaseStatus.CLOSED);
        SavCase claim = dossier(SavCaseStatus.CLOSED);
        claim.setId("sav-2");
        claim.setCaseNumber("SAV-WAR-2026-001");
        claim.setType(SavCaseType.GARANTIES);
        claim.setParentCaseId(ID);
        when(savCases.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(parent, claim)));
        when(savCases.findByParentCaseIdIn(List.of(ID, "sav-2"))).thenReturn(List.of(claim));
        when(savCases.findAllById(List.of(ID))).thenReturn(List.of(parent));

        Page<SavCaseResponse> page = controller().list(PageRequest.of(0, 20));

        SavCaseResponse ligneReclamation = page.getContent().get(0);
        assertEquals(1, ligneReclamation.children().size(),
                "la réclamation affiche qu'elle a donné un dossier garantie — sans requête de plus");
        assertEquals("sav-2", ligneReclamation.children().get(0).id());
        assertNull(ligneReclamation.parentCaseNumber());

        SavCaseResponse ligneClaim = page.getContent().get(1);
        assertEquals("CAS-001", ligneClaim.parentCaseNumber());
        assertTrue(ligneClaim.children().isEmpty());
    }

    /**
     * Ses 17 dossiers du 26/10 n'ont aucun parent : la carte des parents est alors
     * une {@code Map.of()} immuable, et lui demander la clé {@code null} d'un dossier
     * sans parent levait un NPE — toute la liste tombait en 500 et l'écran restait
     * vide. Le test précédent ne pouvait pas le voir : il a un claim rattaché.
     */
    @Test
    void laListeDossierSansAucunParentNeSeCrashPas() {
        SavCase visite = dossier(SavCaseStatus.OPEN);
        SavCase piece = dossier(SavCaseStatus.IN_PROGRESS);
        piece.setId("sav-2");
        piece.setCaseNumber("SAV-PIE-2026-0155");
        piece.setType(SavCaseType.PIECES);
        when(savCases.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(visite, piece)));
        when(savCases.findByParentCaseIdIn(List.of(ID, "sav-2"))).thenReturn(List.of());

        Page<SavCaseResponse> page = controller().list(PageRequest.of(0, 20));

        assertEquals(2, page.getContent().size());
        assertNull(page.getContent().get(0).parentCaseNumber());
        assertNull(page.getContent().get(1).parentCaseNumber());
        verify(savCases, never()).findAllById(any());
    }

    @Test
    void unDossierGarantieClotureSansRienDemandeAuConstructeurSeSignale() {
        SavCase cloture = dossier(SavCaseStatus.CLOSED);
        cloture.setId("sav-2");
        cloture.setType(SavCaseType.GARANTIES);
        when(savCases.findById("sav-2")).thenReturn(Optional.of(cloture));

        assertTrue(controller().get("sav-2").claimADeposer(),
                "travail fini et rien de demandé : le portail doit le signaler, pas décider à la place");

        cloture.setClaimState(ClaimState.A_DEPOSER);
        assertFalse(controller().get("sav-2").claimADeposer(), "la demande est écrite : le signal s'éteint");

        cloture.setClaimState(null);
        cloture.setType(SavCaseType.RECLAMATIONS);
        assertFalse(controller().get("sav-2").claimADeposer(),
                "une réclamation clôturée ne réclame rien au constructeur d'office");
    }

    // ---------------------- les cases du papier de référence, chacune dans sa famille

    @Test
    void uneVisiteGardeSesDatesSonEtatEtSesActionsASuivre() {
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        SavCaseResponse response = controller().create(
                requestVisite(LocalDate.of(2026, 10, 20), VisitState.PLANIFIEE), jwt());

        verify(savCases).save(ecrit.capture());
        assertEquals(LocalDate.of(2026, 10, 20), ecrit.getValue().getPlannedDate());
        assertEquals(VisitState.PLANIFIEE, ecrit.getValue().getVisitState());
        assertEquals("Rappeler le client le 3", ecrit.getValue().getNextActions());
        // L'écran lit le mot français : « reportée » se relit à l'atelier, pas REPORTEE.
        assertEquals("planifiee", response.visitState());
        assertEquals("Planifiée", response.visitStateLabel());
        assertEquals(LocalDate.of(2026, 10, 26), response.visitDate());
    }

    // ------------------------------------------------- N1 : la visite périodique, une seule notion

    /**
     * Rattaché au palier du forfait vendu, le dossier de visite dit « je suis la
     * visite des 40 000 km ». Avant ces deux cases, le mot désignait deux choses qui
     * ne se connaissaient pas : le rappel sorti du compteur d'un côté, le papier tapé
     * à l'atelier de l'autre.
     */
    @Test
    void uneVisiteRattacheeAuPalierDuForfait_seRelitAvecSonForfait() {
        SoldPlanStep suivi = palierVendu("sp-1", "veh-1", "cli-1");
        when(forfaits.findById("sp-1")).thenReturn(Optional.of(suivi.getSoldPlan()));
        when(paliers.findBySoldPlanIdAndStepId("sp-1", "mps-40")).thenReturn(Optional.of(suivi));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        SavCaseResponse response = controller().create(
                visiteAvecRattachement(LocalDate.of(2026, 10, 20), VisitState.REALISEE, "sp-1", "mps-40"),
                jwt());

        verify(savCases).save(ecrit.capture());
        assertEquals("sp-1", ecrit.getValue().getSoldPlanId());
        assertEquals("mps-40", ecrit.getValue().getPlanStepId(),
                "le palier se stocke par son identifiant du catalogue, jamais par la ligne de suivi");
        assertEquals("sp-1", response.soldPlanId());
        assertEquals("mps-40", response.planStepId());
    }

    /** Les deux cases voyagent ensemble : à moitié choisi, le serveur refuse plutôt que de deviner. */
    @Test
    void uneVisiteRattacheeAMoitie_estRefusee() {
        ResponseStatusException sansPalier = assertThrows(ResponseStatusException.class,
                () -> controller().create(visiteAvecRattachement(
                        LocalDate.of(2026, 10, 20), VisitState.PLANIFIEE, "sp-1", null), jwt()));
        assertEquals(HttpStatus.BAD_REQUEST, sansPalier.getStatusCode());
        assertTrue(sansPalier.getReason().contains("à moitié"), sansPalier.getReason());
        verify(savCases, never()).save(any());

        ResponseStatusException sansForfait = assertThrows(ResponseStatusException.class,
                () -> controller().create(visiteAvecRattachement(
                        LocalDate.of(2026, 10, 20), VisitState.PLANIFIEE, null, "mps-40"), jwt()));
        assertTrue(sansForfait.getReason().contains("à moitié"), sansForfait.getReason());
    }

    @Test
    void unPalierQuiNAppartientPasACeForfait_estRefuse() {
        SoldPlanStep suivi = palierVendu("sp-1", "veh-1", "cli-1");
        when(forfaits.findById("sp-1")).thenReturn(Optional.of(suivi.getSoldPlan()));
        when(paliers.findBySoldPlanIdAndStepId("sp-1", "mps-60")).thenReturn(Optional.empty());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(visiteAvecRattachement(
                        LocalDate.of(2026, 10, 20), VisitState.PLANIFIEE, "sp-1", "mps-60"), jwt()));

        assertTrue(erreur.getReason().contains("n'appartient pas au forfait"), erreur.getReason());
        verify(savCases, never()).save(any());
    }

    /**
     * Le forfait a été vendu sur un autre bus : rattacher la visite promettait une
     * visite couverte à un bus qui ne l'est pas.
     */
    @Test
    void unForfaitVenduSurUnAutreBus_estRefuse() {
        SoldPlanStep suivi = palierVendu("sp-1", "veh-9", "cli-1");
        when(forfaits.findById("sp-1")).thenReturn(Optional.of(suivi.getSoldPlan()));

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(visiteAvecRattachement(
                        LocalDate.of(2026, 10, 20), VisitState.PLANIFIEE, "sp-1", "mps-40"), jwt()));

        assertTrue(erreur.getReason().contains("un autre bus"), erreur.getReason());
        verify(paliers, never()).findBySoldPlanIdAndStepId(anyString(), anyString());
    }

    @Test
    void uneVisiteDUnForfaitInexistant_estRefusee() {
        when(forfaits.findById("sp-9")).thenReturn(Optional.empty());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(visiteAvecRattachement(
                        LocalDate.of(2026, 10, 20), VisitState.PLANIFIEE, "sp-9", "mps-40"), jwt()));

        assertTrue(erreur.getReason().contains("il n'existe pas"), erreur.getReason());
    }

    /**
     * Le rattachement ne se remplit que sur la famille « visites ». Une réclamation
     * qui arriverait avec ces deux cases est refusée à l'écrit : effacer les cases
     * en silence laisserait l'atelier croire qu'une visite couverte a été promise.
     */
    @Test
    void unRattachementDeVisiteSurUneAutreFamille_estRefuseEcrit() {
        SavCaseRequest reclamationAvecRattachement = avecRattachement(
                request(SavCaseStatus.OPEN), "sp-1", "mps-40");

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(reclamationAvecRattachement, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("ne porte pas de case de visite périodique"),
                erreur.getReason());
        verify(savCases, never()).save(any());
        verifyNoInteractions(forfaits, paliers);
    }

    @Test
    void laQualiteGardeSaCriticiteEtSesDeuxActions() {
        SavCaseResponse response = controller().create(requestQualite("Majeure"), jwt());

        assertEquals(LocalDate.of(2026, 10, 21), response.declarationDate());
        assertEquals("Peinture", response.classification());
        assertEquals("Majeure", response.severity());
        assertEquals("Reprise du joint", response.correctiveActions());
        assertEquals("Sécher à 40°", response.preventiveActions());
    }

    @Test
    void uneCriticiteSurUnDossierVisite_estRefuseeEcrit() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(requestVisiteAvecCriticite("Mineure"), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("ne porte pas de case de criticité"), erreur.getReason());
        assertTrue(erreur.getReason().contains("qualité"),
                "le refus doit dire dans quelle famille ranger le dossier");
        verify(savCases, never()).save(any());
    }

    @Test
    void uneDateDeDeclarationSurUnDossierVisite_estRefusee() {
        // La case est partagée entre qualité et garanties (le jour où le papier
        // s'écrit), mais elle ne se remplit sur une visite : une date de
        // déclaration sur une visite se relit comme une anomalie déclarée.
        SavCaseRequest visiteAvecDeclaration = avecCasseQualite(
                requestVisite(LocalDate.of(2026, 10, 20), VisitState.REALISEE),
                LocalDate.of(2026, 10, 21), null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(visiteAvecDeclaration, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("ne porte pas de case de déclaration"), erreur.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void uneVisiteSurUnDossierReclamation_estRefusee() {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        SavCaseRequest avecDateDeVisite = new SavCaseRequest(base.caseNumber(), base.type(), base.title(),
                base.vehicleId(), base.clientId(), base.status(), base.priority(), base.createdAt(),
                base.updatedAt(), base.description(), base.resolution(), base.technicianUserId(), false,
                base.warrantyApprovalNumber(), base.costEstimate(), base.callerName(), base.callerChannel(),
                base.calledAt(), base.expectedAction(),
                LocalDate.of(2026, 10, 20), null, VisitState.REALISEE, null,
                null, null, null, null, null, null, null, null, null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(avecDateDeVisite, jwt()));

        assertTrue(erreur.getReason().contains("ne porte pas de case de visite"), erreur.getReason());
    }

    @Test
    void changerDeFamille_effaceLesCasesDeLAutreVie() {
        // Le dossier était une visite, l'atelier le range en réclamation : ses dates
        // de venue ne suivent pas. Une date de visite sur une réclamation se relit
        // comme une preuve, et ce n'en est plus une.
        SavCase etaitVisite = dossier(SavCaseStatus.OPEN);
        etaitVisite.setType(SavCaseType.VISITES);
        etaitVisite.setPlannedDate(LocalDate.of(2026, 10, 20));
        etaitVisite.setVisitState(VisitState.REPORTEE);
        when(savCases.findById(ID)).thenReturn(Optional.of(etaitVisite));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        controller().update(ID, request(SavCaseStatus.OPEN), jwt());

        verify(savCases).save(ecrit.capture());
        assertNull(ecrit.getValue().getPlannedDate(), "la visite ne suit pas le changement de famille");
        assertNull(ecrit.getValue().getVisitState());
    }

    @Test
    void uneCriticiteInventee_passe_quandLeDossierEstBien_deLaFamilleQualite() {
        // La grille de criticité n'a jamais été fournie par le constructeur : le
        // portail propose les trois mots du papier, il n'interdit pas un quatrième.
        SavCaseResponse response = controller().create(requestQualite("Bloquante"), jwt());

        assertEquals("Bloquante", response.severity());
    }

    @Test
    void changerDeFamille_effaceLeRattachementDeVisite() {
        // Le dossier était la visite des 40 000 km d'un forfait, l'atelier le range en
        // réclamation : le rattachement ne suit pas, sinon le forfait croirait sa
        // visite faite alors que le papier dit autre chose.
        SavCase etaitVisite = dossier(SavCaseStatus.OPEN);
        etaitVisite.setType(SavCaseType.VISITES);
        etaitVisite.setSoldPlanId("sp-1");
        etaitVisite.setPlanStepId("mps-40");
        when(savCases.findById(ID)).thenReturn(Optional.of(etaitVisite));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        controller().update(ID, request(SavCaseStatus.OPEN), jwt());

        verify(savCases).save(ecrit.capture());
        assertNull(ecrit.getValue().getSoldPlanId());
        assertNull(ecrit.getValue().getPlanStepId());
        verifyNoInteractions(forfaits, paliers);
    }

    // --- V25 : la date de clôture ------------------------------------------------

    @Test
    void cloturerUnDossier_ecritLaDate_toutSeul() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.ON_SITE)));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        SavCaseResponse response = controller().update(ID, request(SavCaseStatus.CLOSED), jwt());

        verify(savCases).save(ecrit.capture());
        assertEquals(LocalDate.now(), ecrit.getValue().getClosedAt(),
                "le jour de la fermeture s'écrit derrière le statut, personne ne le tape");
        assertEquals(LocalDate.now(), response.closedAt());
    }

    @Test
    void unDossierQuiRepartDehors_perdSaDateDeCloture() {
        // La sortie la plus récente peut rouvrir un travail : laisser la date, ce
        // serait dater une fermeture qui n'a plus lieu.
        SavCase ferme = dossier(SavCaseStatus.CLOSED);
        ferme.setClosedAt(LocalDate.of(2026, 6, 30));
        ferme.setStatus(SavCaseStatus.ON_SITE);

        SavCaseService.daterCloture(ferme);

        assertNull(ferme.getClosedAt());
    }

    @Test
    void uneClotureDeja_Ecrite_neSeReecritPas() {
        SavCase ferme = dossier(SavCaseStatus.CLOSED);
        ferme.setClosedAt(LocalDate.of(2026, 6, 30));

        SavCaseService.daterCloture(ferme);

        assertEquals(LocalDate.of(2026, 6, 30), ferme.getClosedAt(),
                "rouvrir le même dossier ne doit pas re-dater la fin du travail");
    }

    // --- V26 : la commande de pièces ---------------------------------------------

    @Test
    void uneCommandeLivree_seDate() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(requestPieces(PartOrderState.LIVREE,
                        LocalDate.of(2026, 10, 2), null, 4, 4), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("se date"), erreur.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void livreeAvecMoinsRecuQueCommande_estPartielleEtCestRefuse() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(requestPieces(PartOrderState.LIVREE,
                        LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20), 4, 2), jwt()));

        assertTrue(erreur.getReason().contains("partielle"), erreur.getReason());
    }

    @Test
    void unePartielle_seChiffre() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(requestPieces(PartOrderState.PARTIELLE,
                        LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20), null, null), jwt()));

        assertTrue(erreur.getReason().contains("quantité commandée"), erreur.getReason());
    }

    @Test
    void laCommandePartielle_seRecopieEtSeRelit() {
        SavCaseResponse response = controller().create(requestPieces(PartOrderState.PARTIELLE,
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 20), 4, 2), jwt());

        assertEquals("partielle", response.order().state());
        assertEquals("Partielle", response.order().stateLabel());
        assertEquals(Integer.valueOf(4), response.order().quantityOrdered());
        assertEquals(Integer.valueOf(2), response.order().quantityReceived());
        assertEquals(LocalDate.of(2026, 10, 2), response.order().orderDate());
    }

    @Test
    void uneCommandeSurUnDossierVisite_estRefusee() {
        SavCaseRequest base = requestVisite(LocalDate.of(2026, 10, 20), VisitState.REALISEE);
        SavCaseRequest avecCommande = avecCommande(base, requestPieces(PartOrderState.EN_COURS,
                LocalDate.of(2026, 10, 2), null, 1, null).order());

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(avecCommande, jwt()));

        assertTrue(erreur.getReason().contains("ne porte pas de case de commande de pièces"), erreur.getReason());
        assertTrue(erreur.getReason().contains("pièces"), erreur.getReason());
    }

    @Test
    void changerDeFamille_effaceLaCommande() {
        SavCase etaitCommande = dossier(SavCaseStatus.OPEN);
        etaitCommande.setType(SavCaseType.PIECES);
        etaitCommande.setOrderState(PartOrderState.EN_COURS);
        etaitCommande.setQuantityOrdered(6);
        when(savCases.findById(ID)).thenReturn(Optional.of(etaitCommande));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        controller().update(ID, request(SavCaseStatus.OPEN), jwt());

        verify(savCases).save(ecrit.capture());
        assertNull(ecrit.getValue().getOrderState(), "la commande ne suit pas le changement de famille");
        assertNull(ecrit.getValue().getQuantityOrdered());
    }

    // --- V27 : le papier AG --------------------------------------------------------

    @Test
    void lePapierAg_seRecopieCaseParCase() {
        SavCaseResponse response = controller().create(requestGarantie(papier(IncidentNature.REPETITIF,
                "HGR-WAR-25-0114")), jwt());

        assertEquals("M. Trabelsi", response.agPaper().writerName());
        assertEquals("OR-2026-118", response.agPaper().repairOrderNumber());
        assertEquals("repetitif", response.agPaper().incidentType());
        assertEquals("Répétitif", response.agPaper().incidentTypeLabel());
        assertEquals(Double.valueOf(2.1), response.agPaper().allocatedTime());
        assertEquals(Double.valueOf(147.0), response.agPaper().laborNet(),
                "le net reste celui que l'humain a écrit");
        assertEquals("2115U", response.agPaper().defectCode());
        assertEquals(LocalDate.of(2026, 10, 19), response.agPaper().incidentDate());
        assertFalse(response.agPaper().securityImpact());
    }

    @Test
    void laDateDeRedactionDuPapier_estLaMemeCaseQueLaDateDeDeclaration() {
        // Le papier AG appelle « date de rédaction » ce que l'onglet qualité appelle
        // « date de déclaration » : une colonne, deux libellés, pas deux chiffres.
        SavCaseResponse response = controller().create(requestGarantie(papier(IncidentNature.NOUVEAU, null)),
                jwt());

        assertEquals(LocalDate.of(2026, 10, 24), response.declarationDate());
    }

    @Test
    void unIncidentRepetitif_sANSAGPrecedent_estRefuse() {
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(requestGarantie(papier(IncidentNature.REPETITIF, null)), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("répétitif"), erreur.getReason());
    }

    @Test
    void unPapierAgSurUneReclamation_estRefuse() {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        SavCaseRequest avecPapier = avecPapier(base, papier(IncidentNature.NOUVEAU, null));

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(avecPapier, jwt()));

        assertTrue(erreur.getReason().contains("ne porte pas de case de papier AG"), erreur.getReason());
        assertTrue(erreur.getReason().contains("garanties"), erreur.getReason());
    }

    @Test
    void unPapierNonEnvoye_neVideRien() {
        // L'écran Dépannage modifie un dossier sans rien savoir du papier AG : il
        // n'envoie pas le bloc, et le papier doit rester debout derrière lui.
        SavCase dejaPapier = dossier(SavCaseStatus.OPEN);
        dejaPapier.setType(SavCaseType.GARANTIES);
        dejaPapier.setAgWriterName("M. Trabelsi");
        dejaPapier.setAgLaborNet(147.0);
        when(savCases.findById(ID)).thenReturn(Optional.of(dejaPapier));
        ArgumentCaptor<SavCase> ecrit = ArgumentCaptor.forClass(SavCase.class);

        SavCaseRequest garantieSansPapier = avecPapier(
                requestGarantie(papier(IncidentNature.NOUVEAU, null)), null);
        controller().update(ID, garantieSansPapier, jwt());

        verify(savCases).save(ecrit.capture());
        assertEquals("M. Trabelsi", ecrit.getValue().getAgWriterName());
        assertEquals(Double.valueOf(147.0), ecrit.getValue().getAgLaborNet());
    }

    /** Le même dossier, famille et cases de pièces posées. */
    private static SavCaseRequest requestPieces(PartOrderState etat, LocalDate commande, LocalDate livree,
                                                Integer commandee, Integer recue) {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        return new SavCaseRequest("CAS-PIE-001", SavCaseType.PIECES, base.title(), base.vehicleId(),
                base.clientId(), base.status(), base.priority(), base.createdAt(), base.updatedAt(),
                base.description(), base.resolution(), base.technicianUserId(), false,
                base.warrantyApprovalNumber(), base.costEstimate(), base.callerName(),
                base.callerChannel(), base.calledAt(), base.expectedAction(),
                null, null, null, null, null, null, null, null, null, null, null,
                new PartOrderRequest(commande, "HGR-INJ-04", "Injecteur common rail",
                        commandee, recue, etat, livree, "Relancé le 12"),
                null);
    }

    /** Un dossier garantie, avec le papier AG dessus. */
    private static SavCaseRequest requestGarantie(AgPaperRequest papier) {
        SavCaseRequest base = request(SavCaseStatus.OPEN);
        return new SavCaseRequest("CAS-GAR-001", SavCaseType.GARANTIES, base.title(), base.vehicleId(),
                base.clientId(), base.status(), base.priority(), base.createdAt(), base.updatedAt(),
                base.description(), base.resolution(), base.technicianUserId(), false,
                base.warrantyApprovalNumber(), base.costEstimate(), base.callerName(),
                base.callerChannel(), base.calledAt(), base.expectedAction(),
                null, null, null, null, null, null, LocalDate.of(2026, 10, 24), null, null, null, null,
                null, papier);
    }

    /**
     * Le forfait vendu et le palier du catalogue que la visite vient faire. Le palier
     * s'identifie par son identifiant du catalogue ({@code mps-40}), jamais par la
     * ligne de suivi : deux bus vendus du même forfait ont deux lignes différentes
     * pour la même visite des 40 000 km.
     */
    private static SoldPlanStep palierVendu(String soldPlanId, String busId, String clientId) {
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
        vendu.setId(soldPlanId);
        vendu.setPlan(plan);
        vendu.setVehicleId(busId);
        vendu.setClientId(clientId);

        SoldPlanStep suivi = new SoldPlanStep();
        suivi.setId("sps-" + palier.getId());
        suivi.setSoldPlan(vendu);
        suivi.setStep(palier);
        vendu.getSteps().add(suivi);
        return suivi;
    }

    /** La même saisie, un rattachement de visite dessus. */
    private static SavCaseRequest avecRattachement(SavCaseRequest b, String soldPlanId, String planStepId) {
        return new SavCaseRequest(b.caseNumber(), b.type(), b.title(), b.vehicleId(), b.clientId(),
                b.status(), b.priority(), b.createdAt(), b.updatedAt(), b.description(), b.resolution(),
                b.technicianUserId(), b.requiresIntervention(), b.warrantyApprovalNumber(), b.costEstimate(),
                b.callerName(), b.callerChannel(), b.calledAt(), b.expectedAction(),
                b.plannedDate(), b.visitDate(), b.visitState(), b.nextActions(),
                soldPlanId, planStepId,
                b.declarationDate(), b.classification(), b.severity(), b.correctiveActions(),
                b.preventiveActions(), b.order(), b.agPaper());
    }

    /** Le papier de référence, rempli comme à l'atelier. Les codes viennent de Higer. */
    private static AgPaperRequest papier(IncidentNature nature, String precedentAG) {
        return new AgPaperRequest("M. Trabelsi", "OR-2026-118", LocalDate.of(2026, 10, 24),
                LocalDate.of(2026, 10, 19), null, null, null, "CAM-0042",
                148320, false, nature, "Injecteur", "N", 148320, precedentAG,
                "Réseau urbain, 9 h par jour", "Bobine de commande grillée", "Client prévenu le 20",
                "OP-2115U", "Remplacement injecteur et contrôle du faisceau", 2.1, 70.0, 147.0,
                "HGR-INJ-04", "Injecteur common rail", "Groupe motopropulseur", "SN-77120",
                "Électrique", "2115U", 35.0);
    }

    /** La même saisie, un bloc de commande dessus. */
    private static SavCaseRequest avecCommande(SavCaseRequest b, PartOrderRequest commande) {
        return new SavCaseRequest(b.caseNumber(), b.type(), b.title(), b.vehicleId(), b.clientId(),
                b.status(), b.priority(), b.createdAt(), b.updatedAt(), b.description(), b.resolution(),
                b.technicianUserId(), b.requiresIntervention(), b.warrantyApprovalNumber(), b.costEstimate(),
                b.callerName(), b.callerChannel(), b.calledAt(), b.expectedAction(),
                b.plannedDate(), b.visitDate(), b.visitState(), b.nextActions(),
                b.soldPlanId(), b.planStepId(),
                b.declarationDate(), b.classification(), b.severity(), b.correctiveActions(),
                b.preventiveActions(), commande, b.agPaper());
    }

    /** La même saisie, un papier AG dessus. */
    private static SavCaseRequest avecPapier(SavCaseRequest b, AgPaperRequest papier) {
        return new SavCaseRequest(b.caseNumber(), b.type(), b.title(), b.vehicleId(), b.clientId(),
                b.status(), b.priority(), b.createdAt(), b.updatedAt(), b.description(), b.resolution(),
                b.technicianUserId(), b.requiresIntervention(), b.warrantyApprovalNumber(), b.costEstimate(),
                b.callerName(), b.callerChannel(), b.calledAt(), b.expectedAction(),
                b.plannedDate(), b.visitDate(), b.visitState(), b.nextActions(),
                b.soldPlanId(), b.planStepId(),
                b.declarationDate(), b.classification(), b.severity(), b.correctiveActions(),
                b.preventiveActions(), b.order(), papier);
    }

    // ------------------------------------------------------ la garde de suppression

    /**
     * DELETE ne doit pas détacher une sortie : effacer le dossier laisserait la ligne
     * pointer une fiche qui n'existe plus, sans bruit.
     */
    @Test
    void supprimerUnDossierEncoreSuiviParUneSortieEstRefuse() {
        Intervention sortie = new Intervention();
        sortie.setId("itv-1");
        sortie.setNumber("DP-2026-011");
        sortie.setSavCaseId(ID);
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.ON_SITE)));
        when(sorties.findBySavCaseIdOrderByReportedAtAsc(ID)).thenReturn(List.of(sortie));
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc(ID)).thenReturn(List.of());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> controller().delete(ID, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refus.getReason().contains("DP-2026-011"), refus.getReason());
        verify(savCases, never()).deleteById(anyString());
        verifyNoInteractions(audit);
    }

    /** Une demande suffit à retenir le dossier : sa décision est une écriture signée. */
    @Test
    void supprimerUnDossierEncoreSuiviParUneDemandeEstRefuse() {
        Mission demande = new Mission();
        demande.setId("mss-2");
        demande.setNumber("MT-2026-004");
        demande.setSavCaseId(ID);
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.APPROVED)));
        when(sorties.findBySavCaseIdOrderByReportedAtAsc(ID)).thenReturn(List.of());
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc(ID)).thenReturn(List.of(demande));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> controller().delete(ID, jwt()));

        assertTrue(refus.getReason().contains("MT-2026-004"), refus.getReason());
        verify(savCases, never()).deleteById(anyString());
    }

    /** Le dossier qui n'a plus de suite s'efface, et la trace en parle. */
    @Test
    void supprimerUnDossierSansSuiteEstEnregistre() {
        when(savCases.findById(ID)).thenReturn(Optional.of(dossier(SavCaseStatus.CLOSED)));
        when(sorties.findBySavCaseIdOrderByReportedAtAsc(ID)).thenReturn(List.of());
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc(ID)).thenReturn(List.of());

        controller().delete(ID, jwt());

        verify(savCases).deleteById(ID);
        verify(audit).record(eq("DELETE"), eq("SAV_CASE"), eq(ID), contains("CAS-001"), any());
    }
}
