package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.CallerChannel;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.SavPriority;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.SoldPlanRepository;
import tn.higer.sav_backend.repository.SoldPlanStepRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le lien sortie de dépannage → dossier SAV, côté serveur.
 *
 * Le but du module est de savoir qui est vraiment allé sur place : un dossier qui
 * suivrait un autre homme que la sortie serait un faux. Ces tests vérifient que le
 * nom vient toujours de la sortie, qu'un choix contradictoire est refusé et que le
 * refus explique qui est où, et qu'un dossier d'avant ne perd pas son nom quand on
 * l'enregistre sans désigner personne.
 *
 * La dernière série tient la garde de suppression : un dossier encore suivi par une
 * sortie ou par une demande ne s'efface pas, et le refus dit lequel.
 */
@ExtendWith(MockitoExtension.class)
class SavCaseServiceTest {

    @Mock private SavCaseRepository savCases;
    @Mock private InterventionRepository sorties;
    @Mock private MissionRepository demandes;
    @Mock private TechnicianRepository technicians;
    @Mock private TechnicianDirectory directory;
    @Mock private ItemPartService parts;
    @Mock private SoldPlanRepository forfaits;
    @Mock private SoldPlanStepRepository paliers;
    @Mock private AuditService audit;

    private static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID AUTRE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private SavCaseService service() {
        return new SavCaseService(savCases, sorties, demandes, technicians, directory, parts, audit, forfaits, paliers);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static SavCase dossier() {
        SavCase c = new SavCase();
        c.setId("sav-1");
        c.setCaseNumber("SAV-WAR-2026-004");
        c.setType(SavCaseType.GARANTIES);
        c.setTitle("Panne climatisation");
        return c;
    }

    private static Technician ali() {
        return fiche("tch-1", "Ali Trabelsi", ALI);
    }

    private static Technician salma() {
        return fiche("tch-2", "Salma Ben Ali", AUTRE);
    }

    private static Technician fiche(String id, String name, UUID userId) {
        Technician t = new Technician();
        t.setId(id);
        t.setName(name);
        t.setUserId(userId);
        t.setActive(true);
        return t;
    }

    private static Intervention sortie() {
        Intervention i = new Intervention();
        i.setId("itv-1");
        i.setNumber("DP-2026-007");
        i.setTechnicianId("tch-1");
        i.setReportedAt(LocalDateTime.now().minusDays(1));
        return i;
    }

    /** Le dossier est suivi par une sortie : le nom affiché vient de cette sortie. */
    private void withSortie(Intervention... rows) {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(rows));
        when(technicians.findById("tch-1")).thenReturn(Optional.of(ali()));
    }

    // ------------------------------------------------------------- héritage de la sortie

    @Test
    void leDossierPrendLEnvoyeDeLaSortieMemeSansChoix() {
        withSortie(sortie());
        SavCase c = dossier();

        service().applyTechnician(c, null, jwt());

        assertEquals(ALI, c.getTechnicianUserId());
        assertEquals("Ali Trabelsi", c.getTechnician());
        verify(directory, never()).ficheFor(any(), any());
    }

    @Test
    void designerLaMemePersonneQueLaSortiePasse() {
        withSortie(sortie());
        SavCase c = dossier();

        service().applyTechnician(c, ALI, jwt());

        assertEquals("Ali Trabelsi", c.getTechnician());
    }

