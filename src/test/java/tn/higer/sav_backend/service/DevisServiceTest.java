package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.RefusRequest;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.PieceRechange;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.PieceRechangeRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La machine à états du devis : brouillon → envoyé → accepté ou refusé, et rien d'autre.
 *
 * Ce que ces tests surveillent, dans l'ordre du papier :
 *
 * — les trois dates (envoi, accord, refus) sont posées par le serveur au clic, jamais
 *   reçues du formulaire ;
 * — un accord ne s'invente pas : il faut le scan du devis signé au dossier, sinon le
 *   « oui » est une parole sans preuve ;
 * — un refus s'écrit avec sa raison, sinon la question reste posée chez le client ;
 * — le contenu se ferme à l'envoi (le client a lu ce papier-là), et l'état ne se tape
 *   plus au clavier ;
 * — chaque ligne d'un devis dit ce qu'elle chiffre : main-d'œuvre, pièce ou
 *   déplacement, et qui fournit la pièce. Le contrat de maintenance ne couvre que la
 *   main-d'œuvre : une ligne non naturée ne dit pas au client ce qu'il accepte de
 *   payer.
 *
 * Ces règles tiennent toutes dans le service : les dépôts sont des mocks ici, et
 * {@code LeDevisSuitSesEtatsEnBaseTest} rejoue les mêmes gestes sur de vraies lignes.
 */
@ExtendWith(MockitoExtension.class)
class DevisServiceTest {

    @Mock
    private MaintenanceRepository documents;

    @Mock
    private MaintenanceAttachmentRepository piecesJointes;

    @Mock
    private PieceRechangeRepository catalogue;

    @Mock
    private AuditService audit;

    @InjectMocks
    private DevisService devis;

    private static final String ID = "dv-1";

    private static Jwt jeton() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256").subject("usr-1")
                .claim("email", "guichet@higer.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private static MaintenanceDetail ligne(String libelle, LineNature nature, PartSupplier fournisseur) {
        MaintenanceDetail d = new MaintenanceDetail();
        d.setReference("R-1");
        d.setLabel(libelle);
        d.setQuantity(1.0);
        d.setUnitPrice(45.0);
        d.setNature(nature);
        d.setSuppliedBy(fournisseur);
        return d;
    }

    /** Un devis brouillon d'une ligne naturée, prêt à partir chez le client. */
    private static MaintenanceItem brouillon() {
        MaintenanceItem m = new MaintenanceItem();
        m.setId(ID);
        m.setType(MaintenanceType.DEVIS);
        m.setNumber("DV-2026-0007");
        m.setStatus(MaintenanceStatus.DRAFT);
        m.setDetails(new java.util.ArrayList<>(List.of(
                ligne("Main-d'œuvre de la visite", LineNature.LABOR, null))));
        return m;
    }

    private static MaintenanceItem avecEtat(MaintenanceStatus etat) {
        MaintenanceItem m = brouillon();
        m.setStatus(etat);
        return m;
    }

    private static String motifRefus(ResponseStatusException e) {
        return e.getReason();
    }

    // ------------------------------------------------------------------ envoyer

    @Test
    void envoyer_un_brouillon_pose_envoye_et_la_date_d_envoi() {
        MaintenanceItem papier = brouillon();
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(papier));

        MaintenanceItem rendu = devis.envoyer(ID, jeton());

