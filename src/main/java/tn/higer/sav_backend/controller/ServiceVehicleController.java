package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ServiceVehicleDtos.ServiceVehicleRequest;
import tn.higer.sav_backend.dto.ServiceVehicleDtos.ServiceVehicleResponse;
import tn.higer.sav_backend.dto.ServiceVehicleDtos.ServiceVehicleStatusRequest;
import tn.higer.sav_backend.model.InterventionStatus;
import tn.higer.sav_backend.model.ServiceVehicle;
import tn.higer.sav_backend.model.ServiceVehicleStatus;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.ServiceVehicleRepository;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.AuditService;

import java.util.List;
import java.util.UUID;

/**
 * Flotte de service. Le statut « en intervention » n'est pas une saisie : il est
 * posé et rendu par l'affectation et le retour d'une sortie. Ce que l'humain
 * décide ici n'est que la fiche du véhicule et, le cas échéant, sa mise à
 * l'atelier ou sa panne.
 */
@RestController
@RequestMapping("/api/service-vehicles")
public class ServiceVehicleController {

    private final ServiceVehicleRepository vehicles;
    private final InterventionRepository interventions;
    private final AuditService audit;

    public ServiceVehicleController(ServiceVehicleRepository vehicles,
                                    InterventionRepository interventions, AuditService audit) {
        this.vehicles = vehicles;
        this.interventions = interventions;
        this.audit = audit;
    }

    @GetMapping
    public List<ServiceVehicleResponse> list(Authentication auth) {
        requireMember(auth);
        return vehicles.findAllByOrderByActiveDescRegistrationAsc().stream()
                .map(ServiceVehicleResponse::from)
                .toList();
    }

    /** Seulement ce qui peut être affecté : c'est cette liste qui empêche d'engager la même voiture deux fois. */
    @GetMapping("/available")
    public List<ServiceVehicleResponse> available(Authentication auth) {
        requireMember(auth);
        return vehicles.findByActiveTrueAndStatusInOrderByRegistrationAsc(
                        List.of(ServiceVehicleStatus.AVAILABLE)).stream()
                .map(ServiceVehicleResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceVehicleResponse create(@Valid @RequestBody ServiceVehicleRequest request,
                                         @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        if (vehicles.existsByRegistrationIgnoreCase(request.registration())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce numéro d'immatriculation est déjà au tableau de flotte.");
        }
        ServiceVehicle v = new ServiceVehicle();
        v.setId("sv-" + UUID.randomUUID());
        apply(v, request);
        vehicles.save(v);
        audit.record("CREATE", "SERVICE_VEHICLE", v.getId(),
                "Ajout de la voiture de déplacement " + v.getRegistration(), jwt);
        return ServiceVehicleResponse.from(v);
    }

    @PutMapping("/{id}")
    public ServiceVehicleResponse update(@PathVariable String id, @Valid @RequestBody ServiceVehicleRequest request,
                                         @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        ServiceVehicle v = find(id);
        if (vehicles.existsByRegistrationIgnoreCaseAndIdNot(request.registration(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ce numéro d'immatriculation est déjà au tableau de flotte.");
        }
        apply(v, request);
        vehicles.save(v);
        audit.record("UPDATE", "SERVICE_VEHICLE", id,
                "Mise à jour de la voiture de déplacement " + v.getRegistration(), jwt);
        return ServiceVehicleResponse.from(v);
    }

    /**
     * Mise à l'atelier ou sortie de panne. DISPATCHED est refusé ici : une voiture
     * n'est pas engagé parce que quelqu'un l'écrit, il l'est parce qu'une sortie
     * l'a affecté.
     */
    @PostMapping("/{id}/status")
    public ServiceVehicleResponse setStatus(@PathVariable String id,
                                            @Valid @RequestBody ServiceVehicleStatusRequest request,
                                            @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        ServiceVehicle v = find(id);
        ServiceVehicleStatus target = parse(request.status());
        if (target == ServiceVehicleStatus.DISPATCHED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Une voiture de déplacement n'est pas mise « en intervention » à la main : "
                            + "affectez-le à une sortie.");
        }
        if (v.getStatus() == ServiceVehicleStatus.DISPATCHED
                && interventions.existsByServiceVehicleIdAndStatusIn(v.getId(), InterventionStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le véhicule « " + v.getRegistration() + " » est encore dehors : "
                            + "enregistrez son retour avant de changer son statut.");
        }
        v.setStatus(target);
        vehicles.save(v);
        audit.record("UPDATE", "SERVICE_VEHICLE", id,
                "Véhicule " + v.getRegistration() + " : statut " + target.name().toLowerCase(), jwt);
        return ServiceVehicleResponse.from(v);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        requireManager(auth);
        ServiceVehicle v = find(id);
        if (interventions.existsByServiceVehicleIdAndStatusIn(v.getId(), InterventionStatus.OPEN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le véhicule « " + v.getRegistration() + " » est engagé sur une sortie en cours.");
        }
        v.setActive(false);
        vehicles.save(v);
        audit.record("DELETE", "SERVICE_VEHICLE", id,
                "Véhicule " + v.getRegistration() + " retiré de la flotte (conservé pour l'historique)", jwt);
    }

    private void apply(ServiceVehicle v, ServiceVehicleRequest r) {
        v.setRegistration(r.registration().trim().toUpperCase());
        v.setLabel(r.label().trim());
        v.setKind(r.kind().trim());
        v.setCurrentMileage(r.currentMileage());
        v.setNotes(r.notes());
        if (r.active() != null) {
            v.setActive(r.active());
        }
    }

    private ServiceVehicle find(String id) {
        return vehicles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Voiture de déplacement introuvable."));
    }

    private static ServiceVehicleStatus parse(String value) {
        try {
            return ServiceVehicleStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Statut de véhicule inconnu : « " + value + " ».");
        }
    }

    private void requireMember(Authentication auth) {
        if (!Access.usesDepannageModule(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Le module dépannage est interne : votre compte n'y a pas accès.");
        }
    }

    private void requireManager(Authentication auth) {
        requireMember(auth);
        if (!Access.managesInterventions(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seuls le chef d'atelier et l'administrateur gèrent la flotte de service.");
        }
    }
}
