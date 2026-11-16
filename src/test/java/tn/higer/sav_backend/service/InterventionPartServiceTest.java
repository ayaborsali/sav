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
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandRequest;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartDemandResponse;
import tn.higer.sav_backend.dto.InterventionPartDtos.PartReceptionRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.InterventionEventSource;
import tn.higer.sav_backend.model.InterventionEventType;
import tn.higer.sav_backend.model.InterventionPartRequest;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.model.PartRequestState;
import tn.higer.sav_backend.repository.InterventionPartRequestRepository;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MissionRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La pièce demandée sur la sortie — la marche qui manquait entre le diagnostic de
 * l'homme dehors et la commande du guichet.
 *
 * Ce qui est verrouillé ici, parce que c'est là que le scénario s'est cassé en réel :
 *  - la demande <b>garde ses trois liens</b> : la sortie qui l'a vue, la mission qui a
 *    fait partir la voiture, le dossier et le bus repris de la sortie. Sans eux, la
 *    commande retombait dans un commentaire libre ;
 *  - le serveur les écrit, personne ne les saisit : un écran qui redemande le bus peut
 *    en dire un autre ;
 *  - l'état n'est pas une case : la demande seule ouvre la ligne en « À commander » ;
 *  - rien d'inventé : une référence que l'homme n'a pas lue reste vide ;
 *  - une pièce ne se demande pas depuis l'atelier, et ne se rectifie plus dès que le
 *    guichet a commandé.
 *
 * {@link InterventionService}, {@link AuditService} et les dépôts sont mockés ; le
 * service est instancié directement, comme sur les autres fiches du projet.
 */
@ExtendWith(MockitoExtension.class)
class InterventionPartServiceTest {

    @Mock private InterventionPartRequestRepository pieces;
    @Mock private InterventionRepository interventions;
    @Mock private MissionRepository missions;
    @Mock private InterventionService sortie;
    @Mock private MissionService demandes;
    @Mock private AuditService audit;

    private static final String ITV = "itv-9";
    private static final String MSS = "mss-1";
    private static final String MSS_POSE = "mss-2";
    private static final String SAV_ID = "sav-1";
    private static final String BUS_ID = "veh-1";
    private static final String PIECE = "ipr-1";
    private static final int ANNEE = LocalDateTime.now().getYear();

    private InterventionPartService service() {
        return new InterventionPartService(pieces, interventions, missions, sortie, demandes, audit);
    }

    // ------------------------------------------------------------------ fixtures

    private static Authentication homme() {
        return auth("TECHNICIEN");
    }

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "technicien@higerbus.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Une sortie dehors, au statut où la pièce se voit. */
    private static Intervention sortieAuStatut(InterventionStatus status) {
        Intervention i = new Intervention();
        i.setId(ITV);
        i.setNumber("DP-" + ANNEE + "-007");
        i.setStatus(status);
        i.setVehicleId(BUS_ID);
        i.setSavCaseId(SAV_ID);
        i.setTechnicianId("tch-1");
        i.setReportedAt(LocalDateTime.now().minusHours(2));
        i.setCreatedAt(LocalDateTime.now().minusHours(2));
        return i;
    }

    /** La vue que le module Dépannage rend de sa propre sortie : c'est elle qui porte les numéros. */
    private static InterventionResponse vue() {
        return new InterventionResponse(ITV, "DP-" + ANNEE + "-007", "in_progress", "high",
                BUS_ID, "VF631E132LD002323", "cli-1", "SNTRI",
                "tch-1", "Ali Trabelsi", null, null,
                null, "M. Ben Salah", "Panne climatisation",
                null, null, null,
                null, null, null, null, null, null,
                SAV_ID, "REC-" + ANNEE + "-004",
                "panne", null, null, null,
                "usr-1", false, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                "MT-" + ANNEE + "-014", LocalDateTime.now(), List.of(), false);
    }

    private static Mission missionLie() {
        Mission m = new Mission();
        m.setId(MSS);
        m.setNumber("MT-" + ANNEE + "-014");
        m.setInterventionId(ITV);
        return m;
    }

