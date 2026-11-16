package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPieceJointe;
import tn.higer.sav_backend.dto.SavCaseDtos.AgSortieJointe;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionPartRequest;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.ItemPart;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionPartRequestRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.ItemPartRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Le dossier d'appel en garantie relu pour le constructeur. Ce qui est testé ici
 * n'est pas la mise en page mais les trois interdits du module : le rattachement se
 * lit et ne se recopie pas, aucun montant n'est calculé, et ce qui ne dépend pas de
 * nous (les codes que seul Higer détient) devient un signe, jamais une faute imputée
 * à l'atelier.
 *
 * Chaque refus est vérifié avec sa raison en français : c'est elle que le guichet lit
 * à l'écran avant de déposer le papier.
 */
@ExtendWith(MockitoExtension.class)
class AgPaperAssemblyServiceTest {

    private static final String DOSSIER_ID = "sav-1";
    private static final String BUS_ID = "veh-1";

    @Mock private SavCaseRepository savCases;
    @Mock private InterventionRepository interventions;
    @Mock private InterventionPartRequestRepository demandesDePiece;
    @Mock private ItemPartRepository piecesMontees;
    @Mock private MaintenanceRepository papiers;
    @Mock private VehicleRepository vehicles;

    private AgPaperAssemblyService service() {
        return new AgPaperAssemblyService(savCases, interventions, demandesDePiece,
                piecesMontees, papiers, vehicles);
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** Un appel en garantie vide : chaque test ne remplit que la case qui l'intéresse. */
    private static SavCase claim() {
        SavCase c = new SavCase();
        c.setId(DOSSIER_ID);
        c.setCaseNumber("SAV-WAR-2026-004");
        c.setType(SavCaseType.GARANTIES);
        return c;
    }

    private static Intervention sortie(String number, InterventionStatus status) {
        Intervention i = new Intervention();
        i.setId("itv-" + number);
        i.setNumber(number);
        i.setStatus(status);
        i.setSavCaseId(DOSSIER_ID);
        i.setReportedAt(LocalDateTime.now().minusDays(2));
        return i;
    }

    private static ItemPart pieceSurPapier(String reference, String designation, boolean repartie) {
        ItemPart p = new ItemPart();
        p.setId("ipt-1");
        p.setMaintenanceItemId("mti-1");
        p.setSavCaseId(DOSSIER_ID);
        p.setReference(reference);
        p.setDesignation(designation);
        p.setQuantity(1.0);
        p.setFittedOn(LocalDate.of(2026, 10, 12));
        p.setReturned(repartie);
        if (repartie) {
            p.setReturnedAt(LocalDate.of(2026, 10, 20));
        }
        return p;
    }

    private static InterventionPartRequest pieceVueDehors(PartRequestState state) {
        InterventionPartRequest p = new InterventionPartRequest();
        p.setId("ipr-1");
        p.setInterventionId("itv-DP-2026-011");
        p.setSavCaseId(DOSSIER_ID);
        p.setReference("37300-10300");
        p.setDesignation("Alternateur 28V 120A");
        p.setQuantityOrdered(1);
        p.setState(state);
        p.setCreatedAt(LocalDateTime.now().minusDays(3));
        if (state == PartRequestState.POSEE) {
            p.setFittedAt(LocalDateTime.now().minusDays(1));
            p.setFittedInterventionId("itv-DP-2026-014");
        }
        return p;
    }

    private SavCase dossierQuiRepond(SavCase c) {
        when(savCases.findById(DOSSIER_ID)).thenReturn(java.util.Optional.of(c));
        return c;
    }

    // --------------------------------------------------------------- la garde

    @Test
    void unCompteDeConsultationNeLitPasLeDossierDeHiger() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().relire(DOSSIER_ID, auth("CONSULTATION")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(e.getReason().contains("guichet"), e.getReason());
        // La liste des sorties non plus : personne n'a été chercher le dossier.
        verifyNoInteractions(interventions, piecesMontees, demandesDePiece);
    }

    @Test
    void unCompteDeTechnicienNeLitPasLeDossierDeHiger() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().relire(DOSSIER_ID, auth("TECHNICIEN")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verifyNoInteractions(savCases);
    }

    @Test
    void unDossierIntrouvableRepond404EtNonUnePageVide() {
        when(savCases.findById(DOSSIER_ID)).thenReturn(java.util.Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().relire(DOSSIER_ID, auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    /**
     * Une réclamation, une visite, une commande de pièces : ces familles-là n'ont pas de
     * papier AG. Répondre une page vide ferait croire que le dossier est prêt à déposer ;
     * le refus nomme le dossier et dit pourquoi il n'a rien à justifier.
     */
    @Test
    void uneAutreFamilleDeDossierEstRefuseeParSonNumero() {
        SavCase c = claim();
        c.setType(SavCaseType.RECLAMATIONS);
        dossierQuiRepond(c);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().relire(DOSSIER_ID, auth("CHEF_ATELIER")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("SAV-WAR-2026-004"), e.getReason());
        assertTrue(e.getReason().contains("pas un appel en garantie"), e.getReason());
        verifyNoInteractions(interventions, piecesMontees, demandesDePiece, papiers, vehicles);
    }

    // ---------------------------------------------------------------- les lignes

    @Test
    void lesSortiesDuDossierSontReluesParLeurNumeroEtLeurCloture() {
        dossierQuiRepond(claim());
        Intervention cloturee = sortie("DP-2026-011", InterventionStatus.CLOSED);
        cloturee.setClosedAt(LocalDateTime.now().minusDays(1));
        cloturee.setBusMileage(148320L);
        cloturee.setBusMileageEnd(148371L);
        cloturee.setDefectCode("P0450");
        cloturee.setCausingPart("Alternateur");
        Intervention enCours = sortie("DP-2026-012", InterventionStatus.ON_SITE);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID))
                .thenReturn(List.of(cloturee, enCours));
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());

        AgPapierJustifie papier = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertEquals(List.of("DP-2026-011", "DP-2026-012"),
                papier.sorties().stream().map(AgSortieJointe::number).toList());
        assertTrue(papier.sorties().get(0).terminee());
        assertFalse(papier.sorties().get(1).terminee(),
                "une sortie encore dehors n'est pas une réparation prouvée");
        assertEquals(148320L, papier.sorties().get(0).kmReleve());
        assertEquals("P0450", papier.sorties().get(0).defectCode());
        assertEquals("Alternateur", papier.sorties().get(0).causingPart());
    }

    /** Le papier d'atelier né d'une sortie est nommé sur la ligne : le guichet le recopie. */
    @Test
    void lePapierDAtelierNeDuneSortieEstNommeSansEtreRetape() {
        SavCase c = claim();
        c.setVehicleId(BUS_ID);
        dossierQuiRepond(c);
        Intervention i = sortie("DP-2026-011", InterventionStatus.CLOSED);
        i.setMaintenanceItemId("mti-1");
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of(i));
        MaintenanceItem papier = new MaintenanceItem();
        papier.setId("mti-1");
        papier.setNumber("OR-2026-004");
        when(papiers.findAllById(List.of("mti-1"))).thenReturn(List.of(papier));
        ItemPart p = pieceSurPapier("37300-10300", "Alternateur 28V 120A", true);
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of(p));
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());
        when(vehicles.findById(BUS_ID)).thenReturn(java.util.Optional.empty());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("GESTIONNAIRE_SAV"));

        assertEquals("OR-2026-004", lu.sorties().get(0).atelierPaperNumber());
        assertEquals("OR-2026-004", lu.pieces().get(0).atelierPaperNumber());
        assertEquals("DP-2026-011", lu.pieces().get(0).sortieNumber(),
                "la pièce retrouve la sortie qui a fait naître son papier");
    }

    /**
     * Les deux maisons d'une pièce — montée sur un papier d'atelier, montée pendant une
     * sortie — tiennent sur une seule liste à l'écran. Une pièce vue dehors mais restée
     * au magasin y figure avec son état, pas avec un mot inventé par le portail.
     */
    @Test
    void lesPiecesMonteesSurPapierEtCellesVuesEnSortieSontSurLaMemeListe() {
        dossierQuiRepond(claim());
        Intervention i = sortie("DP-2026-011", InterventionStatus.CLOSED);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of(i));
        when(piecesMontees.findBySavCaseId(DOSSIER_ID))
                .thenReturn(List.of(pieceSurPapier("REF-PAPIER", "Courroie", true)));
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(List.of("itv-DP-2026-011")))
                .thenReturn(List.of(pieceVueDehors(PartRequestState.POSEE)));
        Intervention pose = sortie("DP-2026-014", InterventionStatus.CLOSED);
        when(interventions.findAllById(any())).thenReturn(List.of(pose));

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("ADMIN"));

        assertEquals(2, lu.pieces().size());
        AgPieceJointe enSortie = lu.pieces().get(1);
        assertEquals("montee_en_sortie", enSortie.etat());
        assertEquals("Posée", enSortie.etatLabel());
        assertEquals("DP-2026-014", enSortie.sortieNumber());
        assertEquals("37300-10300 (Alternateur 28V 120A)", enSortie.designation(),
                "le nom que le constructeur écrit : la référence devant la désignation");
        assertNull(enSortie.retourEnvoye(),
                "le portail n'a pas de trace de retour pour une pièce posée en sortie : il ne dit pas qu'elle est repartie");
    }

    /**
     * Une pièce posée par une seconde sortie — celle de la mission « changer la pièce » —
     * n'est pas toujours rattachée au dossier. Relue d'un bloc, jamais ligne par ligne :
     * cent pièces ne cent pas lectures de sortie.
     */
    @Test
    void laSortieDePoseEstRelueUneSeuleFoisPourToutesLesPieces() {
        dossierQuiRepond(claim());
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID))
                .thenReturn(List.of(sortie("DP-2026-011", InterventionStatus.CLOSED)));
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());
        InterventionPartRequest a = pieceVueDehors(PartRequestState.POSEE);
        InterventionPartRequest b = pieceVueDehors(PartRequestState.POSEE);
        b.setId("ipr-2");
        b.setReference("REF-2");
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList()))
                .thenReturn(List.of(a, b));
        when(interventions.findAllById(any())).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        verify(interventions, times(1)).findAllById(any());
        assertEquals(2, lu.pieces().size());
        assertNull(lu.pieces().get(0).sortieNumber(),
                "la sortie de pose est inconnue : rien n'est inventé pour remplir la case");
    }

    // ------------------------------------------------------------- le verdict

    @Test
    void unDossierSansSortieNEstJamaisPretALeser() {
        SavCase c = claim();
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        c.setAgRepairOrderNumber("OR-2026-004");
        c.setAgRepairOrderDate(LocalDate.of(2026, 10, 11));
        c.setAgKm(148320);
        c.setResolution("Alternateur remplacé");
        c.setAgCause("Défaut de fabrication");
        c.setAgCausalPartReference("37300-10300");
        c.setClaimAmountClaimed(720.0);
        dossierQuiRepond(c);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertFalse(lu.pret());
        assertTrue(lu.manquants().stream().anyMatch(m -> m.contains("Aucune sortie")), lu.manquants().toString());
    }

    /** Les cases vides sont nommées avec la case du papier, pas la colonne de base. */
    @Test
    void lesCasesVidesDuPapierSontNommeesUneParUne() {
        dossierQuiRepond(claim());
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());

        List<String> manquants = service().relire(DOSSIER_ID, auth("CHEF_ATELIER")).manquants();

        assertTrue(manquants.stream().anyMatch(m -> m.contains("date de l'incident")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("ordre de travail")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("compteur du bus")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("correction faite")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("cause de la panne")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("pièce en cause")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("montant demandé")), manquants.toString());
    }

    /**
     * Ce que l'atelier a déjà écrit ne lui est pas redemandé : le compteur relevé devant
     * le bus et le papier né de la sortie sont récités dans le refus, avec leur valeur.
     */
    @Test
    void ceQuiEstDejaEcritAilleursEstRecitePlutotQueReinvente() {
        SavCase c = claim();
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        dossierQuiRepond(c);
        Intervention i = sortie("DP-2026-011", InterventionStatus.CLOSED);
        i.setBusMileage(148320L);
        i.setMaintenanceItemId("mti-9");
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of(i));
        when(papiers.findAllById(any())).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());

        List<String> manquants = service().relire(DOSSIER_ID, auth("CHEF_ATELIER")).manquants();

        assertTrue(manquants.stream().anyMatch(m -> m.contains("DP-2026-011 a relevé 148320 km")
                && m.contains("recopiez-les")), manquants.toString());
        assertTrue(manquants.stream().anyMatch(m -> m.contains("DP-2026-011 a déjà donné un papier d'atelier")
                && m.contains("recopiez-en le numéro")), manquants.toString());
    }

    /** Une pièce qui dort au magasin n'est pas un travail fini : le papier l'attend. */
    @Test
    void unePiecePasEncoreMonteeEstUnManquant() {
        dossierQuiRepond(claim());
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID))
                .thenReturn(List.of(sortie("DP-2026-011", InterventionStatus.CLOSED)));
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList()))
                .thenReturn(List.of(pieceVueDehors(PartRequestState.COMMANDEE)));

        List<String> manquants = service().relire(DOSSIER_ID, auth("CHEF_ATELIER")).manquants();

        assertTrue(manquants.stream().anyMatch(m -> m.contains("37300-10300")
                && m.contains("Commandée") && m.contains("pas encore montée")), manquants.toString());
    }

    /**
     * La pièce morte doit repartir chez le constructeur, sinon il annule le
     * remboursement. Le motif écrit à l'atelier est récité : c'est lui qui décide, pas
     * le portail.
     */
    @Test
    void unePieceMorteQuiNestPasRepartieEstUnManquantAvecSonMotif() {
        SavCase c = claim();
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        c.setAgRepairOrderNumber("OR-2026-004");
        c.setAgRepairOrderDate(LocalDate.of(2026, 10, 11));
        c.setAgKm(148320);
        c.setResolution("Remplacement");
        c.setAgCause("Défaut de fabrication");
        c.setAgCausalPartReference("37300-10300");
        c.setClaimAmountClaimed(720.0);
        dossierQuiRepond(c);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID))
                .thenReturn(List.of(sortie("DP-2026-011", InterventionStatus.CLOSED)));
        ItemPart garde = pieceSurPapier("37300-10300", "Alternateur 28V 120A", false);
        garde.setReturnReason("Consignée au magasin, en attente d'enlèvement par l'importateur");
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of(garde));
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertFalse(lu.pret());
        assertTrue(lu.manquants().stream().anyMatch(m -> m.contains("n'est pas repartie")
                && m.contains("attente d'enlèvement")), lu.manquants().toString());
    }

    /** Rien ne manque : le dossier est prêt, mais c'est un humain qui signe. */
    @Test
    void unDossierCompletEstDeclarePret() {
        SavCase c = claim();
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        c.setAgRepairOrderNumber("OR-2026-004");
        c.setAgRepairOrderDate(LocalDate.of(2026, 10, 11));
        c.setAgKm(148320);
        c.setResolution("Alternateur remplacé, charge mesurée à 28,4 V");
        c.setAgCause("Défaut de fabrication du régulateur interne");
        c.setAgCausalPartReference("37300-10300");
        c.setClaimAmountClaimed(720.0);
        c.setAgImporter("Saphir L.T.D");
        c.setAgDealerNumber("TN-014");
        c.setAgUnitNumber("U-8812");
        c.setAgCamNumber("CAM-2215U");
        c.setAgDefectCode("2115U");
        dossierQuiRepond(c);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID))
                .thenReturn(List.of(sortie("DP-2026-011", InterventionStatus.CLOSED)));
        when(piecesMontees.findBySavCaseId(DOSSIER_ID))
                .thenReturn(List.of(pieceSurPapier("37300-10300", "Alternateur", true)));
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertTrue(lu.manquants().isEmpty(), lu.manquants().toString());
        assertTrue(lu.pret());
        assertTrue(lu.signes().isEmpty(), lu.signes().toString());
        assertEquals("SAV-WAR-2026-004", lu.caseNumber());
    }

    /**
     * Le bus sorti de sa garantie n'est pas une faute du portail : les deux dates sont
     * récitées et c'est l'atelier qui juge laquelle est fausse. Un refus aurait
     * empêché de déposer quand même — un appel accordé sur un bus apparenté hors
     * garantie arrive.
     */
    @Test
    void unBusHorsGarantieEstUnSignePasUnManquant() {
        SavCase c = claim();
        c.setVehicleId(BUS_ID);
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        dossierQuiRepond(c);
        Vehicle bus = new Vehicle();
        bus.setId(BUS_ID);
        bus.setWarrantyEndDate(LocalDate.of(2026, 9, 30));
        when(vehicles.findById(BUS_ID)).thenReturn(java.util.Optional.of(bus));
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertTrue(lu.signes().stream().anyMatch(s -> s.contains("garantie finie le 30/09/2026")
                && s.contains("l'incident est daté du 10/10/2026")), lu.signes().toString());
        assertTrue(lu.manquants().stream().noneMatch(m -> m.contains("garantie finie")),
                lu.manquants().toString());
    }

    /**
     * Les codes que seul le constructeur détient ne s'inventent pas : ils viennent de sa
     * réponse. Le portail les signale au guichet, il n'en fait pas une faute de
     * l'atelier — et il ne remplit rien.
     */
    @Test
    void lesCodesDuConstructeurSontSignauxPasComptes() {
        SavCase c = claim();
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        dossierQuiRepond(c);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertTrue(lu.signes().stream().anyMatch(s -> s.contains("importateur")
                && s.contains("rien n'est inventé")), lu.signes().toString());
        assertTrue(lu.manquants().stream().noneMatch(m -> m.contains("importateur")),
                lu.manquants().toString());
    }

    /**
     * Une pièce posée par une sortie n'a aucune trace de retour dans le portail : seul
     * le papier d'atelier en porte une. Le dire ici évite le pire — un papier déposé en
     * croyant la preuve faite — sans bloquer le dépôt pour autant.
     */
    @Test
    void unePiecePoseeEnSortieSignaleLeManqueDePreuveDeRetour() {
        dossierQuiRepond(claim());
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID))
                .thenReturn(List.of(sortie("DP-2026-011", InterventionStatus.CLOSED)));
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());
        when(demandesDePiece.findByInterventionIdInOrderByCreatedAtAsc(anyList()))
                .thenReturn(List.of(pieceVueDehors(PartRequestState.POSEE)));
        when(interventions.findAllById(any())).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertTrue(lu.signes().stream().anyMatch(s -> s.contains("preuve que la pièce morte est repartie")
                && s.contains("reste à produire ailleurs")), lu.signes().toString());
        assertTrue(lu.manquants().stream().noneMatch(m -> m.contains("pas encore montée")),
                "posée pendant la sortie est un travail fini, pas un travail en attente");
    }

    /** Un appel déjà accordé ne demande plus rien : le papier devient une trace. */
    @Test
    void unAppelDejaAccordeEstSignaleCommeTel() {
        SavCase c = claim();
        c.setAgIncidentDate(LocalDate.of(2026, 10, 10));
        c.setClaimState(ClaimState.ACCORDE);
        dossierQuiRepond(c);
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());

        assertTrue(service().relire(DOSSIER_ID, auth("ADMIN")).signes().stream()
                .anyMatch(s -> s.contains("encaisser l'avoir")));
    }

    /**
     * La date de l'incident se lit sur le papier, à défaut sur l'appel du client — jamais
     * « aujourd'hui ». Une date devinée ferait entrer dans la garantie un bus qui en est
     * sorti, et c'est précisément le redressement que le constructeur prononce.
     */
    @Test
    void lAppelDuClientTientLieuDeDateDIncident() {
        SavCase c = claim();
        c.setVehicleId(BUS_ID);
        c.setCalledAt(LocalDateTime.of(2026, 10, 10, 9, 0));
        dossierQuiRepond(c);
        Vehicle bus = new Vehicle();
        bus.setWarrantyEndDate(LocalDate.of(2026, 9, 30));
        when(vehicles.findById(BUS_ID)).thenReturn(java.util.Optional.of(bus));
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID)).thenReturn(List.of());

        AgPapierJustifie lu = service().relire(DOSSIER_ID, auth("CHEF_ATELIER"));

        assertTrue(lu.manquants().stream().noneMatch(m -> m.contains("date de l'incident")),
                lu.manquants().toString());
        assertTrue(lu.signes().stream().anyMatch(s -> s.contains("10/10/2026")), lu.signes().toString());
    }

    // ------------------------------------------------------- aucun montant, jamais

    /**
     * Le barème de remboursement de Higer n'a jamais été fourni. Un total fabriqué par
     * le portail engagerait quelqu'un qui ne l'a pas décidé : ce que cette lecture rend
     * est un compte de lignes et des cases vides nommées, jamais une addition. Le test
     * interdit par réflexion toute case monétaire dans les trois DTO, y compris demain.
     */
    @Test
    void laLectureDeHigerNeDeclareAucunChampMonetaire() {
        for (Class<?> dto : List.of(AgPapierJustifie.class, AgSortieJointe.class, AgPieceJointe.class)) {
            for (RecordComponent c : dto.getRecordComponents()) {
                Type t = c.getType();
                assertNotEquals(BigDecimal.class, t, dto.getSimpleName() + "." + c.getName());
                assertFalse(c.getName().toLowerCase().contains("montant"), c.getName());
                assertFalse(c.getName().toLowerCase().contains("total"), c.getName());
                assertFalse(c.getName().toLowerCase().contains("prix"), c.getName());
                assertFalse(c.getName().toLowerCase().contains("totaux"), c.getName());
            }
        }
    }

    /** Le seul nombre d'une ligne de pièce est une quantité : ça se compte, ça ne s'additionne pas. */
    @Test
    void uneQuantiteDePieceResteUneQuantite() {
        dossierQuiRepond(claim());
        when(interventions.findBySavCaseIdOrderByReportedAtAsc(DOSSIER_ID)).thenReturn(List.of());
        when(piecesMontees.findBySavCaseId(DOSSIER_ID))
                .thenReturn(List.of(pieceSurPapier("REF-1", "Courroie", true)));

        AgPieceJointe p = service().relire(DOSSIER_ID, auth("CHEF_ATELIER")).pieces().get(0);

        assertEquals(1.0, p.quantite());
        assertEquals("montee", p.etat());
        assertEquals(LocalDate.of(2026, 10, 12), p.monteeLe());
        assertEquals(LocalDate.of(2026, 10, 20), p.retourLe());
        assertTrue(p.retourEnvoye());
    }
}
