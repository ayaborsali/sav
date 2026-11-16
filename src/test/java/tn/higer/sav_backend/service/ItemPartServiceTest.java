package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartRequest;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartResponse;
import tn.higer.sav_backend.dto.ItemPartDtos.RetourRequest;
import tn.higer.sav_backend.model.ItemPart;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.repository.ItemPartRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La porte unique des pièces posées.
 *
 * Une pièce changée sur un bus est la seule ligne d'un claim que le constructeur
 * vérifie pièce par pièce : la référence, le bus, la date de pose, et ce qu'elle est
 * devenue (renvoyée chez Higer, ou gardée — et pourquoi). Ces tests verrouillent les
 * trois choses qui cassent un remboursement :
 *  - le bus n'est jamais saisi, il vient du papier (deux réponses possibles = une
 *    addition fausse) ;
 *  - le sort de la pièce est écrit avec sa date ou son motif, jamais à moitié ;
 *  - une pièce déjà réclamée ne s'efface pas, et un rattachement ne se joue pas deux fois.
 */
@ExtendWith(MockitoExtension.class)
class ItemPartServiceTest {

    @Mock private ItemPartRepository parts;
    @Mock private MaintenanceRepository items;
    @Mock private AuditService audit;

    private static final String PAPIER = "mi-3";
    private static final String PIECE = "part-1";

    private ItemPartService service() {
        return new ItemPartService(parts, items, audit);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject("chef@saphir.tn").claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Le papier de maintenance qui a monté la pièce — c'est lui qui dit dans quel bus. */
    private static MaintenanceItem papier() {
        MaintenanceItem item = new MaintenanceItem();
        item.setId(PAPIER);
        item.setNumber("MT-2026-018");
        item.setVehicleId("veh-9");
        item.setDate(LocalDate.of(2026, 10, 20));
        return item;
    }

    private static ItemPart piece(String id, String savCaseId) {
        ItemPart p = new ItemPart();
        p.setId(id);
        p.setMaintenanceItemId(PAPIER);
        p.setVehicleId("veh-9");
        p.setReference("HIG-CLIM-0042");
        p.setDesignation("Compresseur de climatisation");
        p.setQuantity(1.0);
        p.setFittedOn(LocalDate.of(2026, 10, 20));
        p.setSavCaseId(savCaseId);
        return p;
    }

    private MaintenanceItem papierDispo() {
        when(items.findById(PAPIER)).thenReturn(Optional.of(papier()));
        return papier();
    }

    // ------------------------------------------------------------------- la pose

    @Test
    void leBusDunePieceEstCeluiDuPapier() {
        papierDispo();

        ItemPartResponse posee = service().ajouter(PAPIER,
                new ItemPartRequest("HIG-CLIM-0042", "Compresseur de climatisation", 2.0, null), jwt());

        assertEquals("veh-9", posee.vehicleId(), "le bus n'est pas une saisie, c'est une lecture");
        assertEquals(PAPIER, posee.maintenanceItemId());
        assertEquals(LocalDate.of(2026, 10, 20), posee.fittedOn(),
                "sans date de pose écrite, c'est celle du papier qui tient");
        assertEquals(2.0, posee.quantity());
        assertFalse(posee.returned(), "rien n'est renvoyé tant que personne ne l'a déclaré");
        assertNull(posee.savCaseId(), "la pièce n'est réclamée à personne avant le dossier garantie");
        verify(audit).record(eq("CREATE"), eq("ITEM_PART"), any(),
                contains("MT-2026-018"), any());
    }

    @Test
    void laDateDePoseFournieEstGardee() {
        papierDispo();
        LocalDate pose = LocalDate.of(2026, 10, 12);

        ItemPartResponse posee = service().ajouter(PAPIER,
                new ItemPartRequest("HIG-CLIM-0042", null, 1.0, pose), jwt());

        assertEquals(pose, posee.fittedOn());
    }

    /**
     * Le devis chiffre, il ne répare pas. Tant que le client n'a pas signé, rien n'est
     * monté dans le bus : une pièce attachée au devis ferait croire à l'atelier qu'elle
     * est posée, et le claim partirait avec une pièce que personne n'a changée.
     */
    @Test
    void unePieceNeSePosePasSurUnDevis() {
        MaintenanceItem devis = papier();
        devis.setType(MaintenanceType.DEVIS);
        devis.setNumber("DV-2026-0007");
        when(items.findById(PAPIER)).thenReturn(Optional.of(devis));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> service().ajouter(PAPIER,
                        new ItemPartRequest("HIG-CLIM-0042", "Compresseur de climatisation", 1.0, null), jwt()));

        assertTrue(refus.getReason().contains("DV-2026-0007"), refus.getReason());
        assertTrue(refus.getReason().contains("est un devis"), refus.getReason());
        assertTrue(refus.getReason().contains("ordre de travail"), refus.getReason());
        verify(parts, never()).save(any());
    }

