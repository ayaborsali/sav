package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Mission;
import tn.higer.sav_backend.model.MissionIssue;
import tn.higer.sav_backend.model.MissionState;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.MissionRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La chaîne des papiers de l'atelier, vérifiée papier en main : devis accepté →
 * ordre de travail → bon de livraison.
 *
 * Ce qui est figé ici est la décision du 02/11 — <b>un ordre ne s'ouvre qu'après
 * l'accord du client</b> — et les trois conséquences qui la rendent applicable :
 *  - l'accord se lit sur l'état du devis, pas sur une case de plus ;
 *  - un devis ne fait qu'un ordre, un ordre ne fait qu'un bon de livraison ;
 *  - le lien d'origine ({@code source_item_id}) et la demande ({@code mission_id},
 *    V35) s'écrivent au clic, et le papier d'après reprend les lignes telles quelles.
 *
 * Ce que le bon de livraison NE reprend pas est une règle, pas un oubli : le palier
 * du forfait reste à l'ordre. Un palier qui se décompterait deux fois facture deux
 * visites chez le client.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceChainServiceTest {

    @Mock private MaintenanceRepository documents;
    @Mock private MissionRepository missions;
    @Mock private SoldPlanService forfaits;
    @Mock private AuditService audit;

    private static final int ANNEE = LocalDate.now().getYear();
    private static final String MSS = "mss-1";
    private static final String DEVIS = "maint-devis";
    private static final String ORDRE = "maint-ordre";
    private static final double DELTA = 0.0001;

    private MaintenanceChainService service() {
        return new MaintenanceChainService(documents, missions, forfaits, audit, 19);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static MaintenanceDetail ligne(String reference, String label, double quantite, double prix) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference(reference);
        d.setLabel(label);
        d.setQuantity(quantite);
        d.setUnitPrice(prix);
        return d;
    }

    /** Le devis de la demande MT-…-001, chiffré hors forfait : 2 × 11,50 + 1 × 60,00 = 83,00 HT. */
    private static MaintenanceItem devis(MaintenanceStatus statut) {
        MaintenanceItem d = new MaintenanceItem();
        d.setId(DEVIS);
        d.setNumber("DV-" + ANNEE + "-003");
        d.setType(MaintenanceType.DEVIS);
        d.setStatus(statut);
        d.setVehicleId("veh-1");
        d.setClientId("cli-1");
        d.setTechnician("Ayed");
        d.setMissionId(MSS);
        d.setDescription("Mission MT-" + ANNEE + "-001 — dossier REC-" + ANNEE + "-004 : panne climatisation");
        d.getDetails().add(ligne("F-10", "Filtre à huile", 2.0, 11.5));
        d.getDetails().get(0).setPieceId("pc-F-10");
        d.getDetails().add(ligne("MO-1", "Main-d'œuvre", 1.0, 60.0));
        return d;
    }

    /** L'ordre né d'un devis : le palier du forfait est le sien, pas celui du papier d'après. */
    private static MaintenanceItem ordre(MaintenanceStatus statut) {
        MaintenanceItem o = new MaintenanceItem();
        o.setId(ORDRE);
        o.setNumber("OR-" + ANNEE + "-008");
        o.setType(MaintenanceType.OPERATION);
        o.setStatus(statut);
        o.setVehicleId("veh-1");
        o.setClientId("cli-1");
        o.setMissionId(MSS);
        o.setSourceItemId(DEVIS);
        o.setSoldPlanId("sp-1");
        o.setPlanStepId("mps-40");
        o.getDetails().add(ligne("F-10", "Filtre à huile", 2.0, 11.5));
        o.getDetails().get(0).setPieceId("pc-F-10");
        return o;
    }

    /** Le papier déjà sorti d'un autre, pour vérifier qu'un second ne passe pas. */
    private static MaintenanceItem enfant(MaintenanceType type, String numero) {
        MaintenanceItem e = new MaintenanceItem();
        e.setId("maint-enfant");
        e.setNumber(numero);
        e.setType(type);
        e.setStatus(MaintenanceStatus.DRAFT);
        e.setSourceItemId(DEVIS);
        return e;
    }

    /** La demande dont le devis est le papier qui court. */
    private static Mission demandeQuiCourtSur(String papierId) {
        Mission m = new Mission();
        m.setId(MSS);
        m.setNumber("MT-" + ANNEE + "-001");
        m.setState(MissionState.EN_COURS);
        m.setIssue(MissionIssue.DEVIS);
        m.setMaintenanceItemId(papierId);
        return m;
    }

    // ---------------------------------------------------- l'accord du client avant tout

    /**
     * La règle, dans le texte du refus : tant que le devis est brouillon, le client
     * n'a pas dit oui, et l'atelier n'a rien à exécuter. Le refus dit quel état
     * atteindre — l'envoi puis l'accord — sinon l'atelier tourne au même bouton.
     */
    @Test
    void un_devis_brouillon_ne_donne_pas_un_ordre() {
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devis(MaintenanceStatus.DRAFT)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().ordreDepuisDevis(DEVIS, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("encore brouillon"), refuse.getReason());
        assertTrue(refuse.getReason().contains("accord du client"), refuse.getReason());
        assertTrue(refuse.getReason().contains("enregistrez son accord"), refuse.getReason());
        verify(documents, never()).save(any());
        verify(missions, never()).save(any());
    }

    /** Un devis annulé n'a pas été accepté non plus — et un nouveau papier se refuse de la même plume. */
    @Test
    void un_devis_annule_ne_donne_pas_un_ordre() {
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devis(MaintenanceStatus.CANCELLED)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().ordreDepuisDevis(DEVIS, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("est annulé"), refuse.getReason());
        verify(documents, never()).save(any());
    }

    /** L'ordre ne se transforme pas depuis un ordre : le bon de livraison est la suite de l'ordre. */
    @Test
    void un_ordre_ne_se_refait_pas_depuis_lui_meme() {
        when(documents.findById(ORDRE)).thenReturn(Optional.of(ordre(MaintenanceStatus.IN_PROGRESS)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().ordreDepuisDevis(ORDRE, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("n'est pas un devis"), refuse.getReason());
    }

    // ------------------------------------------------------------- le devis → l'ordre

    /**
     * Le clic qui fait l'ordre : les lignes du devis sont reprises sans ressaisie,
     * le lien d'origine et la demande s'écrivent en base, le rattachement est vérifié
     * AVANT l'enregistrement, et la case de la demande passe au travail qui court.
     */
    @Test
    void un_devis_accepte_donne_un_ordre_qui_porte_les_lignes_et_le_lien() {
        MaintenanceItem papier = devis(MaintenanceStatus.VALIDATED);
        Mission demande = demandeQuiCourtSur(DEVIS);
        when(documents.findById(DEVIS)).thenReturn(Optional.of(papier));
        when(documents.findBySourceItemId(DEVIS)).thenReturn(List.of());
        when(documents.findNumbersByPrefix("OR-" + ANNEE + "-")).thenReturn(List.of("OR-" + ANNEE + "-007"));
        when(missions.findById(MSS)).thenReturn(Optional.of(demande));

        MaintenanceItem ordre = service().ordreDepuisDevis(DEVIS, jwt());

        assertEquals(MaintenanceType.OPERATION, ordre.getType());
        assertEquals("OR-" + ANNEE + "-008", ordre.getNumber());
        assertEquals(MaintenanceStatus.DRAFT, ordre.getStatus());
        assertEquals(DEVIS, ordre.getSourceItemId(), "l'ordre sait de quel devis il sort");
        assertEquals(MSS, ordre.getMissionId(), "et de quelle demande");
        assertEquals("veh-1", ordre.getVehicleId());
        assertEquals("cli-1", ordre.getClientId());
        assertEquals("Ayed", ordre.getTechnician());
        assertEquals(2, ordre.getDetails().size(), "les lignes du devis se repostent telles quelles");
        assertEquals("F-10", ordre.getDetails().get(0).getReference());
        assertEquals(11.5, ordre.getDetails().get(0).getUnitPrice(), DELTA);
        assertEquals("pc-F-10", ordre.getDetails().get(0).getPieceId(),
                "la ligne d'ordre garde la pièce du catalogue dont elle est la copie (V46)");
        assertNull(ordre.getDetails().get(1).getPieceId(), "la main-d'œuvre ne cite aucune pièce");
        assertNull(ordre.getTotalHT(), "l'ordre est un papier de travail : il ne chiffre pas");
        assertNull(ordre.getTotalTTC(), "le prix se relit sur le devis, la facture sur le bon de livraison");
        assertTrue(ordre.getDescription().contains("DV-" + ANNEE + "-003"), ordre.getDescription());
        assertTrue(ordre.getDescription().contains("panne climatisation"),
                "l'origine du devis se relit sur l'ordre");

        InOrder les_gestes = inOrder(forfaits, documents);
        les_gestes.verify(forfaits).verifierRattachement(ordre);
        les_gestes.verify(documents).save(ordre);
        assertEquals(ordre.getId(), demande.getMaintenanceItemId(),
                "la demande suit le travail en cours, sans que le devis disparaisse pour autant");
        ArgumentCaptor<String> journal = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("CREATE"), eq("MAINTENANCE"), eq(ordre.getId()), journal.capture(), any());
        assertTrue(journal.getValue().contains("depuis le devis « DV-" + ANNEE + "-003 »"), journal.getValue());
    }

    /**
     * Un même travail ne se pose pas deux fois : le second ordre est refusé et nomme
     * celui qui tient la place. Derrière le refus, rien n'est écrit — ni papier, ni
     * case de la demande.
     */
    @Test
    void un_devis_ne_donne_qu_un_seul_ordre() {
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devis(MaintenanceStatus.VALIDATED)));
        when(documents.findBySourceItemId(DEVIS)).thenReturn(List.of(
                enfant(MaintenanceType.OPERATION, "OR-" + ANNEE + "-004")));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().ordreDepuisDevis(DEVIS, jwt()));

        assertEquals(HttpStatus.CONFLICT, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("OR-" + ANNEE + "-004"), refuse.getReason());
        assertTrue(refuse.getReason().contains("même travail ne se pose pas deux fois"), refuse.getReason());
        verify(documents, never()).save(any());
        verify(missions, never()).save(any());
    }

    /**
     * La demande peut tenir son papier d'atelier ailleurs (un ordre ouvert à la main,
     * par exemple) : le devis qui donne un ordre ne déplace pas une case qui ne lui
     * appartient pas — ce serait effacer le travail d'un autre papier.
     */
    @Test
    void la_case_de_la_demande_ne_bouge_pas_si_un_autre_papier_la_tient() {
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devis(MaintenanceStatus.VALIDATED)));
        when(documents.findBySourceItemId(DEVIS)).thenReturn(List.of());
        when(documents.findNumbersByPrefix("OR-" + ANNEE + "-")).thenReturn(List.of());
        when(missions.findById(MSS)).thenReturn(Optional.of(demandeQuiCourtSur("maint-autre")));

        service().ordreDepuisDevis(DEVIS, jwt());

        verify(missions, never()).save(any());
    }

    // ------------------------------------------------------------ l'ordre → le livraison

    /**
     * Le bon de livraison rend le bus et les pièces : mêmes lignes, même bus, et le
     * palier du forfait <b>reste à l'ordre</b>. Un palier se décompte une fois ; le
     * reprendre ici cocherait une seconde visite chez le client.
     */
    @Test
    void le_bon_de_livraison_rend_le_bus_sans_reprendre_le_palier() {
        when(documents.findById(ORDRE)).thenReturn(Optional.of(ordre(MaintenanceStatus.DELIVERED)));
        when(documents.findBySourceItemId(ORDRE)).thenReturn(List.of());
        when(documents.findNumbersByPrefix("BL-" + ANNEE + "-")).thenReturn(List.of("BL-" + ANNEE + "-002"));

        MaintenanceItem bl = service().bonLivraisonDepuisOrdre(ORDRE, jwt());

        assertEquals(MaintenanceType.BL, bl.getType());
        assertEquals("BL-" + ANNEE + "-003", bl.getNumber());
        assertEquals(ORDRE, bl.getSourceItemId());
        assertEquals("veh-1", bl.getVehicleId());
        assertEquals(MSS, bl.getMissionId(), "le bon de livraison reste sur la même demande");
        assertNull(bl.getSoldPlanId(), "le palier est déjà compté par l'ordre");
        assertNull(bl.getPlanStepId());
        assertEquals(1, bl.getDetails().size());
        assertEquals("pc-F-10", bl.getDetails().get(0).getPieceId(),
                "la pièce citée descend jusqu'au bon de livraison, qui la remet au client");
        assertNull(bl.getTotalHT(), "le bon de livraison ne chiffre pas : le prix est sur le devis "
                + "que le client a signé, et trois montants pour un même travail ne se défendent pas");
        assertNull(bl.getTotalTTC());
        assertTrue(bl.getDescription().contains("OR-" + ANNEE + "-008"), bl.getDescription());
        verify(documents).save(bl);
        verify(missions, never()).save(any());
    }

    /** Un bus ne se restitue pas deux fois : le second bon de livraison est refusé, et il nomme le premier. */
    @Test
    void un_bus_ne_se_restitue_pas_deux_fois() {
        MaintenanceItem dejaRendu = enfant(MaintenanceType.BL, "BL-" + ANNEE + "-001");
        dejaRendu.setSourceItemId(ORDRE);
        when(documents.findById(ORDRE)).thenReturn(Optional.of(ordre(MaintenanceStatus.COMPLETED)));
        when(documents.findBySourceItemId(ORDRE)).thenReturn(List.of(dejaRendu));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().bonLivraisonDepuisOrdre(ORDRE, jwt()));

        assertEquals(HttpStatus.CONFLICT, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("BL-" + ANNEE + "-001"), refuse.getReason());
        assertTrue(refuse.getReason().contains("Un bus ne se restitue pas deux fois"), refuse.getReason());
        verify(documents, never()).save(any());
    }

    /** Un ordre annulé n'a rien travaillé : il n'y a rien à restituer derrière lui. */
    @Test
    void un_ordre_annule_ne_rend_rien() {
        when(documents.findById(ORDRE)).thenReturn(Optional.of(ordre(MaintenanceStatus.CANCELLED)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().bonLivraisonDepuisOrdre(ORDRE, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("rien à restituer"), refuse.getReason());
    }

    /** Le bon de livraison se pose sur un ordre, pas sur un devis : le devis ne livre rien. */
    @Test
    void un_devis_ne_se_livre_pas() {
        when(documents.findById(DEVIS)).thenReturn(Optional.of(devis(MaintenanceStatus.VALIDATED)));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().bonLivraisonDepuisOrdre(DEVIS, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("n'est pas un ordre de travail"), refuse.getReason());
    }

    // --------------------------------------------------------- l'origine ne se dérobe pas

    /**
     * Effacer un papier qui a déjà fait naître le suivant couperait la chaîne en son
     * milieu : l'ordre resterait sans le devis qu'il a suivi. Le refus nomme l'enfant,
     * c'est lui que l'atelier annule ou supprime d'abord.
     */
    @Test
    void le_papier_d_origine_ne_s_efface_pas_derriere_ce_qu_il_a_produit() {
        MaintenanceItem papier = devis(MaintenanceStatus.VALIDATED);
        when(documents.findBySourceItemId(DEVIS)).thenReturn(List.of(
                enfant(MaintenanceType.OPERATION, "OR-" + ANNEE + "-004")));

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().refuserSiEnfantDejaNe(papier));

        assertEquals(HttpStatus.BAD_REQUEST, refuse.getStatusCode());
        assertTrue(refuse.getReason().contains("OR-" + ANNEE + "-004"), refuse.getReason());
        assertTrue(refuse.getReason().contains("ne s'efface pas"), refuse.getReason());
    }

    /** Le papier qui n'a rien fait naître se supprime comme avant : la garde ne le retient pas. */
    @Test
    void un_papier_sans_enfant_se_supprime() {
        when(documents.findBySourceItemId(ORDRE)).thenReturn(List.of());

        service().refuserSiEnfantDejaNe(ordre(MaintenanceStatus.DRAFT));
    }

    /** Un identifiant qui ne désigne rien est un 404 écrit, pas un papier inventé. */
    @Test
    void un_papier_introuvable_est_refuse_en_404() {
        when(documents.findById("maint-inexistant")).thenReturn(Optional.empty());

        ResponseStatusException refuse = assertThrows(ResponseStatusException.class,
                () -> service().ordreDepuisDevis("maint-inexistant", jwt()));

        assertEquals(HttpStatus.NOT_FOUND, refuse.getStatusCode());
    }
}