    @Test
    void designerUnAutreHommeQueCeluiDeLaSortieEstRefuseEnFrancais() {
        withSortie(sortie());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().applyTechnician(dossier(), AUTRE, jwt()));
        // Le message nomme la sortie en cause : sinon l'atelier corrige au hasard.
        assertTrue(e.getReason().contains("DP-2026-007"), e.getReason());
        assertTrue(e.getReason().contains("Ali Trabelsi"), e.getReason());
        assertTrue(e.getReason().contains("Changez-le sur la sortie"), e.getReason());
    }

    @Test
    void laSortieLaPlusRecenteDictaNom() {
        Intervention ancienne = sortie();
        ancienne.setNumber("DP-2026-002");
        Intervention recente = sortie();
        recente.setId("itv-2");
        recente.setNumber("DP-2026-009");
        recente.setTechnicianId("tch-2");
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(ancienne, recente));
        when(technicians.findById("tch-2")).thenReturn(Optional.of(salma()));

        SavCase c = dossier();
        service().applyTechnician(c, null, jwt());

        assertEquals("Salma Ben Ali", c.getTechnician());
        assertEquals(AUTRE, c.getTechnicianUserId());
    }

    @Test
    void unNomLibreDAvantNeSeVidePasSansRetour() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());
        SavCase c = dossier();
        c.setTechnician("Sofiane Mebarki");

        service().applyTechnician(c, null, jwt());

        assertEquals("Sofiane Mebarki", c.getTechnician());
        assertNull(c.getTechnicianUserId());
    }

    @Test
    void saisieDAtelierSansSortiePrendLeCompteChoisi() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());
        when(directory.ficheFor(eq(AUTRE), any())).thenReturn(salma());

        SavCase c = dossier();
        service().applyTechnician(c, AUTRE, jwt());

        assertEquals(AUTRE, c.getTechnicianUserId());
    }

    @Test
    void laSortieDUnTechnicienSansFicheLaisseLeDossierALAtelier() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(sortie()));
        when(technicians.findById("tch-1")).thenReturn(Optional.empty());

        SavCase c = dossier();
        service().applyTechnician(c, null, jwt());

        assertNull(c.getTechnicianUserId());
    }

    // --------------------------------------------------- la sortie rejoint un dossier né avant

    @Test
    void unDossierSansHommeHeriteDeLaSortieQuonLuiRattache() {
        SavCase c = dossier();

        service().adoptTechnicianFrom(c, sortie(), ali());

        assertEquals(ALI, c.getTechnicianUserId());
        assertEquals("Ali Trabelsi", c.getTechnician());
        verify(savCases).save(c);
    }

    @Test
    void uneSortieQuiRejointLeBonHommeNEcritRien() {
        SavCase c = dossier();
        c.setTechnicianUserId(ALI);
        c.setTechnician("Ali Trabelsi");

        service().adoptTechnicianFrom(c, sortie(), ali());

        verify(savCases, never()).save(any());
    }

    @Test
    void deuxHommesDifferentSurLeTravailEstRefuse() {
        SavCase c = dossier();
        c.setTechnicianUserId(AUTRE);
        c.setTechnician("Salma Ben Ali");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().adoptTechnicianFrom(c, sortie(), ali()));
        assertTrue(e.getReason().contains("Salma Ben Ali"), e.getReason());
        assertTrue(e.getReason().contains("Ali Trabelsi"), e.getReason());
        assertTrue(e.getReason().contains("DP-2026-007"), e.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void uneSortieSansFicheNeVidePasLeDossier() {
        SavCase c = dossier();
        c.setTechnicianUserId(ALI);
        c.setTechnician("Ali Trabelsi");

        service().adoptTechnicianFrom(c, sortie(), null);

        assertEquals(ALI, c.getTechnicianUserId());
        verify(savCases, never()).save(any());
    }

    // --------------------------------------------------------- dossier créé depuis la sortie

    @Test
    void leDossierNeDeLaSortiePorteLEnvoyeDeLaSortie() {
        when(savCases.findNumbersByPrefix(any())).thenReturn(List.of());
        Technician tech = ali();

        SavCase created = service().createFromIntervention(sortie(), tech, SavCaseType.GARANTIES, jwt());

        assertEquals(ALI, created.getTechnicianUserId());
        assertEquals("Ali Trabelsi", created.getTechnician());
        assertTrue(created.getDescription().contains("DP-2026-007"), created.getDescription());
    }

    @Test
    void unDossierNeDuneSortieSansFicheResteALAtelier() {
        when(savCases.findNumbersByPrefix(any())).thenReturn(List.of());

        SavCase created = service().createFromIntervention(sortie(), null, null, jwt());

        assertNull(created.getTechnicianUserId());
        assertNull(created.getTechnician());
        assertEquals(SavCaseType.RECLAMATIONS, created.getType());
    }

    /**
     * Ses vraies données du 26/10 : la famille Réclamations porte « -029 » et un
     * compteur à quatre chiffres « -0028 ». L'ancien tri par longueur lisait « -0028 »
     * en premier, donnait 29, et le serveur écrivait un numéro déjà posé — l'écran
     * Dossiers SAV répondait 500.
     */
    @Test
    void laCreationReprendLePlusHautNumeroDeLaFamille() {
        String prefix = "SAV-REC-" + LocalDate.now().getYear() + "-";
        when(savCases.findNumbersByPrefix(any())).thenReturn(List.of(prefix + "029", prefix + "0028"));

        assertEquals(prefix + "0030", service().nextNumber(SavCaseType.RECLAMATIONS));
    }

    // ------------------------------------------------- le dossier avance avec la sortie
    /** Le dossier que la sortie suit, posé à l'état lu à l'écran. */
    private static SavCase dossierA(SavCaseStatus status) {
        SavCase c = dossier();
        c.setStatus(status);
        return c;
    }

    /** Une sortie rattachée au dossier « sav-1 », à l'état d'une étape du déroulé. */
    private static Intervention etape(InterventionStatus status, String id, String number) {
        Intervention i = sortie();
        i.setId(id);
        i.setNumber(number);
        i.setSavCaseId("sav-1");
        i.setStatus(status);
        return i;
    }

    /** La sortie qui parle est la seule rattachée au dossier. */
    private SavCase dossierEtUneSortie(SavCaseStatus actuel, Intervention... rows) {
        SavCase c = dossierA(actuel);
        when(savCases.findById("sav-1")).thenReturn(Optional.of(c));
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(rows));
        return c;
    }

    @Test
    void lAffectationDeLaVoitureMetLeDossierEnCours() {
        SavCase c = dossierEtUneSortie(SavCaseStatus.OPEN, etape(InterventionStatus.DISPATCHED, "itv-1", "DP-2026-007"));

        service().suivreSortie(etape(InterventionStatus.DISPATCHED, "itv-1", "DP-2026-007"), jwt());

        assertEquals(SavCaseStatus.IN_PROGRESS, c.getStatus(),
                "l'homme est parti : le dossier ne reste pas « Ouvert » à l'écran");
        verify(savCases).save(c);
    }

    @Test
    void lArriveeSurPlaceEtLeRetourALAtelierSeLisentSurLeDossier() {
        SavCase surPlace = dossierEtUneSortie(SavCaseStatus.IN_PROGRESS,
                etape(InterventionStatus.ON_SITE, "itv-1", "DP-2026-007"));
        service().suivreSortie(etape(InterventionStatus.ON_SITE, "itv-1", "DP-2026-007"), jwt());
        assertEquals(SavCaseStatus.ON_SITE, surPlace.getStatus());

        SavCase revenu = dossierEtUneSortie(SavCaseStatus.ON_SITE,
                etape(InterventionStatus.RETURNED, "itv-1", "DP-2026-007"));
        service().suivreSortie(etape(InterventionStatus.RETURNED, "itv-1", "DP-2026-007"), jwt());
        assertEquals(SavCaseStatus.RETURNED, revenu.getStatus(),
                "le technicien est rentré : le dossier le dit avant même la clôture du chef");
    }

    @Test
    void reparerSurPlaceNeChangeRienLeDossierEstDejaSurPlace() {
        SavCase c = dossierA(SavCaseStatus.ON_SITE);
        when(savCases.findById("sav-1")).thenReturn(Optional.of(c));

        service().suivreSortie(etape(InterventionStatus.REPAIRED, "itv-1", "DP-2026-007"), jwt());

        assertEquals(SavCaseStatus.ON_SITE, c.getStatus(),
                "même état : rien à écrire, rien au journal");
        verify(savCases, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void laClotureDeLaSortieClotLeDossier() {
        SavCase c = dossierEtUneSortie(SavCaseStatus.RETURNED,
                etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007"));

        service().suivreSortie(etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007"), jwt());

        assertEquals(SavCaseStatus.CLOSED, c.getStatus());
        assertEquals(LocalDate.now(), c.getClosedAt(),
                "la date de clôture s'écrit avec le statut : l'atelier ne la tape pas");
        verify(audit).record(eq("UPDATE"), eq("SAV_CASE"), eq("sav-1"),
                contains("« Retour à l'atelier » → « Clôturé »"), any());
    }

    @Test
    void uneSortieEncoreDehorsEmpecheLeDossierDeSeDireCloture() {
        SavCase c = dossierEtUneSortie(SavCaseStatus.RETURNED,
                etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007"),
                etape(InterventionStatus.ON_SITE, "itv-2", "DP-2026-009"));

        service().suivreSortie(etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007"), jwt());

        assertEquals(SavCaseStatus.RETURNED, c.getStatus(),
                "DP-2026-009 est encore dehors : écrire « Clôturé » serait un faux dans une pièce Higer");
        verify(savCases, never()).save(any());
        verify(audit).record(eq("UPDATE"), eq("SAV_CASE"), eq("sav-1"), contains("DP-2026-009"), any());
    }

    @Test
    void uneEtapeDeSortieNEcrasePasUneDecisionDAtelier() {
        SavCase c = dossierEtUneSortie(SavCaseStatus.PENDING_PARTS,
                etape(InterventionStatus.ON_SITE, "itv-1", "DP-2026-007"));

        service().suivreSortie(etape(InterventionStatus.ON_SITE, "itv-1", "DP-2026-007"), jwt());

        assertEquals(SavCaseStatus.PENDING_PARTS, c.getStatus(),
                "l'attente pièces est une décision, elle passe devant l'état d'une sortie");
        verify(savCases, never()).save(any());
    }

    @Test
    void laClotureVientAuDessusDuneDecision() {
        SavCase c = dossierEtUneSortie(SavCaseStatus.APPROVED,
                etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007"));

        service().suivreSortie(etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007"), jwt());

        assertEquals(SavCaseStatus.CLOSED, c.getStatus(), "accord donné et travail fini : le dossier se ferme");
    }

    @Test
    void uneSortiePlusAncienneNeFaitPasReculerLeDossier() {
        Intervention ancienne = etape(InterventionStatus.DISPATCHED, "itv-1", "DP-2026-007");
        ancienne.setReportedAt(LocalDateTime.now().minusDays(3));
        Intervention recente = etape(InterventionStatus.CLOSED, "itv-2", "DP-2026-009");
        recente.setReportedAt(LocalDateTime.now());
        SavCase c = dossierEtUneSortie(SavCaseStatus.CLOSED, ancienne, recente);

        service().suivreSortie(ancienne, jwt());

        assertEquals(SavCaseStatus.CLOSED, c.getStatus(),
                "DP-2026-007 n'est pas la sortie en cours du dossier : elle ne le remet pas en route");
        verify(savCases, never()).save(any());
    }

    @Test
    void laSortieEnCoursDuDossierLeRouvre() {
        Intervention finie = etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007");
        finie.setReportedAt(LocalDateTime.now().minusDays(3));
        Intervention nouvelle = etape(InterventionStatus.ON_SITE, "itv-2", "DP-2026-009");
        nouvelle.setReportedAt(LocalDateTime.now());
        SavCase c = dossierEtUneSortie(SavCaseStatus.CLOSED, finie, nouvelle);
        c.setClosedAt(LocalDate.now().minusDays(1));

        service().suivreSortie(nouvelle, jwt());

        assertEquals(SavCaseStatus.ON_SITE, c.getStatus(),
                "une nouvelle sortie sur un dossier fermé : c'est son travail qui parle");
        assertNull(c.getClosedAt(), "un dossier reparti n'est plus clos : sa date de clôture se retire");
    }

    @Test
    void uneSortieSansDossierRattacheNeBloqueRien() {
        Intervention i = etape(InterventionStatus.CLOSED, "itv-1", "DP-2026-007");
        i.setSavCaseId(null);

        service().suivreSortie(i, jwt());

        verify(savCases, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    // ------------------------------------------- la réclamation enfante le dossier garantie

    /** Une réclamation du client, telle qu'elle est restée après l'appel. */
    private static SavCase reclamation() {
        SavCase c = new SavCase();
        c.setId("sav-1");
        c.setCaseNumber("SAV-REC-2026-004");
        c.setType(SavCaseType.RECLAMATIONS);
        c.setTitle("Climatisation hors service ligne 4");
        c.setVehicleId("veh-9");
        c.setClientId("cli-sntri");
        c.setStatus(SavCaseStatus.CLOSED);
        c.setPriority(SavPriority.HIGH);
        c.setDescription("Le client signale que la clim ne fonctionne plus depuis 3 jours.");
        c.setExpectedAction("Intervention avant vendredi");
        c.setCallerName("M. Bouazizi");
        c.setCallerChannel(CallerChannel.TELEPHONE);
        c.setCalledAt(LocalDateTime.now().minusDays(4));
        c.setTechnicianUserId(ALI);
        c.setTechnician("Ali Trabelsi");
        c.setWarrantyApprovalNumber("HIG-2026-77");
        return c;
    }

    /** Le serveur cherche un claim déjà né et n'en trouve aucun ; le numéro suit la famille. */
    private SavCaseService porteLibre() {
        when(savCases.findFirstByParentCaseIdAndType("sav-1", SavCaseType.GARANTIES))
                .thenReturn(Optional.empty());
        when(savCases.findNumbersByPrefix(any())).thenReturn(List.of());
        return service();
    }

    @Test
    void laReclamationEnfanteUnDossierGarantieSansCesserDEtreUneReclamation() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());

        SavCase claim = porteLibre().openClaimFrom(reclamation(), jwt());

        assertEquals(SavCaseType.GARANTIES, claim.getType());
        assertEquals("sav-1", claim.getParentCaseId(), "le claim sait de quelle réclamation il est né");
        assertTrue(claim.getCaseNumber().matches("SAV-WAR-\\d{4}-001"), claim.getCaseNumber());
        assertEquals(ClaimState.A_DEPOSER, claim.getClaimState(),
                "rien n'est encore demandé au constructeur : ça s'écrit, ça ne se devine pas");
        assertTrue(claim.getTitle().startsWith("Claim garantie — "), claim.getTitle());
    }

    @Test
    void laDemandeDuClientSuitLeClaim() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());

        SavCase parent = reclamation();
        SavCase claim = porteLibre().openClaimFrom(parent, jwt());

        assertEquals("M. Bouazizi", claim.getCallerName());
        assertEquals(CallerChannel.TELEPHONE, claim.getCallerChannel());
        assertEquals(parent.getCalledAt(), claim.getCalledAt());
        assertEquals("Intervention avant vendredi", claim.getExpectedAction());
        assertEquals("cli-sntri", claim.getClientId());
        assertEquals("veh-9", claim.getVehicleId());
        assertEquals(SavPriority.HIGH, claim.getPriority());
        assertEquals("HIG-2026-77", claim.getWarrantyApprovalNumber());
        assertEquals(ALI, claim.getTechnicianUserId(), "c'est l'homme de la réclamation qui a fait le travail");
        // Le papier déposé chez le constructeur doit se lire sans rouvrir la réclamation.
        assertTrue(claim.getDescription().contains("SAV-REC-2026-004"), claim.getDescription());
        assertTrue(claim.getDescription().contains("M. Bouazizi"), claim.getDescription());
        assertTrue(claim.getDescription().contains("Téléphone"), claim.getDescription());
        assertTrue(claim.getDescription().contains("Le client attend : Intervention avant vendredi"),
                claim.getDescription());
    }

    @Test
    void unSeulClaimParReclamation() {
        SavCase dejaNe = reclamation();
        dejaNe.setId("sav-7");
        dejaNe.setCaseNumber("SAV-WAR-2026-002");
        when(savCases.findFirstByParentCaseIdAndType("sav-1", SavCaseType.GARANTIES))
                .thenReturn(Optional.of(dejaNe));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().openClaimFrom(reclamation(), jwt()));

        // Le message nomme le dossier qui existe : sinon le poste cherche lequel.
        assertTrue(e.getReason().contains("SAV-WAR-2026-002"), e.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void unAppelEnGarantieNeSeDemandePasDepuisUneVisite() {
        SavCase visite = reclamation();
        visite.setType(SavCaseType.VISITES);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().openClaimFrom(visite, jwt()));

        assertTrue(e.getReason().contains("n'est pas une réclamation"), e.getReason());
    }

    @Test
    void lesPiecesPoseesPendantLaSortieSuiventLeClaim() {
        Intervention sortie = sortie();
        sortie.setSavCaseId("sav-1");
        sortie.setMaintenanceItemId("mi-3");
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(sortie));
        when(parts.rattacherAuDossier(eq("mi-3"), any())).thenReturn(2);
        SavCaseService neuf = porteLibre();

        SavCase claim = neuf.openClaimFrom(reclamation(), jwt());

        ArgumentCaptor<String> dossier = ArgumentCaptor.forClass(String.class);
        verify(parts).rattacherAuDossier(eq("mi-3"), dossier.capture());
        assertEquals(claim.getId(), dossier.getValue(),
                "les pièces sont rattachées au claim qui vient de naître, pas à la réclamation");
        verify(audit).record(eq("CREATE"), eq("SAV_CASE"), eq(claim.getId()),
                contains("2 pièce(s) posée(s) rattachée(s)"), any());
    }

    @Test
    void uneReclamationSansSortieNeRattacheRien() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());

        SavCase claim = porteLibre().openClaimFrom(reclamation(), jwt());

        verify(parts, never()).rattacherAuDossier(any(), any());
        verify(savCases).save(claim);
        verify(audit).record(eq("CREATE"), eq("SAV_CASE"), eq(claim.getId()),
                contains("SAV-WAR"), any());
    }

    // ---------------------------------------------------- la garde de suppression

    private static Mission demande(String number) {
        Mission m = new Mission();
        m.setId("mss-" + number);
        m.setNumber(number);
        m.setSavCaseId("sav-1");
        return m;
    }

    /** Une sortie porte le dossier : elle a des heures, un homme, une prime. */
    @Test
    void uneSortieEncoreAttacheeRetientLeDossier() {
        Intervention sortie = sortie();
        sortie.setSavCaseId("sav-1");
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(sortie));
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc("sav-1")).thenReturn(List.of());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().verifierSuppression(dossier()));

        assertTrue(refus.getReason().contains("DP-2026-007"), refus.getReason());
        assertTrue(refus.getReason().contains("SAV-WAR-2026-004"), refus.getReason());
    }

    /** Une demande suffit à retenir le dossier : sa décision est signée et datée. */
    @Test
    void uneDemandeEncoreAttacheeRetientLeDossier() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc("sav-1"))
                .thenReturn(List.of(demande("MT-2026-003")));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().verifierSuppression(dossier()));

        assertTrue(refus.getReason().contains("MT-2026-003"), refus.getReason());
        // Le refus dit aussi ce qu'on attend de celui qui veut effacer.
        assertTrue(refus.getReason().contains("Clôturez"), refus.getReason());
    }

    /** Les deux se nomment dans le même refus : celui qui efface doit tout clore. */
    @Test
    void laSortieEtLaDemandeSeNommentDansUnSeulRefus() {
        Intervention sortie = sortie();
        sortie.setSavCaseId("sav-1");
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(sortie));
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc("sav-1"))
                .thenReturn(List.of(demande("MT-2026-009")));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().verifierSuppression(dossier()));

        assertTrue(refus.getReason().contains("DP-2026-007 et la demande MT-2026-009"), refus.getReason());
    }

    /** Le dossier qui n'a plus de suite part : la garde ne doit pas devenir un verrou. */
    @Test
    void unDossierSansSortieNiDemandeSeSupprimeSansRefus() {
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());
        when(demandes.findBySavCaseIdOrderByCreatedAtDesc("sav-1")).thenReturn(List.of());

        assertDoesNotThrow(() -> service().verifierSuppression(dossier()));
    }

    // --------------------------------------------------- reprendre l'homme de la sortie

    /**
     * Le champ de la fiche est verrouillé et la sortie partie ne se retire plus :
     * sans cette porte, un dossier portant le nom d'un autre que celui du terrain ne
     * se corrigeait nulle part. Elle écrit le nom de la sortie et le dit au journal.
     */
    @Test
    void leDossierReprendLEnvoyeDeLaSortieEtLeJournalise() {
        SavCase c = dossier();
        c.setTechnicianUserId(AUTRE);
        c.setTechnician("aya");
        when(savCases.findById("sav-1")).thenReturn(Optional.of(c));
        withSortie(sortie());

        SavCase maj = service().reprendreTechnicienDeLaSortie("sav-1", jwt());

        assertEquals(ALI, maj.getTechnicianUserId());
        assertEquals("Ali Trabelsi", maj.getTechnician());
        verify(savCases).save(c);
        // La trace nomme la sortie et le nom qu'elle remplace : le papier déposé chez
        // le constructeur ne se réécrit pas sans que l'on sache d'où vient le nouveau.
        verify(audit).record(eq("UPDATE"), eq("SAV_CASE"), eq("sav-1"),
                contains("DP-2026-007"), any());
        verify(audit).record(eq("UPDATE"), eq("SAV_CASE"), eq("sav-1"),
                contains("aya → Ali Trabelsi"), any());
    }

    /** Rien à reprendre : le dossier dit déjà le nom de sa sortie, et ne s'écrit pas deux fois. */
    @Test
    void unDossierDejaAligneSurSaSortieNeSeReecritPas() {
        SavCase c = dossier();
        c.setTechnicianUserId(ALI);
        c.setTechnician("Ali Trabelsi");
        when(savCases.findById("sav-1")).thenReturn(Optional.of(c));
        withSortie(sortie());

        service().reprendreTechnicienDeLaSortie("sav-1", jwt());

        verify(savCases, never()).save(any());
    }

    /** Un dossier sans sortie garde le choix d'atelier : la porte ne vaut que pour une sortie. */
    @Test
    void unDossierSansSortieNePeutRienReprendre() {
        when(savCases.findById("sav-1")).thenReturn(Optional.of(dossier()));
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of());

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().reprendreTechnicienDeLaSortie("sav-1", jwt()));
        assertTrue(refus.getReason().contains("ne suit aucune sortie"), refus.getReason());
    }

    /** Une sortie sans homme ne donne pas de nom : le dossier reste ce qu'il est. */
    @Test
    void uneSortieSansHommeNeSeReprendPas() {
        Intervention sansHomme = sortie();
        sansHomme.setTechnicianId(null);
        when(savCases.findById("sav-1")).thenReturn(Optional.of(dossier()));
        when(sorties.findBySavCaseIdOrderByReportedAtAsc("sav-1")).thenReturn(List.of(sansHomme));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().reprendreTechnicienDeLaSortie("sav-1", jwt()));
        assertTrue(refus.getReason().contains("n'a pas d'homme désigné"), refus.getReason());
    }
}
