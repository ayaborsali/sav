package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleRequest;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleResponse;
import tn.higer.sav_backend.dto.VehicleDtos.VehicleSummary;
import tn.higer.sav_backend.dto.VehicleDtos.VinDetectionResponse;
import tn.higer.sav_backend.model.BusType;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.model.VehicleStatus;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.VehicleMileageService;
import tn.higer.sav_backend.service.VinDetectionService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    // Durée de garantie constructeur (en mois) selon la motorisation, sinon une valeur
    // par défaut. Table volontairement simple et défendable. La gamme du bus (Confort /
    // Standard) ne change rien ici : elle décide du forfait d'entretien, pas de la
    // garantie usine.
    private static final Map<String, Integer> WARRANTY_MONTHS_BY_MOTORIZATION = Map.of(
            "diesel", 24,
            "cng", 36,
            "gas", 36,
            "hybride", 48,
            "hybrid", 48,
            "electrique", 60,
            "electric", 60);

    private static final int DEFAULT_WARRANTY_MONTHS = 24;
    
    private final VehicleRepository vehicles;
    private final ContractRepository contracts;
    private final AuditService audit;
    private final VinDetectionService vinDetection;
    private final VehicleMileageService compteur;

    public VehicleController(VehicleRepository vehicles, ContractRepository contracts, AuditService audit,
                             VinDetectionService vinDetection, VehicleMileageService compteur) {
        this.vehicles = vehicles;
        this.contracts = contracts;
        this.audit = audit;
        this.vinDetection = vinDetection;
        this.compteur = compteur;
    }

    /** Liste paginée allégée : sans l'image de plaque (base64), sans les prix en consultation. */
    @GetMapping
    public Page<VehicleSummary> list(
            @PageableDefault(size = 20, sort = "vin", direction = Sort.Direction.ASC) Pageable pageable,
            Authentication auth) {
        boolean priced = Access.seesCommercialFigures(auth);
        return vehicles.findAll(pageable).map(v -> VehicleSummary.from(v, priced));
    }

    /** Détail complet d'un véhicule, image de plaque incluse. */
    @GetMapping("/{id}")
    public VehicleResponse get(@PathVariable String id, Authentication auth) {
        Vehicle v = vehicles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Véhicule introuvable."));
        return VehicleResponse.from(v, Access.seesCommercialFigures(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse create(@Valid @RequestBody VehicleRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        if (vehicles.existsByVinIgnoreCase(request.vin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce numéro de châssis (VIN) existe déjà.");
        }
        Vehicle v = new Vehicle();
        // L'identifiant est posé avant l'application : la porte du compteur demande ce
        // que le portail sait déjà du bus, et une fiche toute neuve n'a rien à défendre.
        v.setId("veh-" + UUID.randomUUID());
        apply(v, request, jwt);
        vehicles.save(v);
        audit.record("CREATE", "VEHICLE", v.getId(), "Création du véhicule " + v.getVin(), jwt);
        return VehicleResponse.from(v);
    }

    @PutMapping("/{id}")
    public VehicleResponse update(@PathVariable String id, @Valid @RequestBody VehicleRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        Vehicle v = vehicles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Véhicule introuvable."));
        if (vehicles.existsByVinIgnoreCaseAndIdNot(request.vin(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce numéro de châssis (VIN) existe déjà.");
        }
        apply(v, request, jwt);
        vehicles.save(v);
        audit.record("UPDATE", "VEHICLE", id, "Mise à jour du véhicule " + v.getVin(), jwt);
        return VehicleResponse.from(v);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Vehicle v = vehicles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Véhicule introuvable."));
        vehicles.deleteById(id);
        audit.record("DELETE", "VEHICLE", id, "Suppression du véhicule " + v.getVin(), jwt);
    }

    /**
     * Lecture du numéro de châssis (VIN) sur une photo : le modèle vision propose des caractères
     * via scripts/detect_vin.py, mais c'est ce serveur qui décide s'ils sont recevables. Une lecture
     * partielle ou illisible n'est pas une erreur HTTP : elle revient avec son message, et rien
     * n'est pré-rempli dans le formulaire.
     */
    @PostMapping("/detect-vin")
    public VinDetectionResponse detectVin(@RequestParam("image") MultipartFile file,
                                                      @AuthenticationPrincipal Jwt jwt) throws IOException {
        VinDetectionService.Outcome outcome = vinDetection.detect(
                jwt == null ? "anon" : jwt.getSubject(), file.getBytes());
        Vehicle known = outcome.complete()
                ? vehicles.findByVinIgnoreCase(outcome.vin()).orElse(null)
                : null;
        return new VinDetectionResponse(outcome.status(), outcome.vin(),
                outcome.partial(), outcome.readChars(), known != null,
                known == null ? null : known.getId(),
                known == null ? null : known.getRegistration(),
                detectionMessage(outcome, known));
    }

    /** Le motif écrit à l'écran : l'utilisateur doit comprendre quoi reprendre dans sa photo. */
    private static String detectionMessage(VinDetectionService.Outcome outcome, Vehicle known) {
        if (outcome.complete()) {
            if (known != null) {
                return "Châssis lu : " + outcome.vin() + ". Ce véhicule existe déjà "
                        + "(immatriculation " + known.getRegistration() + ") : vérifie avant d'enregistrer.";
            }
            return "Châssis lu : " + outcome.vin() + ". Relis-le sur la plaque avant d'enregistrer.";
        }
        if ("partial".equals(outcome.status())) {
            return "Lecture incomplète : " + outcome.readChars() + " caractères sur 17 ("
                    + outcome.partial() + "). Reprends la photo de plus près, lumière diffuse, "
                    + "sans reflet. Complète le champ manuellement.";
        }
        return "Aucun châssis reconnaissable sur cette photo. Rapproche-toi de la plaque, "
                + "sans reflet, ou saisis le VIN manuellement.";
    }

    private void apply(Vehicle v, VehicleRequest r, Jwt jwt) {
        v.setVin(r.vin().trim().toUpperCase());
        v.setRegistration(r.registration().trim());
        v.setModel(typeDeBus(r.model()));
        v.setYear(r.year());
        v.setClientId(r.clientId());
        // Le compteur ne se copie pas : il passe par la porte unique, qui refuse une
        // valeur plus basse que le dernier relevé du technicien tant qu'aucun motif
        // n'est écrit (voir VehicleMileageService).
        compteur.saisieAtelier(v, r.mileage(), r.mileageMotif(), jwt);
        v.setCommissioningDate(r.commissioningDate());
        // Règle garantie (autorité serveur) : la date de fin de garantie n'est jamais
        // aveuglément copiée depuis la requête.
        //   - absente                    : valeur par défaut = mise en service + durée
        //     (table motorisation ci-dessus, 24 mois par défaut).
        //   - antérieure à la mise en service : rejet 400.
        //   - sinon cohérente             : on la conserve.
        LocalDate commissioning = r.commissioningDate();
        LocalDate requestedWarranty = r.warrantyEndDate();
        if (requestedWarranty == null) {
            LocalDate base = commissioning != null ? commissioning : LocalDate.now();
            v.setWarrantyEndDate(base.plusMonths(warrantyMonths(r)));
        } else if (commissioning != null && requestedWarranty.isBefore(commissioning)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La date de fin de garantie ne peut pas être antérieure à la date de mise en service.");
        } else {
            v.setWarrantyEndDate(requestedWarranty);
        }
        v.setMotorization(r.motorization());
        v.setStatus(r.status() != null ? r.status() : VehicleStatus.ACTIVE);
        v.setNotes(r.notes());
        // Rattachement au contrat : le contrat doit exister, et un véhicule sous contrat
        // doit avoir un prix unitaire HT. Cette règle tient des deux côtés (voir aussi
        // ContractController) : elle garantit qu'une ligne de contrat ne contribue jamais
        // 0 DT à un total silencieusement faux.
        String contractId = isBlank(r.contractId()) ? null : r.contractId().trim();
        BigDecimal unitPrice = r.unitPriceHt();
        if (contractId != null) {
            Contract contract = contracts.findById(contractId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Contrat introuvable : " + contractId));
            if (!contract.getClientId().equals(r.clientId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Le contrat " + contract.getNumber() + " est au nom d'un autre client : "
                                + "rattachez ce véhicule à son propre contrat.");
            }
            if (unitPrice == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Un véhicule rattaché à un contrat doit avoir un prix unitaire HT.");
            }
        }
        v.setContractId(contractId);
        v.setUnitPriceHt(unitPrice);
        v.setPlateImage(r.plateImage());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * La gamme du bus : Confort ou Standard, rien d'autre. Le modèle commercial
     * (KLQ6129G, Azure…) ne détermine aucun forfait d'entretien, il ne peut donc pas
     * rester la valeur de ce champ. Une écriture floue est refusée sur place, en
     * français, parce qu'un bus sans gamme connue ne peut pas être vendu en forfait.
     */
    private static String typeDeBus(String raw) {
        if (isBlank(raw)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bus doit porter une gamme : Confort ou Standard. "
                            + "C'est elle qui décide du forfait d'entretien, pas le modèle commercial.");
        }
        String label = BusType.labelOf(raw);
        if (label == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + raw.trim() + " » n'est pas une gamme d'autobus : choisis Confort ou Standard. "
                            + "Le châssis et l'immatriculation disent déjà quel bus c'est.");
        }
        return label;
    }

    private static int warrantyMonths(VehicleRequest r) {
        if (r.motorization() != null) {
            Integer months = WARRANTY_MONTHS_BY_MOTORIZATION.get(r.motorization().trim().toLowerCase(Locale.ROOT));
            if (months != null) {
                return months;
            }
        }
        return DEFAULT_WARRANTY_MONTHS;
    }
}
