package tn.higer.sav_backend.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ContractDtos.ContractLineRequest;
import tn.higer.sav_backend.dto.ContractDtos.ContractRequest;
import tn.higer.sav_backend.dto.ContractDtos.ContractResponse;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.service.AuditService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du contrat : numérotation serveur, totaux recalculés à la lecture
 * (HT + TVA = TTC au centime près), règles d'ajout d'une ligne, masquage des montants
 * pour le rôle consultation.
 */
@ExtendWith(MockitoExtension.class)
class ContractControllerTest {

    @Mock
    private ContractRepository contracts;

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ClientRepository clients;

    @Mock
    private AuditService audit;

    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "x@y.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static Authentication role(String authority) {
        return new UsernamePasswordAuthenticationToken("x@y.tn", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private ContractController controller() {
        // documentService, pdfService et draftService ne sont pas exercés par ces tests (endpoints
        // dédiés) : null suffit, les méthodes testées ne les touchent jamais.
        ContractController controller = new ContractController(contracts, vehicles, clients, audit,
                null, null, null);
        ReflectionTestUtils.setField(controller, "tvaRateConfig", 19);
        return controller;
    }

    private static Contract contrat(String id) {
        Contract c = new Contract();
        c.setId(id);
        c.setNumber("CT-HIG-" + Year.now().getValue() + "-001");
        c.setContractDate(DATE);
        c.setClientId("cli-1");
        c.setPaymentTerms("30 jours virement");
        c.setDeliveryAddress("Dépôt Ariana");
        return c;
    }

    private static Vehicle ligne(String id, String prix) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setVin("VIN" + id);
        v.setRegistration("AB-123-CD");
        v.setModel("Confort");
        v.setClientId("cli-1");
        v.setCommissioningDate(DATE);
        v.setContractId("ctr-1");
        v.setUnitPriceHt(prix == null ? null : new BigDecimal(prix));
        return v;
    }

    private static void argentEquals(String attendu, BigDecimal obtenu) {
        assertEquals(0, new BigDecimal(attendu).compareTo(obtenu),
                () -> "Attendu " + attendu + " mais obtenu " + obtenu);
    }

    @Test
    void numerotation_genereeParLeServeur_premierContratDeLAnnee() {
        when(clients.existsById("cli-1")).thenReturn(true);
        when(contracts.findNumbersByPrefix(anyString())).thenReturn(List.of());

        ContractResponse response = controller()
                .create(new ContractRequest(DATE, "cli-1", null, null, null, null, null, null, null, null, null, null), jwt());

        assertEquals("CT-HIG-" + Year.now().getValue() + "-001", response.number());
        assertEquals(0, response.vehicleCount());
    }

    @Test
    void numerotation_incrementeeSurLePlusHautNumeroExistant() {
        when(clients.existsById("cli-1")).thenReturn(true);
        when(contracts.findNumbersByPrefix(anyString()))
                .thenReturn(List.of("CT-HIG-" + Year.now().getValue() + "-014"));

        ContractResponse response = controller()
                .create(new ContractRequest(DATE, "cli-1", null, null, null, null, null, null, null, null, null, null), jwt());

        assertEquals("CT-HIG-" + Year.now().getValue() + "-015", response.number());
    }

    @Test
    void totaux_recalculsALecture_laTvaSuitLeTauxServeur() {
        when(contracts.findById("ctr-1")).thenReturn(Optional.of(contrat("ctr-1")));
        when(vehicles.findByContractId("ctr-1"))
                .thenReturn(List.of(ligne("veh-1", "100000.00"), ligne("veh-2", "150000.00")));

        ContractResponse response = controller().get("ctr-1", role("ROLE_ADMIN"));

        argentEquals("250000.00", response.totalHT());
        argentEquals("47500.00", response.tva());
        // HT + TVA = TTC exactement, même après arrondi au centime.
        argentEquals("297500.00", response.totalTTC());
        assertEquals(2, response.vehicleCount());
        assertEquals(2, response.lines().size());
    }

    @Test
    void totaux_consultation_masquesAlaLecture() {
        when(contracts.findById("ctr-1")).thenReturn(Optional.of(contrat("ctr-1")));
        when(vehicles.findByContractId("ctr-1")).thenReturn(List.of(ligne("veh-1", "100000.00")));

        ContractResponse response = controller().get("ctr-1", role("ROLE_CONSULTATION"));

        assertNull(response.totalHT(), "Le total HT ne doit pas sortir vers la consultation");
        assertNull(response.tva());
        assertNull(response.totalTTC());
        assertNull(response.paymentTerms(), "Les conditions de paiement sont confidentielles");
        assertNull(response.lines().get(0).unitPriceHt(), "Le prix d'une ligne est masqué");
        assertEquals("CT-HIG-" + Year.now().getValue() + "-001", response.number(),
                "Le numéro et les dates restent lisibles : la consultation voit quoi, pas à combien");
    }

    @Test
    void ajoutLigne_refuseSansPrixUnitaire() {
        when(clients.existsById("cli-1")).thenReturn(true);
        when(contracts.findNumbersByPrefix(anyString())).thenReturn(List.of());
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(ligne("veh-1", null)));

        ContractRequest request = new ContractRequest(DATE, "cli-1", null, null, null,
                List.of(new ContractLineRequest("veh-1", null)), null, null, null, null, null, null);
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(request, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("prix unitaire"),
                "Le message doit nommer le champ manquant : " + erreur.getReason());
    }

    @Test
    void ajoutLigne_refuseSiLeVehiculeNestPasDuClientDuContrat() {
        when(clients.existsById("cli-1")).thenReturn(true);
        when(contracts.findNumbersByPrefix(anyString())).thenReturn(List.of());
        Vehicle autreClient = ligne("veh-9", "120000.00");
        autreClient.setClientId("cli-2");
        when(vehicles.findById("veh-9")).thenReturn(Optional.of(autreClient));

        ContractRequest request = new ContractRequest(DATE, "cli-1", null, null, null,
                List.of(new ContractLineRequest("veh-9", new BigDecimal("120000.00"))), null, null, null, null, null, null);
        ResponseStatusException erreur = assertThrows(ResponseStatusException.class,
                () -> controller().create(request, jwt()));

        assertEquals(HttpStatus.BAD_REQUEST, erreur.getStatusCode());
        assertTrue(erreur.getReason().contains("n'appartient pas"), erreur.getReason());
    }

    @Test
    void ajoutLigne_reporteLePrixSurLeVehicule() {
        when(clients.existsById("cli-1")).thenReturn(true);
        when(contracts.findNumbersByPrefix(anyString())).thenReturn(List.of());
        Vehicle vehicule = ligne("veh-1", null);
        vehicule.setContractId(null);
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(vehicule));

        ContractRequest request = new ContractRequest(DATE, "cli-1", null, null, null,
                List.of(new ContractLineRequest("veh-1", new BigDecimal("185000.00"))), null, null, null, null, null, null);
        controller().create(request, jwt());

        // La ligne du contrat écrit le prix sur le véhicule : source unique du total.
        assertEquals(new BigDecimal("185000.00"), vehicule.getUnitPriceHt());
        assertTrue(vehicule.getContractId().startsWith("ctr-"),
                "Le véhicule doit être rattaché au contrat créé");
        verify(vehicles).save(vehicule);
    }
}
