package tn.higer.sav_backend.controller;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceLineDto;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.BonLivraisonService;
import tn.higer.sav_backend.service.DevisService;
import tn.higer.sav_backend.service.MaintenanceAttachmentService;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceDocumentService;
import tn.higer.sav_backend.service.MaintenancePdfService;
import tn.higer.sav_backend.service.OrdreService;
import tn.higer.sav_backend.service.SoldPlanService;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du calcul des totaux : totalHT = somme (qté × prix),
 * totalTTC = totalHT × (1 + TVA) arrondi au centime, taux lu défensivement.
 *
 * La phase C ajoute une chose que ces tests devaient garder à l'oeil : le
 * rattachement à un forfait est vérifié AVANT l'enregistrement, jamais après. Un
 * refus ne doit pas laisser un devis à moitié écrit avec un palier coché.
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceControllerTest {

    @Mock
    private MaintenanceRepository maintenance;

    @Mock
    private SoldPlanService forfaits;

    @Mock
    private MaintenanceAttachmentService pieces;

    @Mock
    private MaintenanceChainService chaine;

    @Mock
    private DevisService devis;

    @Mock
    private OrdreService ordre;

    @Mock
    private BonLivraisonService bons;

    @Mock
    private MaintenanceDocumentService documentService;

    @Mock
    private MaintenancePdfService pdfService;

    @Mock
    private AuditService audit;

    private static final double DELTA = 0.0001;

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "x@y.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private MaintenanceController controllerAvecTaux(double taux) {
        MaintenanceController controller =
                new MaintenanceController(maintenance, forfaits, pieces, chaine, devis, ordre, bons,
                        documentService, pdfService, audit);
        ReflectionTestUtils.setField(controller, "tvaRateConfig", taux);
        return controller;
    }

    /**
     * Une ligne de devis telle que l'écran l'envoie depuis V36 : une nature (« pièce »)
     * et un fournisseur (« Saphir »). Le contrôle de ces deux cases est dans
     * {@code DevisServiceTest} ; ici le service est un mock, ces tests gardent les
     * totaux et l'ordre des gestes.
     */
    private static MaintenanceLineDto piece(String reference, String label, double quantite, double prix) {
        return new MaintenanceLineDto(reference, label, quantite, prix, null, "part", "saphir",
                null, null, null, null, null);
    }

    /** Un document hors forfait : les deux cases de rattachement restent vides. */
    private static MaintenanceRequest request(String numero, MaintenanceLineDto... lignes) {
        return new MaintenanceRequest(MaintenanceType.DEVIS, numero, "veh-1", "cli-1",
                LocalDate.of(2024, 3, 1), MaintenanceStatus.DRAFT, null, null,
                "Technicien", "Description", null, null, null, List.of(lignes), null, null);
    }

    @Test
    void calcul19PourCent_arrondiAuCentime() {
        // 1 × 10,005 = 10,005 HT → 10,01 (arrondi HALF_UP). TTC = 10,005 × 1,19 = 11,90595 → 11,91.
        MaintenanceResponse response = controllerAvecTaux(19)
                .create(request("DEV-1", piece("R1", "Filtre", 1.0, 10.005)), jwt(), null);

        assertEquals(10.01, response.totalHT(), DELTA);
        assertEquals(11.91, response.totalTTC(), DELTA);
    }

    @Test
    void totalSurPlusieursLignes() {
        // 2 × 5,00 = 10 + 3 × 4,00 = 12 → 22,00 HT. TTC = 22 × 1,19 = 26,18.
        MaintenanceResponse response = controllerAvecTaux(19).create(request("DEV-2",
                piece("R1", "Piece A", 2.0, 5.0),
                piece("R2", "Piece B", 3.0, 4.0)), jwt(), null);

        assertEquals(22.0, response.totalHT(), DELTA);
        assertEquals(26.18, response.totalTTC(), DELTA);
    }

    @Test
    void tauxEnFractionEtEnPourcentage_donentLeMemeResultat() {
        // Interprétation défensive : 0.19 (fraction) et 19 (pourcentage) donnent le même TTC.
        MaintenanceResponse fraction = controllerAvecTaux(0.19)
                .create(request("DEV-3", piece("R1", "Piece", 2.0, 3.0)), jwt(), null);
        MaintenanceResponse pourcentage = controllerAvecTaux(19)
                .create(request("DEV-3bis", piece("R1", "Piece", 2.0, 3.0)), jwt(), null);

        assertEquals(6.0, fraction.totalHT(), DELTA);
        assertEquals(7.14, fraction.totalTTC(), DELTA);
        assertEquals(fraction.totalTTC(), pourcentage.totalTTC(), DELTA);
    }

    @Test
    void tauxNegatif_retombeSur19PourCent() {
        // Taux négatif : retour défensif à 19 %. 6,00 HT → TTC 7,14.
        MaintenanceResponse response = controllerAvecTaux(-5)
                .create(request("DEV-4", piece("R1", "Piece", 2.0, 3.0)), jwt(), null);

        assertEquals(6.0, response.totalHT(), DELTA);
        assertEquals(7.14, response.totalTTC(), DELTA);
    }

    // ------------------------------------------------------------------ phase C

    /**
     * Le critère d'acceptation de la phase C, écrit noir sur blanc : un document
     * sans forfait garde ses deux cases vides et son calcul d'avant.
     */
    @Test
    void unDocumentSansForfaitResteSansForfait() {
        MaintenanceResponse response = controllerAvecTaux(19)
                .create(request("BL-5", piece("R1", "Piece", 2.0, 3.0)), jwt(), null);

        assertNull(response.soldPlanId());
        assertNull(response.planStepId());
        assertEquals(6.0, response.totalHT(), DELTA);
    }

    /**
     * L'ordre des gestes est la règle, pas le détail : vérifier avant d'écrire.
     * Un rattachement refusé après coup laisserait un BL enregistré et un palier
     * coché chez le client.
     */
    @Test
    void leRattachementEstVerifiePuisEnregistre() {
        controllerAvecTaux(19).create(request("DEV-6",
                piece("R1", "Piece", 1.0, 10.0)), jwt(), null);

        InOrder ordre = inOrder(forfaits, maintenance);
        ordre.verify(forfaits).verifierRattachement(any(MaintenanceItem.class));
        ordre.verify(maintenance).save(any(MaintenanceItem.class));
        ordre.verify(forfaits).appliquerSuivi(any(MaintenanceItem.class), any(), any(), any());
    }

    /** Le refus du rattachement ne laisse rien derrière lui : ni document, ni palier touché. */
    @Test
    void unRattachementRefuseNenregistreRien() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "palier déjà fait"))
                .when(forfaits).verifierRattachement(any(MaintenanceItem.class));

        assertThrows(ResponseStatusException.class, () -> controllerAvecTaux(19)
                .create(request("DEV-7", piece("R1", "Piece", 1.0, 10.0)), jwt(), null));

        verify(maintenance, never()).save(any(MaintenanceItem.class));
        verify(forfaits, never()).appliquerSuivi(any(), any(), any(), any());
    }

    /**
     * Supprimer un document libère le palier qu'il avait fait et emporte ses pièces
     * jointes — sinon le forfait reste amputé d'une visite sans papier, et le
     * disque garde des scans que plus personne ne peut nommer.
     */
    @Test
    void laSuppressionLibereLePalierEtLesPieces() {
        MaintenanceItem m = new MaintenanceItem();
        m.setId("maint-9");
        m.setNumber("BL-2026-0009");
        m.setSoldPlanId("sp-1");
        m.setPlanStepId("mps-10");
        when(maintenance.findById("maint-9")).thenReturn(Optional.of(m));
        when(pieces.supprimerAvecDocument("maint-9")).thenReturn(2);

        controllerAvecTaux(19).delete("maint-9", jwt());

        verify(pieces).supprimerAvecDocument("maint-9");
        verify(forfaits).relacherLiens(eq(m), any(Jwt.class));
        verify(maintenance).deleteById(anyString());
    }

    /**
     * Le même critère, lu depuis le JSON plutôt que depuis le code : l'écran d'avant
     * les forfaits envoie exactement la requête d'hier, sans « soldPlanId », sans
     * « planStepId » et sans « quantityText ». Il doit être décodé et enregistré
     * comme avant.
     *
     * Ce test passe par Jackson parce qu'un composant de plus dans un record se
     * charge lui-même de la liaison : c'est précisément là qu'un appel ancien casse,
     * et la construction du record en Java ne le verrait pas.
     */
    @Test
    void uneRequeteDavantSansLesChampsForfaitPasseToujours() throws Exception {
        // La configuration de l'application accepte les enums en minuscules ; le
        // mapper la reproduit, sinon ce serait le test qui a tort et pas l'écran.
        ObjectMapper json = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
        MaintenanceRequest legacy = json.readValue("""
                {"type":"bl","number":"BL-2026-0042","vehicleId":"veh-1","clientId":"cli-1",
                 "date":"2026-03-02","status":"delivered","technician":"Khalil",
                 "description":"remplacement du filtre",
                 "details":[{"reference":"F-10","label":"Filtre a huile","quantity":2.0,"unitPrice":11.5}]}
                """, MaintenanceRequest.class);

        assertNull(legacy.soldPlanId());
        assertNull(legacy.planStepId());
        assertNull(legacy.details().get(0).quantityText());

        MaintenanceResponse response = controllerAvecTaux(19).create(legacy, jwt(), null);

        ArgumentCaptor<MaintenanceItem> saved = ArgumentCaptor.forClass(MaintenanceItem.class);
        verify(maintenance).save(saved.capture());
        assertNull(saved.getValue().getSoldPlanId());
        assertNull(saved.getValue().getPlanStepId());
        assertEquals("bl", response.type());
        // Depuis V39, le bon de livraison ne chiffre pas : le prix est sur le devis que le
        // client a signé. Les lignes gardent leur prix hérité, le papier ne porte plus de
        // total — ce test-ci vérifie que la requête « d'avant » passe toujours, pas l'argent.
        assertNull(response.totalHT());
        assertNull(response.totalTTC());
    }

    /**
     * Depuis V38, l'ordre de travail ne chiffre pas : le prix est sur le devis que le
     * client a signé, et le remettre ici donnerait deux montants à défendre pour un
     * même travail. Le serveur ne pose plus de total sur ce papier — les lignes gardent
     * leur prix hérité, parce que c'est lui qui alimente le bon de livraison d'après.
     */
    @Test
    void unOrdreDeTravailNeChiffrePas() {
        MaintenanceRequest papier = new MaintenanceRequest(MaintenanceType.OPERATION, "OR-ESS-1",
                "veh-1", "cli-1", LocalDate.of(2024, 3, 1), MaintenanceStatus.DRAFT, null, null,
                "Khalil", "Visite des 45 000 km", null, null, null,
                List.of(piece("F-10", "Filtre à huile", 2.0, 11.5)), null, null);

        MaintenanceResponse response = controllerAvecTaux(19).create(papier, jwt(), null);

        assertNull(response.totalHT(), "l'ordre n'a plus de total serveur");
        assertNull(response.totalTTC(), "l'ordre n'a plus de total serveur");
        assertEquals("Ordre de travail", response.typeLabel());
        assertEquals("À faire", response.statusLabel(), "le mot de l'état se lit sur l'ordre, pas sur le devis");
    }

    /**
     * Le nom des cases d'une ligne est le contrat avec l'écran. Le 04/11, un `bloc`
     * serveur lu comme `bon` par l'Angular a rendu une fiche vide, en 200 OK, sans
     * qu'un seul test rougisse — d'où cette épingle : l'interface TypeScript
     * `MaintenanceItem.details` (src/app/core/types.ts) nomme exactement ces douze
     * cases, et `maintenance-devis-pieces.spec.ts` relit `pieceId` sur un littéral
     * brut. Renommer d'un seul côté fait rougir l'un des deux.
     */
    @Test
    void une_ligne_expose_a_l_ecran_les_douze_cases_attendues() {
        String[] attendues = {"reference", "label", "quantity", "unitPrice", "quantityText",
                "nature", "suppliedBy", "natureLabel", "suppliedByLabel", "done", "realHours",
                "pieceId"};
        String[] reelles = Arrays.stream(MaintenanceLineDto.class.getRecordComponents())
                .map(RecordComponent::getName).toArray(String[]::new);

        assertArrayEquals(attendues, reelles,
                "l'écran lit `pieceId` : renommer la composante serveur rendrait la ligne muette");
    }
}
