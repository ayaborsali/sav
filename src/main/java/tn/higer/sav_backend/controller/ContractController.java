package tn.higer.sav_backend.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ContractDtos;
import tn.higer.sav_backend.dto.ContractDtos.ContractLineRequest;
import tn.higer.sav_backend.dto.ContractDtos.ContractRequest;
import tn.higer.sav_backend.dto.ContractDtos.ContractResponse;
import tn.higer.sav_backend.dto.ContractDtos.ContractSummary;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Contract;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.ContractRepository;
import tn.higer.sav_backend.repository.VehicleRepository;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.ContractDocumentService;
import tn.higer.sav_backend.service.ContractPdfService;
import tn.higer.sav_backend.service.GroqDraftService;
import tn.higer.sav_backend.service.NumberSequence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD des contrats de maintenance.
 * Lecture : tous les rôles connectés, mais les montants et les conditions de paiement
 * sont masqués au rôle consultation (Access.seesCommercialFigures).
 * Écriture : admin + gestionnaire_sav (règle générique /api/**).
 * Le numéro est généré ici, les totaux sont recalculés à chaque lecture (ContractDtos).
 */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    /** Format du numéro : CT-HIG-<année>-<compteur sur 3 chiffres>. */
    private static final String PREFIX = "CT-HIG-";
    private static final int SEQUENCE_WIDTH = 3;

    @Value("${app.tva.rate:19}")
    private double tvaRateConfig;

    private final ContractRepository contracts;
    private final VehicleRepository vehicles;
    private final ClientRepository clients;
    private final AuditService audit;
    private final ContractDocumentService documentService;
    private final ContractPdfService pdfService;
    private final GroqDraftService draftService;

    public ContractController(ContractRepository contracts, VehicleRepository vehicles,
                              ClientRepository clients, AuditService audit,
                              ContractDocumentService documentService, ContractPdfService pdfService,
                              GroqDraftService draftService) {
        this.contracts = contracts;
        this.vehicles = vehicles;
        this.clients = clients;
        this.audit = audit;
        this.documentService = documentService;
        this.pdfService = pdfService;
        this.draftService = draftService;
    }

    @GetMapping
    public Page<ContractSummary> list(
            @PageableDefault(size = 20, sort = "contractDate", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth) {
        Page<Contract> page = contracts.findAll(pageable);
        Map<String, List<Vehicle>> linesByContract = groupByContract(
                vehicles.findByContractIdIn(page.getContent().stream().map(Contract::getId).toList()));
        boolean figures = Access.seesCommercialFigures(auth);
        BigDecimal rate = tvaRate();
        return page.map(c -> ContractDtos.toSummary(c,
                linesByContract.getOrDefault(c.getId(), List.of()), figures, rate));
    }

    @GetMapping("/{id}")
    public ContractResponse get(@PathVariable String id, Authentication auth) {
        Contract c = require(id);
        return ContractDtos.toResponse(c, vehicles.findByContractId(id),
                Access.seesCommercialFigures(auth), tvaRate());
    }

    /**
     * Contrat au format PDF, prêt à être enregistré et signé. Le document est reconstruit
     * intégralement depuis la base à chaque demande (numéro, parties, clauses, annexe, totaux,
     * somme en toutes lettres) : il ne peut pas être désynchronisé de la base. La mise en page
     * est déléguée à un script Python (voir ContractPdfService).
     * Le rôle consultation reçoit le même document mais sans aucun montant (figures=false).
     */
    @GetMapping("/{id}/document.pdf")
    public ResponseEntity<byte[]> documentPdf(@PathVariable String id, Authentication auth) {
        Contract c = require(id);
        List<Vehicle> lines = vehicles.findByContractId(id);
        Client client = clients.findById(c.getClientId()).orElse(null);
        Map<String, Object> payload = documentService.payload(c, client, lines,
                Access.seesCommercialFigures(auth));
        byte[] pdf = pdfService.render(payload);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(c.getNumber() + ".pdf", StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    /**
     * L'IA est-elle branchée ? Le frontend grise le bouton « Proposer une rédaction » si
     * available=false (clé absente), sans appeler le modèle.
     */
    @GetMapping("/ai-status")
    public Map<String, Object> aiStatus() {
        return Map.of("available", draftService.available(), "model", draftService.model());
    }

    /**
     * Propose le texte d'une clause via Groq (qwen3-32b). Le frontend affiche la proposition
     * dans un encart Utiliser / Modifier / Ignorer : rien n'est enregistré ici. Écriture réservée
     * à admin + gestionnaire_sav (règle générique /api/**), et auditée.
     */
    @PostMapping("/{id}/draft")
    public ContractDtos.DraftResponse draft(@PathVariable String id,
                                            @Valid @RequestBody ContractDtos.DraftRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        Contract c = require(id);
        Client client = clients.findById(c.getClientId()).orElse(null);
        String instruction = INSTRUCTIONS.get(request.section());
        if (instruction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Section de clause inconnue : " + request.section());
        }
        String facts = buildFacts(client, vehicles.findByContractId(id));
        String text = draftService.draft(jwt != null ? jwt.getSubject() : null, instruction, facts);
        audit.record("AI_DRAFT", "CONTRACT", id,
                "Rédaction IA (« " + request.section() + " ») via " + draftService.model(), jwt);
        return new ContractDtos.DraftResponse(request.section(), text, draftService.model());
    }

    /**
     * Propose le texte d'une clause pour un contrat pas encore enregistré. Le navigateur n'envoie
     * que des identifiants (client, bus cochés) : le serveur relit lui-même le nom du client et
     * les gammes, et ne transmet toujours aucun montant. Rien n'est enregistré ici non plus —
     * la proposition s'affiche, un humain la relit et l'utilise ou non.
     */
    @PostMapping("/draft-preview")
    public ContractDtos.DraftResponse draftPreview(
            @Valid @RequestBody ContractDtos.DraftPreviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String instruction = INSTRUCTIONS.get(request.section());
        if (instruction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Section de clause inconnue : " + request.section());
        }
        Client client = clients.findById(request.clientId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Client introuvable : " + request.clientId()));
        String facts = buildFacts(client, retainedLines(request.vehicleIds(), client.getId()));
        String text = draftService.draft(jwt != null ? jwt.getSubject() : null, instruction, facts);
        audit.record("AI_DRAFT", "CONTRACT", null,
                "Rédaction IA (« " + request.section() + " ») avant enregistrement via "
                        + draftService.model(), jwt);
        return new ContractDtos.DraftResponse(request.section(), text, draftService.model());
    }

    /**
     * Les bus cochés qui appartiennent vraiment au client. Un identifiant d'un autre portefeuille
     * est écarté : le modèle ne doit pas apprendre un bus qui n'est pas celui du contrat.
     */
    private List<Vehicle> retainedLines(List<String> vehicleIds, String clientId) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = vehicleIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        return vehicles.findAllById(ids).stream()
                .filter(v -> clientId.equals(v.getClientId()))
                .toList();
    }

    /** Instruction par section — le texte exact envoyé au modèle. Aucune ne demande de chiffrer. */
    private static final Map<String, String> INSTRUCTIONS = Map.of(
            "object", "la clause « Objet » : la maintenance préventive et corrective des véhicules "
                    + "désignés en annexe, confiés par le client au prestataire",
            "delivery", "la clause « Prestations et délais d'intervention » : visites périodiques, "
                    + "diagnostic et réparations, remise du véhicule au client dans le délai standard",
            "warranty", "la clause « Garantie des travaux » : garantie de la main-d'oeuvre et des "
                    + "pièces remplacées selon le carnet d'entretien, pièces d'usure exclues",
            "ownership", "la clause « Pièces déposées et propriété » : le client reste propriétaire "
                    + "du véhicule, les pièces remplacées lui sont restituées sur demande écrite",
            "dispute", "la clause « Litiges » : accord amiable d'abord, puis juridiction compétente",
            "special", "des clauses particulières neutres et standard pour un contrat de maintenance "
                    + "de bus et véhicules industriels");

    /**
     * Résumé du contrat envoyé au modèle : identité et consistance, JAMAIS de montant, de VIN
     * ni de total. C'est ce qui empêche l'IA de « voir » l'argent et de le recopier de travers.
     */
    static String buildFacts(Client client, List<Vehicle> lines) {
        StringBuilder gammes = new StringBuilder();
        lines.stream()
                .map(Vehicle::getModel)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .forEach(m -> gammes.append(gammes.length() > 0 ? ", " : "").append(m));
        return "Client (celui qui confie ses véhicules au prestataire) : "
                + (client != null ? client.getName() : "—")
                + (client != null && client.getCity() != null ? ", ville " + client.getCity() : "")
                + (client != null && client.getCountry() != null ? ", pays " + client.getCountry() : "")
                + (lines.isEmpty()
                        ? " — véhicules pas encore désignés, contrat en préparation"
                        : " — nombre de véhicules : " + lines.size()
                                + (gammes.length() > 0 ? " — gammes : " + gammes : ""));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContractResponse create(@Valid @RequestBody ContractRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        requireClient(request.clientId());
        Contract c = new Contract();
        c.setId("ctr-" + UUID.randomUUID());
        c.setNumber(nextNumber());
        applyHeader(c, request);
        contracts.save(c);
        int lines = attachLines(c, request.lines());
        audit.record("CREATE", "CONTRACT", c.getId(),
                "Création du contrat " + c.getNumber() + " (" + lines + " véhicule(s))", jwt);
        return ContractDtos.toResponse(c, vehicles.findByContractId(c.getId()), true, tvaRate());
    }

    @PutMapping("/{id}")
    @Transactional
    public ContractResponse update(@PathVariable String id, @Valid @RequestBody ContractRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        Contract c = require(id);
        requireClient(request.clientId());
        applyHeader(c, request);
        contracts.save(c);
        if (request.lines() != null) {
            attachLines(c, request.lines());
        }
        audit.record("UPDATE", "CONTRACT", id, "Mise à jour du contrat " + c.getNumber(), jwt);
        return ContractDtos.toResponse(c, vehicles.findByContractId(id), true, tvaRate());
    }

    /** Suppression : les véhicules détachés survivent, ils redeviennent « hors contrat ». */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Contract c = require(id);
        List<Vehicle> lines = vehicles.findByContractId(id);
        for (Vehicle v : lines) {
            v.setContractId(null);
            v.setUnitPriceHt(null);
        }
        vehicles.saveAll(lines);
        contracts.deleteById(id);
        audit.record("DELETE", "CONTRACT", id,
                "Suppression du contrat " + c.getNumber() + " (" + lines.size() + " véhicule(s) détaché(s))", jwt);
    }

    private Contract require(String id) {
        return contracts.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrat introuvable."));
    }

    private void requireClient(String clientId) {
        if (!clients.existsById(clientId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client introuvable : " + clientId);
        }
    }

    private void applyHeader(Contract c, ContractRequest r) {
        c.setContractDate(r.contractDate());
        c.setClientId(r.clientId().trim());
        c.setPaymentTerms(r.paymentTerms());
        c.setDeliveryAddress(r.deliveryAddress());
        c.setNotes(r.notes());
        // Clauses (V5) : une zone vide (blank) redevient null => rédaction standard rétablie.
        c.setObjectClause(blankToNull(r.objectClause()));
        c.setDeliveryClause(blankToNull(r.deliveryClause()));
        c.setWarrantyClause(blankToNull(r.warrantyClause()));
        c.setOwnershipClause(blankToNull(r.ownershipClause()));
        c.setDisputeClause(blankToNull(r.disputeClause()));
        c.setSpecialTerms(blankToNull(r.specialTerms()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Remplace le contenu du contrat par la liste envoyée. Un véhicule ajouté doit
     * exister, appartenir au client du contrat, et repartir avec un prix unitaire HT —
     * sinon le total du contrat serait faux dès la première lecture.
     */
    private int attachLines(Contract c, List<ContractLineRequest> requested) {
        Set<String> keep = new HashSet<>();
        if (requested != null) {
            for (ContractLineRequest line : requested) {
                Vehicle v = vehicles.findById(line.vehicleId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Véhicule introuvable : " + line.vehicleId()));
                if (!c.getClientId().equals(v.getClientId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le véhicule " + v.getVin() + " n'appartient pas au client « " + c.getClientId()
                                    + " » ; corrigez le client du véhicule avant de l'ajouter au contrat.");
                }
                if (line.unitPriceHt() != null) {
                    v.setUnitPriceHt(line.unitPriceHt());
                }
                if (v.getUnitPriceHt() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Le véhicule " + v.getVin() + " n'a pas de prix unitaire HT : "
                                    + "renseignez-le sur la fiche véhicule ou sur la ligne du contrat.");
                }
                v.setContractId(c.getId());
                vehicles.save(v);
                keep.add(v.getId());
            }
        }
        for (Vehicle v : vehicles.findByContractId(c.getId())) {
            if (!keep.contains(v.getId())) {
                v.setContractId(null);
                vehicles.save(v);
            }
        }
        return requested != null ? requested.size() : vehicles.findByContractId(c.getId()).size();
    }

    /** CT-HIG-2026-014 : compteur repris sur le plus haut numéro de l'année, jamais saisi à la main. */
    private String nextNumber() {
        String prefix = PREFIX + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(prefix, contracts.findNumbersByPrefix(prefix), SEQUENCE_WIDTH);
    }

    private static Map<String, List<Vehicle>> groupByContract(List<Vehicle> lines) {
        return lines.stream().collect(Collectors.groupingBy(Vehicle::getContractId));
    }

    /** Taux TVA normalisé en fraction (0.19 pour 19 %), lu défensivement comme en maintenance. */
    private BigDecimal tvaRate() {
        double v = tvaRateConfig;
        if (v < 0) {
            v = 19;
        }
        if (v > 1) {
            v = v / 100.0;
        }
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