        assertEquals(MaintenanceStatus.SENT, rendu.getStatus());
        assertEquals(LocalDate.now(), rendu.getSentOn());
        assertNull(rendu.getAcceptedOn(), "envoyer n'est pas un accord");
        verify(documents).save(papier);
        verify(audit).record(eq("UPDATE"), eq("MAINTENANCE"), eq(ID), anyString(), any(Jwt.class));
    }

    @Test
    void envoyer_un_devis_vide_se_refuse() {
        MaintenanceItem vide = brouillon();
        vide.getDetails().clear();
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(vide));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.envoyer(ID, jeton()));

        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refus.getReason().contains("aucune ligne"), motifRefus(refus));
        verify(documents, never()).save(any());
    }

    @Test
    void envoyer_deux_fois_se_refuse_et_nomme_l_etat_et_le_jour() {
        MaintenanceItem parti = avecEtat(MaintenanceStatus.SENT);
        parti.setSentOn(LocalDate.of(2026, 11, 1));
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(parti));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.envoyer(ID, jeton()));

        assertTrue(refus.getReason().contains("n'est plus brouillon"), motifRefus(refus));
        assertTrue(refus.getReason().contains("Envoyé au client"), motifRefus(refus));
        assertTrue(refus.getReason().contains("01/11/2026"), motifRefus(refus));
        verify(documents, never()).save(any());
    }

    @Test
    void envoyer_refuse_une_ligne_sans_nature() {
        MaintenanceItem papier = brouillon();
        papier.getDetails().add(ligne("Plaquette de frein", null, null));
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(papier));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.envoyer(ID, jeton()));

        assertTrue(refus.getReason().contains("Ligne 2"), motifRefus(refus));
        assertTrue(refus.getReason().contains("main-d'œuvre, pièce ou déplacement"), motifRefus(refus));
        verify(documents, never()).save(any());
    }

    // ------------------------------------------------------------------ accord

    @Test
    void accord_sans_le_papier_signe_se_refuse() {
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(avecEtat(MaintenanceStatus.SENT)));
        when(piecesJointes.countByMaintenanceItemId(ID)).thenReturn(0L);

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.accord(ID, jeton()));

        assertTrue(refus.getReason().contains("Aucun papier joint"), motifRefus(refus));
        verify(documents, never()).save(any());
    }

    @Test
    void accord_depuis_envoye_avec_le_scan_pose_accepte_et_la_date() {
        MaintenanceItem papier = avecEtat(MaintenanceStatus.SENT);
        papier.setSentOn(LocalDate.of(2026, 11, 1));
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(papier));
        when(piecesJointes.countByMaintenanceItemId(ID)).thenReturn(1L);

        MaintenanceItem rendu = devis.accord(ID, jeton());

        assertEquals(MaintenanceStatus.VALIDATED, rendu.getStatus());
        assertEquals(LocalDate.now(), rendu.getAcceptedOn());
        assertEquals(LocalDate.of(2026, 11, 1), rendu.getSentOn(), "la date d'envoi reste");
        verify(documents).save(papier);
    }

    @Test
    void accord_sur_un_brouillon_se_refuse_il_faut_envoyer_avant() {
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(brouillon()));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.accord(ID, jeton()));

        assertTrue(refus.getReason().contains("n'a pas encore été envoyé"), motifRefus(refus));
    }

    @Test
    void un_accord_ne_se_pose_pas_deux_fois() {
        MaintenanceItem signe = avecEtat(MaintenanceStatus.VALIDATED);
        signe.setAcceptedOn(LocalDate.of(2026, 10, 30));
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(signe));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.accord(ID, jeton()));

        assertTrue(refus.getReason().contains("déjà accepté le 30/10/2026"), motifRefus(refus));
    }

    @Test
    void un_refus_ne_se_retourne_pas_en_accord_et_le_motif_reste_lisible() {
        MaintenanceItem refuse = avecEtat(MaintenanceStatus.REFUSED);
        refuse.setRefusedOn(LocalDate.of(2026, 11, 2));
        refuse.setRefusalReason("trop cher pour cette année");
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(refuse));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.accord(ID, jeton()));

        assertTrue(refus.getReason().contains("trop cher pour cette année"), motifRefus(refus));
        assertTrue(refus.getReason().contains("nouveau devis"), motifRefus(refus));
    }

    // ------------------------------------------------------------------ refus

    @Test
    void un_refus_sans_raison_ecrite_se_refuse() {
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(avecEtat(MaintenanceStatus.SENT)));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.refus(ID, new RefusRequest("   "), jeton()));

        assertTrue(refus.getReason().contains("Écrivez le motif"), motifRefus(refus));
        verify(documents, never()).save(any());
    }

    @Test
    void refus_depuis_envoye_ecrit_la_raison_et_la_date() {
        MaintenanceItem papier = avecEtat(MaintenanceStatus.SENT);
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(papier));

        MaintenanceItem rendu = devis.refus(ID, new RefusRequest("la pièce est attendue ailleurs"), jeton());

        assertEquals(MaintenanceStatus.REFUSED, rendu.getStatus());
        assertEquals(LocalDate.now(), rendu.getRefusedOn());
        assertEquals("la pièce est attendue ailleurs", rendu.getRefusalReason());
        assertNull(rendu.getAcceptedOn());
        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("UPDATE"), eq("MAINTENANCE"), eq(ID), trace.capture(), any(Jwt.class));
        assertTrue(trace.getValue().contains("la pièce est attendue ailleurs"), trace.getValue());
    }

    @Test
    void un_brouillon_ne_peut_pas_avoir_ete_refuse() {
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(brouillon()));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.refus(ID, new RefusRequest("trop cher"), jeton()));

        assertTrue(refus.getReason().contains("n'a jamais été envoyé"), motifRefus(refus));
        verify(documents, never()).save(any());
    }

    @Test
    void un_refus_ne_se_pose_pas_deux_fois() {
        MaintenanceItem refuse = avecEtat(MaintenanceStatus.REFUSED);
        refuse.setRefusalReason("trop cher");
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(refuse));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.refus(ID, new RefusRequest("et reporté"), jeton()));

        assertTrue(refus.getReason().contains("déjà refusé"), motifRefus(refus));
    }

    @Test
    void on_n_enregistre_pas_un_refus_apres_un_accord() {
        when(documents.findById(ID)).thenReturn(java.util.Optional.of(avecEtat(MaintenanceStatus.VALIDATED)));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.refus(ID, new RefusRequest("trop cher"), jeton()));

        assertTrue(refus.getReason().contains("est accepté"), motifRefus(refus));
    }

    // ------------------------------------------------------------------ le reste du formulaire

    @Test
    void un_devis_ne_nait_pas_accepte() {
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierCreation(MaintenanceType.DEVIS, MaintenanceStatus.VALIDATED));

        assertTrue(refus.getReason().contains("commence brouillon"), motifRefus(refus));
    }

    /** Un état d'accord ne passe plus par la création : le formulaire n'a plus la case. */
    @Test
    void aucun_etat_d_entree_n_est_accepte_sur_un_devis() {
        assertThrows(ResponseStatusException.class,
                () -> devis.verifierCreation(MaintenanceType.DEVIS, MaintenanceStatus.SENT));
        assertThrows(ResponseStatusException.class,
                () -> devis.verifierCreation(MaintenanceType.DEVIS, MaintenanceStatus.REFUSED));
        assertDoesNotThrow(() -> devis.verifierCreation(MaintenanceType.DEVIS, MaintenanceStatus.DRAFT));
    }

    /** Les autres papiers gardent leur habitude : un BL né « livré » est le cas normal. */
    @Test
    void la_garde_ne_concerne_que_le_devis() {
        assertDoesNotThrow(() -> devis.verifierCreation(MaintenanceType.BL, MaintenanceStatus.DELIVERED));
        assertDoesNotThrow(() -> devis.verifierCreation(MaintenanceType.OPERATION, MaintenanceStatus.IN_PROGRESS));
    }

    @Test
    void l_etat_d_un_devis_ne_se_change_pas_au_clavier() {
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierModification(brouillon(), MaintenanceStatus.VALIDATED));

        assertTrue(refus.getReason().contains("ne se change pas au clavier"), motifRefus(refus));
        assertTrue(refus.getReason().contains("Envoyer au client"), motifRefus(refus));
    }

    @Test
    void un_devis_envoye_n_est_plus_modifiable_et_le_remede_est_ecrit() {
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierModification(avecEtat(MaintenanceStatus.SENT), MaintenanceStatus.SENT));

        assertTrue(refus.getReason().contains("n'est plus modifiable"), motifRefus(refus));
        assertTrue(refus.getReason().contains("parole donnée"), motifRefus(refus));
    }

    @Test
    void un_devis_accepte_revoie_le_travail_sur_l_ordre() {
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierModification(avecEtat(MaintenanceStatus.VALIDATED), null));

        assertTrue(refus.getReason().contains("ordre de travail"), motifRefus(refus));
    }

    @Test
    void un_brouillon_se_modifie_librement() {
        assertDoesNotThrow(() -> devis.verifierModification(brouillon(), null));
        assertDoesNotThrow(() -> devis.verifierModification(avecEtat(MaintenanceStatus.IN_PROGRESS),
                MaintenanceStatus.IN_PROGRESS));
    }

    /** Un ordre ou un BL ne se naturait pas ici : la garde ne regarde que le devis. */
    @Test
    void les_lignes_d_un_ordre_ne_sont_pas_contraintes() {
        MaintenanceItem ordre = brouillon();
        ordre.setType(MaintenanceType.OPERATION);
        ordre.getDetails().clear();
        ordre.getDetails().add(ligne("Vidange", null, null));

        assertDoesNotThrow(() -> devis.verifierLignes(ordre));
    }

    // ------------------------------------------------------------------ nature et « fourni par »

    @Test
    void une_ligne_sans_designation_se_refuse() {
        MaintenanceItem papier = brouillon();
        papier.getDetails().set(0, ligne("  ", LineNature.LABOR, null));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignes(papier));

        assertTrue(refus.getReason().contains("désignation"), motifRefus(refus));
    }

    @Test
    void une_piece_sans_fournisseur_nomme_la_ligne_et_la_question() {
        MaintenanceItem papier = brouillon();
        papier.getDetails().set(0, ligne("Filtre à huile", LineNature.PART, null));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignes(papier));

        assertTrue(refus.getReason().contains("Ligne 1"), motifRefus(refus));
        assertTrue(refus.getReason().contains("Saphir ou le client"), motifRefus(refus));
    }

    @Test
    void un_fourni_par_sur_une_main_d_oeuvre_se_refuse() {
        MaintenanceItem papier = brouillon();
        papier.getDetails().set(0, ligne("Main-d'œuvre", LineNature.LABOR, PartSupplier.SAPHIR));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignes(papier));

        assertTrue(refus.getReason().contains("que sur une pièce"), motifRefus(refus));
    }

    @Test
    void un_devis_complet_passe() {
        MaintenanceItem papier = brouillon();
        papier.getDetails().clear();
        papier.getDetails().add(ligne("Main-d'œuvre", LineNature.LABOR, null));
        papier.getDetails().add(ligne("Filtre à huile", LineNature.PART, PartSupplier.SAPHIR));
        papier.getDetails().add(ligne("Pièce fournie par le client", LineNature.PART, PartSupplier.CLIENT));
        papier.getDetails().add(ligne("Déplacement", LineNature.TRAVEL, null));

        assertDoesNotThrow(() -> devis.verifierLignes(papier));
        assertNotNull(papier.getDetails().get(1).getSuppliedBy());
    }

    // ------------------------------------------------------------------ la pièce du catalogue (V46)

    /**
     * Une ligne qui cite une pièce n'est pas une saisie libre : c'est la copie d'une
     * ligne du catalogue, prise au clic. Le service revérifie à l'écriture ce que
     * l'écran a posé — un écran qui montre un prix n'empêche pas un autre client
     * d'envoyer le sien.
     */
    private static PieceRechange pieceDuCatalogue(String reference, String designation, String prixVente) {
        PieceRechange piece = new PieceRechange();
        piece.setId("pc-" + reference);
        piece.setReference(reference);
        piece.setDesignationFr(designation);
        piece.setPrixVente(prixVente == null ? null : new BigDecimal(prixVente));
        return piece;
    }

    private static MaintenanceItem devisAvecUnePiece(String reference, String label, double prix,
                                                     String pieceId) {
        MaintenanceItem papier = brouillon();
        MaintenanceDetail d = ligne(label, LineNature.PART, PartSupplier.SAPHIR);
        d.setReference(reference);
        d.setUnitPrice(prix);
        d.setPieceId(pieceId);
        papier.getDetails().clear();
        papier.getDetails().add(d);
        return papier;
    }

    @Test
    void une_piece_inconnue_du_catalogue_arrete_lecriture() {
        when(catalogue.findById("pc-disparue")).thenReturn(Optional.empty());
        MaintenanceItem papier = devisAvecUnePiece("F-10", "Filtre à huile", 86.5, "pc-disparue");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignesDeCatalogue(papier, true));

        assertTrue(refus.getReason().contains("Ligne 1"), motifRefus(refus));
        assertTrue(refus.getReason().contains("pc-disparue"), motifRefus(refus));
        assertTrue(refus.getReason().contains("Gestion PR"), motifRefus(refus));
    }

    @Test
    void une_reference_retouchee_au_clavier_se_refuse() {
        when(catalogue.findById("pc-F-10"))
                .thenReturn(Optional.of(pieceDuCatalogue("F-10", "Filtre à huile", "86.500")));
        MaintenanceItem papier = devisAvecUnePiece("F-10-bis", "Filtre à huile", 86.5, "pc-F-10");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignesDeCatalogue(papier, true));

        assertTrue(refus.getReason().contains("celles du catalogue"), motifRefus(refus));
    }

    @Test
    void un_prix_de_catalogue_passe_pour_n_importe_quel_compte() {
        when(catalogue.findById("pc-F-10"))
                .thenReturn(Optional.of(pieceDuCatalogue("F-10", "Filtre à huile", "86.500")));
        MaintenanceItem papier = devisAvecUnePiece("F-10", "Filtre à huile", 86.5, "pc-F-10");

        assertDoesNotThrow(() -> devis.verifierLignesDeCatalogue(papier, false));
    }

    @Test
    void un_prix_ajuste_passe_pour_un_compte_autorise() {
        when(catalogue.findById("pc-F-10"))
                .thenReturn(Optional.of(pieceDuCatalogue("F-10", "Filtre à huile", "86.500")));
        MaintenanceItem papier = devisAvecUnePiece("F-10", "Filtre à huile", 79.0, "pc-F-10");

        assertDoesNotThrow(() -> devis.verifierLignesDeCatalogue(papier, true));
    }

    @Test
    void un_prix_ajuste_se_refuse_pour_un_compte_de_l_atelier() {
        when(catalogue.findById("pc-F-10"))
                .thenReturn(Optional.of(pieceDuCatalogue("F-10", "Filtre à huile", "86.500")));
        MaintenanceItem papier = devisAvecUnePiece("F-10", "Filtre à huile", 79.0, "pc-F-10");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignesDeCatalogue(papier, false));

        assertTrue(refus.getReason().contains("administrateur"), motifRefus(refus));
        assertTrue(refus.getReason().contains("86.500"), motifRefus(refus));
    }

    @Test
    void une_piece_sans_prix_de_vente_ne_s_ecrit_pas_zero() {
        when(catalogue.findById("pc-F-10"))
                .thenReturn(Optional.of(pieceDuCatalogue("F-10", "Filtre à huile", null)));
        MaintenanceItem papier = devisAvecUnePiece("F-10", "Filtre à huile", 0.0, "pc-F-10");

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> devis.verifierLignesDeCatalogue(papier, true));

        assertTrue(refus.getReason().contains("Chiffrez-la dans Gestion PR"), motifRefus(refus));
    }

    @Test
    void une_ligne_saisie_a_la_main_ne_lit_pas_le_catalogue() {
        MaintenanceItem papier = devisAvecUnePiece("LIBRE-1", "Pièce apportée", 0.0, null);

        assertDoesNotThrow(() -> devis.verifierLignesDeCatalogue(papier, false));
        verify(catalogue, never()).findById(anyString());
    }

    @Test
    void un_ordre_garde_ses_lignes_meme_citees_du_catalogue() {
        MaintenanceItem ordre = devisAvecUnePiece("F-10", "Filtre à huile", 12.0, "pc-F-10");
        ordre.setType(MaintenanceType.OPERATION);

        assertDoesNotThrow(() -> devis.verifierLignesDeCatalogue(ordre, false));
        verify(catalogue, never()).findById(anyString());
    }
}