    private static InterventionPartRequest pieceAvecEtat(PartRequestState etat) {
        InterventionPartRequest p = new InterventionPartRequest();
        p.setId(PIECE);
        p.setInterventionId(ITV);
        p.setMissionId(MSS);
        p.setSavCaseId(SAV_ID);
        p.setVehicleId(BUS_ID);
        p.setReference("HIG-CLIM-0042");
        p.setDesignation("Compresseur de climatisation");
        p.setReason("Compresseur mort, plus de froid dans le bus");
        p.setQuantityOrdered(1);
        p.setState(etat);
        p.setCreatedAt(LocalDateTime.now().minusHours(1));
        return p;
    }

    private static PartDemandRequest demande(String reference, String designation,
                                             Integer quantite, String raison) {
        return new PartDemandRequest(reference, designation, quantite, raison);
    }

    /** La demande licite, telle que l'écran de l'homme dehors l'envoie. */
    private static PartDemandRequest bonneDemande() {
        return demande("HIG-CLIM-0042", "Compresseur de climatisation", 2,
                "Compresseur mort, plus de froid dans le bus");
    }

    // ------------------------------------------------------ la demande et ses liens

    @Test
    void leServeurEcritLesQuatreCasesEtLesTroisLiensDeLaSortie() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);
        when(missions.findByInterventionId(ITV)).thenReturn(Optional.of(missionLie()));
        when(sortie.full(i)).thenReturn(vue());

        service().demander(ITV, bonneDemande(), jwt(), homme());

        ArgumentCaptor<InterventionPartRequest> pose =
                ArgumentCaptor.forClass(InterventionPartRequest.class);
        verify(pieces).save(pose.capture());
        InterventionPartRequest p = pose.getValue();
        assertTrue(p.getId().startsWith("ipr-"), "l'identifiant d'une pièce demandée se nomme : " + p.getId());
        // Les quatre cases de l'homme.
        assertEquals("HIG-CLIM-0042", p.getReference());
        assertEquals("Compresseur de climatisation", p.getDesignation());
        assertEquals(2, p.getQuantityOrdered());
        assertEquals("Compresseur mort, plus de froid dans le bus", p.getReason());
        // Les liens : sortie, mission, dossier, bus — tous repris de la sortie.
        assertEquals(ITV, p.getInterventionId());
        assertEquals(MSS, p.getMissionId());
        assertEquals(SAV_ID, p.getSavCaseId());
        assertEquals(BUS_ID, p.getVehicleId());
        // L'état n'est pas saisi : la demande, seule, l'ouvre.
        assertEquals(PartRequestState.A_COMMANDER, p.getState());
        assertEquals("technicien@higerbus.tn", p.getDemandedBy());
        assertNull(p.getOrderedAt(), "rien n'est commandé à la demande");
        assertNull(p.getQuantityReceived(), "rien n'est reçu à la demande");
        assertNotNull(p.getCreatedAt());
    }

    @Test
    void laDemandeSeGraVeAuJournalDeLaSortie() {
        Intervention i = sortieAuStatut(InterventionStatus.ON_SITE);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);
        when(missions.findByInterventionId(ITV)).thenReturn(Optional.empty());
        when(sortie.full(i)).thenReturn(vue());

        service().demander(ITV, bonneDemande(), jwt(), homme());

        verify(sortie).event(eq(ITV), eq(InterventionEventType.PART),
                eq(InterventionEventSource.SERVER), any(Jwt.class),
                contains("Compresseur de climatisation"));
    }

    /** Une sortie sans mission (les sorties d'avant V28) reste rattachable à son bus. */
    @Test
    void uneSortieSansMissionGardeSaPieceSansInventerUneDemande() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);
        when(missions.findByInterventionId(ITV)).thenReturn(Optional.empty());
        when(sortie.full(i)).thenReturn(vue());

        service().demander(ITV, bonneDemande(), jwt(), homme());

        ArgumentCaptor<InterventionPartRequest> pose =
                ArgumentCaptor.forClass(InterventionPartRequest.class);
        verify(pieces).save(pose.capture());
        assertNull(pose.getValue().getMissionId());
    }

    /**
     * « Rien d'inventé » : un code que l'homme n'a pas pu lire sur la pièce reste
     * vide. Une référence inventée part en commande, et le bus attend une pièce qui
     * n'existe pas.
     */
    @Test
    void uneReferenceNonLueResteVide() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);
        when(missions.findByInterventionId(ITV)).thenReturn(Optional.of(missionLie()));
        when(sortie.full(i)).thenReturn(vue());

        service().demander(ITV, demande("   ", "Compresseur de climatisation", 1,
                "Compresseur mort, plus de froid"), jwt(), homme());

        ArgumentCaptor<InterventionPartRequest> pose =
                ArgumentCaptor.forClass(InterventionPartRequest.class);
        verify(pieces).save(pose.capture());
        assertNull(pose.getValue().getReference());
        assertEquals(PartRequestState.A_COMMANDER, pose.getValue().getState());
    }

    // ------------------------------------------------------ les refus qui s'écrivent

    /** Le but du module : la pièce se voit devant le bus, pas au bureau. */
    @Test
    void rienNeSeDemandeAvantDArriveSurPlace() {
        Intervention i = sortieAuStatut(InterventionStatus.DISPATCHED);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().demander(ITV, bonneDemande(), jwt(), homme()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refus.getReason().contains("devant le bus"), refus.getReason());
        verify(pieces, never()).save(any());
    }

    /** Une pièce oubliée pendant la sortie ne se rattrape pas ici : elle repasse par le dossier. */
    @Test
    void uneSortieRentreeNeSeRattrapePasParUneDemandeTardive() {
        Intervention i = sortieAuStatut(InterventionStatus.RETURNED);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().demander(ITV, bonneDemande(), jwt(), homme()));

        assertTrue(refus.getReason().contains("déjà rentrée"), refus.getReason());
        verify(pieces, never()).save(any());
    }

    @Test
    void uneQuantiteZeroNeCommandeRien() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);

        assertThrows(ResponseStatusException.class, () -> service().demander(ITV,
                demande("HIG-CLIM-0042", "Compresseur", 0, "Compresseur mort"), jwt(), homme()));
        assertThrows(ResponseStatusException.class, () -> service().demander(ITV,
                demande("HIG-CLIM-0042", "Compresseur", null, "Compresseur mort"), jwt(), homme()));
        verify(pieces, never()).save(any());
    }

    @Test
    void laDesignationEtLaRaisonSecrivent() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);

        ResponseStatusException sansNom = assertThrows(ResponseStatusException.class,
                () -> service().demander(ITV, demande("HIG-CLIM-0042", "  ", 1, "raison"), jwt(), homme()));
        assertTrue(sansNom.getReason().contains("désignation"), sansNom.getReason());

        ResponseStatusException sansRaison = assertThrows(ResponseStatusException.class,
                () -> service().demander(ITV, demande("HIG-CLIM-0042", "Compresseur", 1, "  "), jwt(), homme()));
        assertTrue(sansRaison.getReason().contains("raison"), sansRaison.getReason());
        verify(pieces, never()).save(any());
    }

    // ------------------------------------------------------ la rectification

    @Test
    void laDemandeSeRectifieTantQueRienNestCommande() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        InterventionPartRequest p = pieceAvecEtat(PartRequestState.A_COMMANDER);
        when(pieces.findById(PIECE)).thenReturn(Optional.of(p));
        when(interventions.findById(ITV)).thenReturn(Optional.of(i));
        when(sortie.full(i)).thenReturn(vue());

        service().corriger(PIECE, demande("HIG-CLIM-0042", "Compresseur de climatisation", 3,
                "Deux compresseurs sur ce bus"), jwt(), homme());

        assertEquals(3, p.getQuantityOrdered());
        assertNotNull(p.getUpdatedAt());
        assertEquals(PartRequestState.A_COMMANDER, p.getState(),
                "rectifier une demande ne fait pas avancer son état");
        verify(pieces).save(p);
    }

    /** Le guichet a engagé une commande sur ces chiffres : les changer ici casserait la réception. */
    @Test
    void uneDemandeCommandeeNestPlusLibre() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        InterventionPartRequest p = pieceAvecEtat(PartRequestState.COMMANDEE);
        p.setOrderedAt(LocalDateTime.now());
        when(pieces.findById(PIECE)).thenReturn(Optional.of(p));
        when(interventions.findById(ITV)).thenReturn(Optional.of(i));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().corriger(PIECE, bonneDemande(), jwt(), homme()));

        assertTrue(refus.getReason().contains("Commandée"), refus.getReason());
        verify(pieces, never()).save(any());
    }

    // ------------------------------------------------------ la lecture des écrans

    @Test
    void laLigneRendLesNumerosQueLhommePeutVerifier() {
        Intervention i = sortieAuStatut(InterventionStatus.IN_PROGRESS);
        when(sortie.requireVisible(eq(ITV), any(Authentication.class), any(Jwt.class))).thenReturn(i);
        when(pieces.findByInterventionIdOrderByCreatedAtAsc(ITV)).thenReturn(List.of(pieceAvecEtat(PartRequestState.A_COMMANDER)));
        when(sortie.full(i)).thenReturn(vue());

        List<PartDemandResponse> lignes = service().pourSortie(ITV, homme(), jwt());

        assertEquals(1, lignes.size());
        PartDemandResponse ligne = lignes.get(0);
        // Un identifiant seul ne se vérifie pas à l'œil : les trois liens sortent avec leur numéro.
        assertEquals("DP-" + ANNEE + "-007", ligne.interventionNumber());
        assertEquals(MSS, ligne.missionId());
        assertEquals("MT-" + ANNEE + "-014", ligne.missionNumber());
        assertEquals("REC-" + ANNEE + "-004", ligne.savCaseNumber());
        assertEquals("VF631E132LD002323", ligne.vehicleLabel());
        assertEquals("a_commander", ligne.state());
        assertEquals("À commander", ligne.stateLabel());
        assertEquals(1, ligne.quantityOrdered());
    }

    /** L'écran du dossier relit ses pièces sans retomber sortie par sortie. */
    @Test
    void leDossierRelitSesPiecesEnUneSeuleLectureDeSorties() {
        List<InterventionPartRequest> rows = List.of(
                pieceAvecEtat(PartRequestState.A_COMMANDER),
                pieceAvecEtat(PartRequestState.COMMANDEE));
        when(pieces.findBySavCaseIdOrderByCreatedAtAsc(SAV_ID)).thenReturn(rows);
        when(interventions.findAllById(anyList())).thenReturn(List.of(
                sortieAuStatut(InterventionStatus.IN_PROGRESS)));
        when(sortie.full(any(Intervention.class))).thenReturn(vue());

        List<PartDemandResponse> lignes = service().pourDossier(SAV_ID, auth("ADMIN"), jwt());

        assertEquals(2, lignes.size());
        assertEquals("À commander", lignes.get(0).stateLabel());
        assertEquals("Commandée", lignes.get(1).stateLabel());
        verify(interventions, times(1)).findAllById(anyList());
    }

    // ------------------------------------------------- les deux gestes d'après la demande

    private static Authentication guichet() {
        return auth("GESTIONNAIRE_SAV");
    }

    /** Une ligne commandée pour deux pièces, rien reçu. */
    private static InterventionPartRequest commandeDe(int quantiteCommandee) {
        InterventionPartRequest p = pieceAvecEtat(PartRequestState.COMMANDEE);
        p.setQuantityOrdered(quantiteCommandee);
        p.setOrderedAt(LocalDateTime.now().minusDays(2));
        p.setOrderedBy("guichet@higerbus.tn");
        return p;
    }

    /** Les deux lectures que tout geste fait : sa ligne, et la sortie qui la porte. */
    private InterventionPartRequest sortiePourGeste(InterventionPartRequest p) {
        when(pieces.findById(PIECE)).thenReturn(Optional.of(p));
        when(interventions.findById(ITV)).thenReturn(
                Optional.of(sortieAuStatut(InterventionStatus.RETURNED)));
        return p;
    }

    /** La mise en page de la ligne relue, que les refus n'atteignent jamais. */
    private void laLigneSeRend() {
        when(sortie.full(any(Intervention.class))).thenReturn(vue());
    }

    /**
     * La mission « changer la pièce » que le serveur ouvre dès que la ligne passe
     * « Livrée ». Sa logique a sa fiche ({@link MissionServiceTest}) : ce fichier
     * vérifie que la réception l'appelle, avec quoi, et ce qu'elle en garde sur la
     * ligne.
     */
    private void laMissionDePoseSouvre() {
        when(demandes.dossierConnu(SAV_ID)).thenReturn(true);
        when(demandes.ouvrirPourLaPiece(any(MissionService.PoseDePiece.class), any(Jwt.class)))
                .thenReturn(missionDePose());
    }

    private static Mission missionDePose() {
        Mission m = new Mission();
        m.setId(MSS_POSE);
        m.setNumber("MT-" + ANNEE + "-021");
        m.setSavCaseId(SAV_ID);
        m.setState(MissionState.A_TRAITER);
        return m;
    }

    /**
     * Le guichet commande : une date prise à l'horloge du serveur, un nom pris au
     * jeton, aucun corps de requête. Le bus n'a plus besoin d'être dehors — la
     * commande se passe à l'atelier, souvent après le retour de l'homme.
     */
    @Test
    void laCommandeNAucuneCaseSaisieEtOuvreCommandee() {
        InterventionPartRequest p = sortiePourGeste(pieceAvecEtat(PartRequestState.A_COMMANDER));
        laLigneSeRend();

        service().commander(PIECE, jwt(), guichet());

        assertEquals(PartRequestState.COMMANDEE, p.getState());
        assertNotNull(p.getOrderedAt(), "la date de commande vient du serveur");
        assertEquals("technicien@higerbus.tn", p.getOrderedBy(), "le nom vient du jeton");
        verify(pieces).save(p);
        verify(sortie).event(eq(ITV), eq(InterventionEventType.PART),
                eq(InterventionEventSource.SERVER), any(Jwt.class), contains("Commande passée"));
    }

    /** Deux commandes sur la même ligne, c'est deux fois le même argent engagé. */
    @Test
    void onNeCommandePasDeuxFois() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().commander(PIECE, jwt(), guichet()));

        assertTrue(refus.getReason().contains("Commandée"), refus.getReason());
        verify(pieces, never()).save(any());
    }

    /**
     * Le cœur de la demande : « reçue < commandée = Partielle, reçue = commandée =
     * Livrée », calculé ici et nulle part ailleurs.
     */
    @Test
    void uneReceptionInferieureEstPartielle() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));
        laLigneSeRend();

        PartDemandResponse ligne = service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());

        assertEquals(1, p.getQuantityReceived());
        assertEquals(PartRequestState.PARTIELLE, p.getState());
        assertEquals("partielle", ligne.state());
        assertEquals("Partielle", ligne.stateLabel());
        assertNotNull(p.getReceivedAt());
        assertEquals("technicien@higerbus.tn", p.getReceivedBy());
    }

    @Test
    void uneReceptionCompleteEstLivree() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));
        laLigneSeRend();
        laMissionDePoseSouvre();

        PartDemandResponse ligne = service().receptionner(PIECE, new PartReceptionRequest(2), jwt(), guichet());

        assertEquals(2, p.getQuantityReceived());
        assertEquals(PartRequestState.LIVREE, p.getState());
        assertEquals("Livrée", ligne.stateLabel());
    }

    /**
     * Deux livraisons qui se cumulent : c'est le cumul, pas la dernière quantité, qui
     * décide de l'état. La première écriture laisse la commande ouverte.
     */
    @Test
    void lesReceptionsSeCumulentEtLeCumulDecideDeLEtat() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(3));
        laLigneSeRend();
        laMissionDePoseSouvre();

        service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());
        assertEquals(PartRequestState.PARTIELLE, p.getState());
        assertEquals(1, p.getQuantityReceived());
        verify(demandes, never()).ouvrirPourLaPiece(any(), any());

        service().receptionner(PIECE, new PartReceptionRequest(2), jwt(), guichet());
        assertEquals(3, p.getQuantityReceived());
        assertEquals(PartRequestState.LIVREE, p.getState());
    }

    /** Un de plus sur le bon de livraison, et le chiffre part dans un papier envoyé à Higer. */
    @Test
    void onNeRecoitPasPlusQueCeQuiEstCommande() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));
        p.setQuantityReceived(1);
        p.setState(PartRequestState.PARTIELLE);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().receptionner(PIECE, new PartReceptionRequest(2), jwt(), guichet()));

        // Le refus nomme les trois chiffres : celui qui l'a sous les yeux doit pouvoir relire.
        assertTrue(refus.getReason().contains("2 commandée"), refus.getReason());
        assertTrue(refus.getReason().contains("1 déjà reçue"), refus.getReason());
        assertEquals(1, p.getQuantityReceived(), "rien n'est écrit");
        verify(pieces, never()).save(any());
    }

    /** Réceptionner avant la commande, c'est faire croire à une livraison sans ordre. */
    @Test
    void rienNeSeRecoitAvantLaCommande() {
        sortiePourGeste(pieceAvecEtat(PartRequestState.A_COMMANDER));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet()));

        assertTrue(refus.getReason().contains("pas encore commandée"), refus.getReason());
        verify(pieces, never()).save(any());
    }

    @Test
    void unePieceLivreeNeSeReceptionnePlus() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(1));
        p.setState(PartRequestState.LIVREE);
        p.setQuantityReceived(1);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet()));

        assertTrue(refus.getReason().contains("Livrée"), refus.getReason());
        verify(pieces, never()).save(any());
    }

    @Test
    void uneQuantiteRecueZeroNeRienEcrit() {
        sortiePourGeste(commandeDe(2));

        assertThrows(ResponseStatusException.class,
                () -> service().receptionner(PIECE, new PartReceptionRequest(0), jwt(), guichet()));
        assertThrows(ResponseStatusException.class,
                () -> service().receptionner(PIECE, new PartReceptionRequest(null), jwt(), guichet()));
        verify(pieces, never()).save(any());
    }

    /**
     * L'homme dehors demande la pièce ; ce n'est pas lui qui écrit qu'elle est
     * commandée ou arrivée. Sinon la ligne avancerait sans que personne au guichet
     * n'ait rien engagé, et le contrôle de la commande n'existerait plus.
     */
    @Test
    void leCompteDeTechnicienNeCommandePasEtNeReceptionnePasLuiMeme() {
        sortiePourGeste(pieceAvecEtat(PartRequestState.A_COMMANDER));

        ResponseStatusException commande = assertThrows(ResponseStatusException.class,
                () -> service().commander(PIECE, jwt(), homme()));
        assertEquals(HttpStatus.FORBIDDEN, commande.getStatusCode());

        ResponseStatusException reception = assertThrows(ResponseStatusException.class,
                () -> service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), homme()));
        assertEquals(HttpStatus.FORBIDDEN, reception.getStatusCode());
        verify(pieces, never()).save(any());
    }

    /** L'écran ne peut calculer l'écart que si les deux chiffres et les deux noms sortent. */
    @Test
    void laLigneRendLesDeuxQuantitesEtCeuxQuiLesOntEcrites() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));
        laLigneSeRend();

        PartDemandResponse ligne = service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());

        assertEquals(2, ligne.quantityOrdered());
        assertEquals(1, ligne.quantityReceived());
        assertEquals("guichet@higerbus.tn", ligne.orderedBy());
        assertEquals("technicien@higerbus.tn", ligne.receivedBy());
        assertNotNull(ligne.orderedAt());
        assertNotNull(ligne.receivedAt());
    }

    // ------------------------------------------- la livraison qui ouvre la mission de pose

    /**
     * Le trou que P3 referme : la pièce arrive au magasin et personne n'envoie qui que
     * ce soit la monter. Ici la réception complète ouvre la demande elle-même, sur la
     * <b>même réclamation</b>, avec les mots de la pièce.
     */
    @Test
    void laPieceLivreeOuvreElleMemeSaMissionDePose() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));
        laLigneSeRend();
        laMissionDePoseSouvre();

        service().receptionner(PIECE, new PartReceptionRequest(2), jwt(), guichet());

        ArgumentCaptor<MissionService.PoseDePiece> demande =
                ArgumentCaptor.forClass(MissionService.PoseDePiece.class);
        verify(demandes).ouvrirPourLaPiece(demande.capture(), any(Jwt.class));
        assertEquals(SAV_ID, demande.getValue().savCaseId(), "la même réclamation que la pièce");
        assertEquals("DP-" + ANNEE + "-007", demande.getValue().sortieNumber());
        assertEquals("Compresseur de climatisation", demande.getValue().designation());
        assertEquals("HIG-CLIM-0042", demande.getValue().reference());
        assertEquals(2, demande.getValue().quantite().intValue());
        // Le lien reste sur la ligne : c'est lui qui dira, à la seconde sortie, laquelle est posée.
        assertEquals(MSS_POSE, p.getFittingMissionId());
        verify(sortie).event(eq(ITV), eq(InterventionEventType.PART),
                eq(InterventionEventSource.SERVER), any(Jwt.class), contains("MT-" + ANNEE + "-021"));
    }

    /** « Partielle » n'ouvre rien : la moitié d'une commande ne se monte pas. */
    @Test
    void unePiecePartielleNouvreAucuneMissionDePose() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(2));
        laLigneSeRend();

        service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());

        assertEquals(PartRequestState.PARTIELLE, p.getState());
        assertNull(p.getFittingMissionId());
        verify(demandes, never()).ouvrirPourLaPiece(any(), any());
    }

    /**
     * Une sortie née d'un rappel de forfait n'a pas de réclamation au dos : il n'y a
     * rien à quoi répondre, donc aucune mission ne s'ouvre — et c'est écrit au journal,
     * parce qu'une pièce qui attend sans que rien ne le dise ne viendra jamais à
     * l'écran.
     */
    @Test
    void sansReclamationLaMissionNestPasOuverteEtLeJournalLEcrit() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(1));
        p.setSavCaseId(null);
        laLigneSeRend();

        service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());

        assertEquals(PartRequestState.LIVREE, p.getState(), "la livraison est écrite, elle");
        assertNull(p.getFittingMissionId(), "et rien n'est inventé pour la remplir");
        verify(demandes, never()).ouvrirPourLaPiece(any(), any());
        verify(sortie).event(eq(ITV), eq(InterventionEventType.PART),
                eq(InterventionEventSource.SERVER), any(Jwt.class), contains("Ouvrez-la sur le dossier"));
    }

    /**
     * Douze sorties de la base de l'atelier portent l'identifiant d'un dossier supprimé
     * ({@code DELETE /api/sav-cases} ne nettoie aucun pointeur). La garde se fait avant
     * toute écriture, sinon le magasin recevrait un refus sur une boîte qu'il tient dans
     * ses bras : la livraison reste acquise, la mission ne s'ouvre pas, et le journal
     * dit lequel des deux cas il joue.
     */
    @Test
    void unDossierSupprimeLaisseLaReceptionPasserSansOuvrirDeMission() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(1));
        laLigneSeRend();
        when(demandes.dossierConnu(SAV_ID)).thenReturn(false);

        service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());

        assertEquals(PartRequestState.LIVREE, p.getState());
        assertNull(p.getFittingMissionId());
        verify(demandes, never()).ouvrirPourLaPiece(any(), any());
        verify(sortie).event(eq(ITV), eq(InterventionEventType.PART),
                eq(InterventionEventSource.SERVER), any(Jwt.class), contains("n'existe plus"));
    }

    /** Le numéro de la mission de pose sort avec la ligne : « MT- » seul ne se vérifie pas à l'œil. */
    @Test
    void laLigneRendLeNumeroDeLaMissionQueLaLivraisonAOuverte() {
        InterventionPartRequest p = sortiePourGeste(commandeDe(1));
        laLigneSeRend();
        laMissionDePoseSouvre();
        when(missions.findById(MSS_POSE)).thenReturn(Optional.of(missionDePose()));

        PartDemandResponse ligne = service().receptionner(PIECE, new PartReceptionRequest(1), jwt(), guichet());

        assertEquals(MSS_POSE, ligne.fittingMissionId());
        assertEquals("MT-" + ANNEE + "-021", ligne.fittingMissionNumber());
    }
}
