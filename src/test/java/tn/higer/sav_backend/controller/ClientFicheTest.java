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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import tn.higer.sav_backend.dto.ClientDtos.ClientRequest;
import tn.higer.sav_backend.dto.ClientDtos.ClientResponse;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClientType;
import tn.higer.sav_backend.model.CommercialStatus;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.service.AuditService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fiche client (V3 + V5) : les colonnes du classeur commercial font le trajet
 * aller-retour demande → entité → réponse, et le CA comme les conditions de paiement
 * sont masqués au rôle consultation par le serveur. Le registre de commerce et le matricule
 * fiscal sortent pour tous : ce sont les mentions qui identifient le client au contrat, pas des
 * chiffres d'affaires.
 */
@ExtendWith(MockitoExtension.class)
class ClientFicheTest {

    @Mock
    private ClientRepository clients;

    @Mock
    private AuditService audit;

    private static final LocalDate DERNIER_CONTACT = LocalDate.of(2026, 10, 02);

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("usr-1")
                .claim("email", "x@y.tn")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static ClientRequest ficheComplete() {
        return new ClientRequest("Transports du Sahel", "TDS", ClientType.PUBLIC_TRANSPORT,
                "M. Ben Ali", "contact@tds.tn", "71 000 000", "Route de Sousse", "Sousse",
                null, "Remarques", "Directeur technique", "Tunisie", DERNIER_CONTACT,
                "Sami Higer", CommercialStatus.PROSPECT, "6 bus KLQ6129, contrat d'entretien",
                new BigDecimal("1250000.00"), "30 % commande, solde à la livraison",
                "B1234567", "1234567/A/M/000");
    }

    @Test
    void creation_enregistreToutesLesColonnesDeLaFiche() {
        when(clients.existsByCodeIgnoreCase("TDS")).thenReturn(false);
        ClientController controller = new ClientController(clients, audit);

        ClientResponse response = controller.create(ficheComplete(), jwt());

        assertEquals("Directeur technique", response.contactRole());
        assertEquals("Tunisie", response.country());
        assertEquals(DERNIER_CONTACT, response.lastContactAt());
        assertEquals("Sami Higer", response.salesRep());
        assertEquals("prospect", response.commercialStatus(), "Le statut sort en minuscules pour Angular");
        assertEquals("6 bus KLQ6129, contrat d'entretien", response.products());
        assertEquals(new BigDecimal("1250000.00"), response.annualRevenue());
        assertEquals("30 % commande, solde à la livraison", response.paymentTerms());
        assertEquals("B1234567", response.tradeRegister());
        assertEquals("1234567/A/M/000", response.taxId());

        ArgumentCaptor<Client> capture = ArgumentCaptor.forClass(Client.class);
        verify(clients).save(capture.capture());
        assertEquals(CommercialStatus.PROSPECT, capture.getValue().getCommercialStatus());
        assertEquals("B1234567", capture.getValue().getTradeRegister(),
                "Le RC est stocké : le contrat imprimable l'emprunte à la fiche client");
    }

    @Test
    void liste_consultation_nevoitNiChiffreDaffairesNiConditions() {
        Client c = new Client();
        c.setId("cli-1");
        c.setName("Transports du Sahel");
        c.setCode("TDS");
        c.setType(ClientType.PUBLIC_TRANSPORT);
        c.setCommercialStatus(CommercialStatus.ACTIVE);
        c.setAnnualRevenue(new BigDecimal("1250000.00"));
        c.setPaymentTerms("30 % commande");
        c.setTradeRegister("B1234567");
        c.setTaxId("1234567/A/M/000");
        when(clients.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(c)));
        ClientController controller = new ClientController(clients, audit);

        Page<ClientResponse> consultation = controller.list(PageRequest.of(0, 20),
                new UsernamePasswordAuthenticationToken("x@y.tn", null,
                        List.of(new SimpleGrantedAuthority("ROLE_CONSULTATION"))));
        Page<ClientResponse> gestionnaire = controller.list(PageRequest.of(0, 20),
                new UsernamePasswordAuthenticationToken("x@y.tn", null,
                        List.of(new SimpleGrantedAuthority("ROLE_GESTIONNAIRE_SAV"))));

        assertNull(consultation.getContent().get(0).annualRevenue(),
                "Le CA ne doit pas quitter le serveur vers la consultation");
        assertNull(consultation.getContent().get(0).paymentTerms());
        assertEquals(new BigDecimal("1250000.00"), gestionnaire.getContent().get(0).annualRevenue());
        assertEquals("active", gestionnaire.getContent().get(0).commercialStatus());
    }

    @Test
    void consultation_voitLeRegistreDeCommerceMaisPasLeChiffreDAffaires() {
        Client c = new Client();
        c.setId("cli-1");
        c.setName("Transports du Sahel");
        c.setCode("TDS");
        c.setType(ClientType.PUBLIC_TRANSPORT);
        c.setAnnualRevenue(new BigDecimal("1250000.00"));
        c.setTradeRegister("B1234567");
        c.setTaxId("1234567/A/M/000");
        when(clients.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(c)));
        ClientController controller = new ClientController(clients, audit);

        ClientResponse vue = controller.list(PageRequest.of(0, 20),
                new UsernamePasswordAuthenticationToken("x@y.tn", null,
                        List.of(new SimpleGrantedAuthority("ROLE_CONSULTATION"))))
                .getContent().get(0);

        assertEquals("B1234567", vue.tradeRegister(),
                "Identifiant juridique : nécessaire au contrat, pas confidentiel");
        assertEquals("1234567/A/M/000", vue.taxId());
        assertNull(vue.annualRevenue(), "Mais le CA reste fermé");
    }
}
