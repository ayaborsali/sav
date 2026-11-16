package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleRequest;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleResponse;
import tn.higer.sav_backend.dto.VehicleDtos.VinDetectionResponse;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.VehicleMileageService;
import tn.higer.sav_backend.service.VinDetectionService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Tests unitaires de la règle de garantie : dérivation serveur quand warrantyEndDate
 * est absent, rejet 400 si la date fournie précède la mise en service, conservation sinon.
 * Et règle contrat : pas de rattachement sans prix unitaire HT.
 */
@ExtendWith(MockitoExtension.class)
class VehicleControllerTest {

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ContractRepository contracts;

    @Mock
    private AuditService audit;

    @Mock
    private VinDetectionService vinDetection;

    /** La porte du compteur : ici on vérifie que le contrôleur y passe, pas ce qu'elle décide. */
    @Mock
    private VehicleMileageService compteur;

    private static final LocalDate MISE_EN_SERVICE = LocalDate.of(2024, 1, 15);

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "x@y.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static VehicleRequest request(String motorisation, LocalDate finGarantie) {
        return new VehicleRequest("VIN0001", "AB-123-CD", "Standard", 2024, "cli-1",
                1000L, null, MISE_EN_SERVICE, finGarantie, motorisation, null, null, null, null, null);
    }

    private VehicleController controller() {
        return new VehicleController(vehicles, contracts, audit, vinDetection, compteur);
    }

    private static Contract contratDe(String clientId) {
        Contract c = new Contract();
        c.setId("ctr-1");
        c.setNumber("CT-HIG-2026-001");
        c.setClientId(clientId);
        c.setContractDate(MISE_EN_SERVICE);
        return c;
    }

    @Test
    void garantieDerivee_dieselEtElectrique_donentDureesDifferentes() {
        VehicleController controller = controller();

        VehicleResponse diesel = controller.create(request("diesel", null), jwt());
        VehicleResponse electrique = controller.create(request("electrique", null), jwt());

        // 24 mois pour le diesel, 60 mois pour l'électrique (table constructeur).
        assertEquals(MISE_EN_SERVICE.plusMonths(24), diesel.warrantyEndDate());
        assertEquals(MISE_EN_SERVICE.plusMonths(60), electrique.warrantyEndDate());
        assertNotEquals(diesel.warrantyEndDate(), electrique.warrantyEndDate(),
                "Deux motorisations différentes doivent donner des durées de garantie différentes");
    }

    /**
     * La gamme — Confort ou Standard — décide de la grille d'entretien du bus. Un
     * modèle commercial (« Higer KLQ6129G (12m Urbain) ») ne dit pas cette gamme :
     * la fiche est refusée à l'enregistrement, sinon la vente du forfait échouerait
     * plus tard sur un bus pourtant sauvegardé.
     */
    @Test
    void unBusQuiNeDitPasSaGammeEstRefuse() {
        VehicleRequest ancienModele = new VehicleRequest("VIN0007", "AB-129-CD",
                "Higer KLQ6129G (12m Urbain)", 2024, "cli-1", 1000L, null, MISE_EN_SERVICE, null,
                "diesel", null, null, null, null, null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(ancienModele, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("Confort ou Standard"), erreur.getReason());
        verify(vehicles, never()).save(any());
    }

    @Test
    void uneGammeOublieeEstRefusee() {
        VehicleRequest sansGamme = new VehicleRequest("VIN0008", "AB-130-CD", "  ", 2024, "cli-1",
                1000L, null, MISE_EN_SERVICE, null, "diesel", null, null, null, null, null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(sansGamme, jwt()));

        assertTrue(erreur.getReason().contains("gamme"), erreur.getReason());
        verify(vehicles, never()).save(any());
    }

    /** Ce que le document Higer écrit en majuscules se relit comme le choix de l'écran. */
    @Test
    void laGammeDuDocumentSeRangeSurLaValeurDeLEcran() {
        VehicleRequest fiche = new VehicleRequest("VIN0009", "AB-131-CD", "BUS HIGER CONFORT", 2024,
                "cli-1", 1000L, null, MISE_EN_SERVICE, null, "diesel", null, null, null, null, null);

        VehicleResponse created = controller().create(fiche, jwt());

        assertEquals("Confort", created.model());
    }

    @Test
    void garantieRefusee_quandLaDatePreceneLaMiseEnService() {
        VehicleController controller = controller();
        LocalDate finAnterieure = MISE_EN_SERVICE.minusDays(1);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller.create(request("diesel", finAnterieure), jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("antérieure"),
                "Le message doit expliquer le rejet de la date incohérente");
    }

    @Test
    void garantieConseree_quandLaDateFournieEstValide() {
        VehicleController controller = controller();
        LocalDate finFournie = MISE_EN_SERVICE.plusMonths(30);

        VehicleResponse response = controller.create(request("diesel", finFournie), jwt());

        // Date cohérente : on la conserve telle quelle, sans la recalculer.
        assertEquals(finFournie, response.warrantyEndDate());
        ArgumentCaptor<Vehicle> capture = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicles).save(capture.capture());
        assertEquals(finFournie, capture.getValue().getWarrantyEndDate());
    }

    @Test
    void contratRefuse_quandLePrixUnitaireEstAbsent() {
        when(contracts.findById("ctr-1")).thenReturn(Optional.of(contratDe("cli-1")));
        VehicleController controller = controller();
        VehicleRequest avecContratSansPrix = new VehicleRequest("VIN0002", "AB-124-CD", "Standard", 2024,
                "cli-1", 0L, null, MISE_EN_SERVICE, null, "diesel", null, null, "ctr-1", null, null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller.create(avecContratSansPrix, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("prix unitaire"),
                "Le message doit dire qu'un véhicule sous contrat exige un prix");
    }

    @Test
    void contratRefuse_quandIlNExistePas() {
        when(contracts.findById(anyString())).thenReturn(Optional.empty());
        VehicleController controller = controller();
        VehicleRequest avecContratInconnu = new VehicleRequest("VIN0003", "AB-125-CD", "Standard", 2024,
                "cli-1", 0L, null, MISE_EN_SERVICE, null, "diesel", null, null, "ctr-inexistant",
                new BigDecimal("150000.00"), null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller.create(avecContratInconnu, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("Contrat introuvable"));
    }

    @Test
    void contratRefuse_quandIlNeConcernepasLeClientDuVehicule() {
        when(contracts.findById("ctr-1")).thenReturn(Optional.of(contratDe("cli-2")));
        VehicleController controller = controller();
        VehicleRequest mauvaisClient = new VehicleRequest("VIN0005", "AB-127-CD", "Standard", 2024,
                "cli-1", 0L, null, MISE_EN_SERVICE, null, "diesel", null, null, "ctr-1",
                new BigDecimal("150000.00"), null);

        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller.create(mauvaisClient, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("autre client"),
                "Le contrat d'un autre client doit être nommé dans le message : " + erreur.getReason());
    }

    @Test
    void prixConsenti_horsContrat() {
        VehicleController controller = controller();
        VehicleRequest horsContrat = new VehicleRequest("VIN0004", "AB-126-CD", "Standard", 2024,
                "cli-1", 0L, null, MISE_EN_SERVICE, null, "diesel", null, null, "   ",
                new BigDecimal("150000.00"), null);

        VehicleResponse response = controller.create(horsContrat, jwt());

        // contractId blanc côté client = pas de contrat, mais le prix reste enregistré.
        assertNull(response.contractId());
        assertEquals(new BigDecimal("150000.00"), response.unitPriceHt());
    }

    // --------------------------------------------------------- lecture IA du châssis (VIN)

    /**
     * Phase G : le compteur d'une fiche de bus ne se copie plus dans le contrôleur, il
     * passe par la porte unique — celle qui connaît le dernier relevé du technicien et
     * qui exige un motif écrit pour redescendre. Le motif doit suivre la valeur jusqu'à
     * la porte, sinon la baisse ne sera jamais acceptée.
     */
    @Test
    void leCompteurPasseParLaPorteUniqueEtLeMotifSuit() {
        VehicleRequest avecMotif = new VehicleRequest("VIN0006", "AB-128-CD", "Standard", 2024,
                "cli-1", 5_400L, "compteur remplacé après panne", MISE_EN_SERVICE, null, "diesel",
                null, null, null, null, null);

        controller().create(avecMotif, jwt());

        ArgumentCaptor<Vehicle> capture = ArgumentCaptor.forClass(Vehicle.class);
        verify(compteur).saisieAtelier(capture.capture(), eq(5_400L), eq("compteur remplacé après panne"), any());
        assertEquals("VIN0006", capture.getValue().getVin());
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("image", "plaque.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
    }

    @Test
    void chassisLuEtDejaAuPortefeuille_leDitEnDurALecran() throws Exception {
        when(vinDetection.detect(anyString(), any())).thenReturn(
                new VinDetectionService.Outcome("complete", "LKLR1KSD5NC123456", null, 17));
        Vehicle deja = new Vehicle();
        deja.setId("veh-7");
        deja.setVin("LKLR1KSD5NC123456");
        deja.setRegistration("215 TU 8920");
        when(vehicles.findByVinIgnoreCase("LKLR1KSD5NC123456")).thenReturn(Optional.of(deja));

        VinDetectionResponse reponse = controller().detectVin(photo(), jwt());

        assertEquals("complete", reponse.status());
        assertEquals("LKLR1KSD5NC123456", reponse.vin());
        assertTrue(reponse.alreadyRegistered());
        assertEquals("veh-7", reponse.existingVehicleId());
        assertTrue(reponse.message().contains("existe déjà"),
                "L'écran doit signaler le doublon : " + reponse.message());
        assertTrue(reponse.message().contains("215 TU 8920"));
    }

    /** Une lecture partielle ne pré-remplit rien et dit combien de caractères manquent. */
    @Test
    void lecturePartielle_nePreRemplitPasLeChassis() throws Exception {
        when(vinDetection.detect(anyString(), any())).thenReturn(
                new VinDetectionService.Outcome("partial", null, "LKLR1KSD5NC12", 14));

        VinDetectionResponse reponse = controller().detectVin(photo(), jwt());

        assertNull(reponse.vin(), "Seul un VIN complet a le droit de pré-remplir le formulaire");
        assertEquals("partial", reponse.status());
        assertEquals(14, reponse.readChars());
        assertFalse(reponse.alreadyRegistered());
        assertTrue(reponse.message().contains("14 caractères sur 17"), reponse.message());
    }

    @Test
    void aucuneLecture_ditCommentReprendreLaPhoto() throws Exception {
        when(vinDetection.detect(anyString(), any())).thenReturn(
                new VinDetectionService.Outcome("none", null, null, 0));

        VinDetectionResponse reponse = controller().detectVin(photo(), jwt());

        assertEquals("none", reponse.status());
        assertTrue(reponse.message().contains("manuellement"),
                "La saisie manuelle doit rester proposée : " + reponse.message());
    }
}

