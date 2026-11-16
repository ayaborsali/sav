package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ClientDtos.ClientRequest;
import tn.higer.sav_backend.dto.ClientDtos.ClientResponse;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClientType;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.AuditService;

import java.time.LocalDate;
import java.util.UUID;

/**
 * CRUD des clients.
 * Lecture : tous les rôles connectés (règle générique GET /api/**).
 * Écriture : admin + gestionnaire_sav (règle générique /api/** — consultation bloquée).
 * La liste est paginée (Page standard Spring Data). Les écritures sont auditées.
 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientRepository clients;
    private final AuditService audit;

    public ClientController(ClientRepository clients, AuditService audit) {
        this.clients = clients;
        this.audit = audit;
    }

    @GetMapping
    public Page<ClientResponse> list(
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable,
            Authentication auth) {
        boolean commercial = Access.seesCommercialFigures(auth);
        return clients.findAll(pageable).map(c -> ClientResponse.from(c, commercial));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientResponse create(@Valid @RequestBody ClientRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        if (clients.existsByCodeIgnoreCase(request.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce code client existe déjà.");
        }
        Client c = new Client();
        apply(c, request);
        c.setId("cli-" + UUID.randomUUID());
        if (c.getCreatedAt() == null) {
            c.setCreatedAt(LocalDate.now());
        }
        clients.save(c);
        audit.record("CREATE", "CLIENT", c.getId(), "Création du client « " + c.getName() + " »", jwt);
        return ClientResponse.from(c);
    }

    @PutMapping("/{id}")
    public ClientResponse update(@PathVariable String id, @Valid @RequestBody ClientRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        Client c = clients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable."));
        if (clients.existsByCodeIgnoreCaseAndIdNot(request.code(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce code client existe déjà.");
        }
        apply(c, request);
        clients.save(c);
        audit.record("UPDATE", "CLIENT", id, "Mise à jour du client « " + c.getName() + " »", jwt);
        return ClientResponse.from(c);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Client c = clients.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client introuvable."));
        clients.deleteById(id);
        audit.record("DELETE", "CLIENT", id, "Suppression du client « " + c.getName() + " »", jwt);
    }

    private void apply(Client c, ClientRequest r) {
        c.setName(r.name().trim());
        c.setCode(r.code().trim());
        c.setType(r.type() != null ? r.type() : ClientType.PUBLIC_TRANSPORT);
        c.setContactPerson(r.contactPerson());
        c.setEmail(r.email());
        c.setPhone(r.phone());
        c.setAddress(r.address());
        c.setCity(r.city());
        c.setNotes(r.notes());
        c.setContactRole(r.contactRole());
        c.setCountry(r.country());
        c.setLastContactAt(r.lastContactAt());
        c.setSalesRep(r.salesRep());
        c.setCommercialStatus(r.commercialStatus());
        c.setProducts(r.products());
        c.setAnnualRevenue(r.annualRevenue());
        c.setPaymentTerms(r.paymentTerms());
        c.setTradeRegister(r.tradeRegister());
        c.setTaxId(r.taxId());
        if (r.createdAt() != null) {
            c.setCreatedAt(r.createdAt());
        }
    }
}