    @Test
    void uneQuantiteAbsenteOuNulleEstRefusee() {
        papierDispo();

        ResponseStatusException sansQuantite = assertThrows(ResponseStatusException.class,
                () -> service().ajouter(PAPIER, new ItemPartRequest("HIG-CLIM-0042", null, null, null), jwt()));
        ResponseStatusException zero = assertThrows(ResponseStatusException.class,
                () -> service().ajouter(PAPIER, new ItemPartRequest("HIG-CLIM-0042", null, 0.0, null), jwt()));

        assertTrue(sansQuantite.getReason().contains("quantité"), sansQuantite.getReason());
        assertTrue(zero.getReason().contains("quantité"), zero.getReason());
        verify(parts, never()).save(any());
    }

    @Test
    void uneLigneSansNomEstRefusee() {
        papierDispo();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().ajouter(PAPIER, new ItemPartRequest("  ", "   ", 1.0, null), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("Nommez la pièce"), e.getReason());
        verify(parts, never()).save(any());
    }

    @Test
    void laReferenceSeuleSuffitEtElleEstNettoyee() {
        papierDispo();

        ItemPartResponse posee = service().ajouter(PAPIER,
                new ItemPartRequest("  HIG-CLIM-0042  ", null, 1.0, null), jwt());

        assertEquals("HIG-CLIM-0042", posee.reference());
        assertNull(posee.designation());
    }

    @Test
    void sansPapierLaPieceNaPasOuEtrePosee() {
        when(items.findById(PAPIER)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().ajouter(PAPIER, new ItemPartRequest("HIG-CLIM-0042", null, 1.0, null), jwt()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        assertTrue(e.getReason().contains("introuvable"), e.getReason());
    }

    // ------------------------------------------------------------------- l'effacement

    @Test
    void unePieceReclameeParUnDossierNeSentacePas() {
        when(parts.findById(PIECE)).thenReturn(Optional.of(piece(PIECE, "sav-2")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().supprimer(PIECE, jwt()));

        assertTrue(e.getReason().contains("on ne l'efface pas"), e.getReason());
        verify(parts, never()).delete(any());
    }

    @Test
    void unePieceEncoreLibreSentaceEtSeJournalise() {
        when(parts.findById(PIECE)).thenReturn(Optional.of(piece(PIECE, null)));

        service().supprimer(PIECE, jwt());

        verify(parts).delete(any(ItemPart.class));
        verify(audit).record(eq("DELETE"), eq("ITEM_PART"), eq(PIECE), contains("HIG-CLIM-0042"), any());
    }

    // ------------------------------------------------------------------- le sort de la pièce

    @Test
    void unePieceEnvoyeeSansDateNeComptePas() {
        when(parts.findById(PIECE)).thenReturn(Optional.of(piece(PIECE, "sav-2")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().enregistrerRetour(PIECE,
                        new RetourRequest(true, null, "renvoyée au constructeur"), jwt()));

        assertTrue(e.getReason().contains("date d'envoi"), e.getReason());
        verify(parts, never()).save(any());
    }

    @Test
    void unePieceGardeeSansMotifEstRefusee() {
        when(parts.findById(PIECE)).thenReturn(Optional.of(piece(PIECE, "sav-2")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().enregistrerRetour(PIECE, new RetourRequest(false, null, "  "), jwt()));

        // C'est la case que le contrôleur constructeur lit avant d'annuler un remboursement.
        assertTrue(e.getReason().contains("écrivez pourquoi"), e.getReason());
        verify(parts, never()).save(any());
    }

    @Test
    void unePieceEnvoyeeEstDatee() {
        ItemPart existante = piece(PIECE, "sav-2");
        existante.setReturnReason("à renvoyer");
        when(parts.findById(PIECE)).thenReturn(Optional.of(existante));
        LocalDate envoi = LocalDate.of(2026, 10, 24);

        ItemPartResponse apres = service().enregistrerRetour(PIECE,
                new RetourRequest(true, envoi, "Renvoyée avec le bon de retour 4471"), jwt());

        assertTrue(apres.returned());
        assertEquals(envoi, apres.returnedAt(), "la date n'est jamais supposée à aujourd'hui");
        assertEquals("Renvoyée avec le bon de retour 4471", apres.returnReason());
        verify(audit).record(eq("UPDATE"), eq("ITEM_PART"), eq(PIECE), contains("envoyée le 2026-10-24"), any());
    }

    @Test
    void unePieceGardeePerdSaDateEtGardeSaRaison() {
        ItemPart dejaEnvoyee = piece(PIECE, "sav-2");
        dejaEnvoyee.setReturned(true);
        dejaEnvoyee.setReturnedAt(LocalDate.of(2026, 10, 24));
        when(parts.findById(PIECE)).thenReturn(Optional.of(dejaEnvoyee));

        ItemPartResponse apres = service().enregistrerRetour(PIECE,
                new RetourRequest(false, null, " Pièce conservée, Higer n'a pas donné de bon de retour. "), jwt());

        assertFalse(apres.returned());
        assertNull(apres.returnedAt(), "une pièce non partie ne porte plus de date de départ");
        assertEquals("Pièce conservée, Higer n'a pas donné de bon de retour.", apres.returnReason());
    }

    @Test
    void unePieceInconnueNeSeDatePas() {
        when(parts.findById(PIECE)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().enregistrerRetour(PIECE,
                        new RetourRequest(true, LocalDate.now(), null), jwt()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    // ------------------------------------------------------------------- le rattachement au claim

    @Test
    void lesPiecesDUnPapierPassentAuClaimUneSeuleFois() {
        ItemPart libre = piece("part-1", null);
        ItemPart dejaReclamee = piece("part-2", "sav-2");
        when(parts.findByMaintenanceItemIdOrderByFittedOnAsc(PAPIER))
                .thenReturn(List.of(libre, dejaReclamee));

        int rattachees = service().rattacherAuDossier(PAPIER, "sav-9");

        assertEquals(1, rattachees, "une pièce déjà réclamée ne change pas de maître deux fois");
        assertEquals("sav-9", libre.getSavCaseId());
        assertEquals("sav-2", dejaReclamee.getSavCaseId(), "le lien d'un claim déposé ne bouge pas");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ItemPart>> ecrites = ArgumentCaptor.forClass(List.class);
        verify(parts).saveAll(ecrites.capture());
        assertEquals(List.of(libre), ecrites.getValue());
    }

    @Test
    void unPapierSansPieceNeRattacheRienSansRienDire() {
        when(parts.findByMaintenanceItemIdOrderByFittedOnAsc(PAPIER)).thenReturn(List.of());

        assertEquals(0, service().rattacherAuDossier(PAPIER, "sav-9"));
        verify(parts, never()).saveAll(anyList());
    }

    @Test
    void unRattachementSansPapierOuSansDossierNEcritRien() {
        assertEquals(0, service().rattacherAuDossier(null, "sav-9"));
        assertEquals(0, service().rattacherAuDossier(PAPIER, null));
        verify(parts, never()).findByMaintenanceItemIdOrderByFittedOnAsc(any());
    }

    @Test
    void laListeDunBusSeLitDuPlusRecentAuPlusAncien() {
        when(parts.findByVehicleIdOrderByFittedOnDesc("veh-9"))
                .thenReturn(List.of(piece("part-1", null)));

        List<ItemPartResponse> lignes = service().pourBus("veh-9");

        assertEquals(1, lignes.size());
        assertEquals("HIG-CLIM-0042", lignes.get(0).reference());
    }
}
