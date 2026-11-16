package tn.higer.sav_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.FactureDtos.AFacturerDto;
import tn.higer.sav_backend.dto.FactureDtos.EncaissementRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureEmissionRequest;
import tn.higer.sav_backend.dto.FactureDtos.FactureResponse;
import tn.higer.sav_backend.dto.FactureDtos.FactureSummary;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Encaissement;
import tn.higer.sav_backend.model.Facture;
import tn.higer.sav_backend.model.FactureLine;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.EncaissementRepository;
import tn.higer.sav_backend.repository.FactureRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * L'argent qui entre : la facture née du bon de livraison signé, et ce qui reste
 * dehors (I01, I02).
 *
 * Le portail chiffrait le travail sans jamais savoir si l'argent était venu. Ce
 * service ferme la chaîne : le devis accepté porte le montant, l'ordre exécute, le
 * bon de livraison rend le bus et se signe ({@code BonLivraisonService}), et c'est
 * cette signature qui ouvre la facture. Trois portes, une seule main :
 *
 * 1. <b>on ne facture qu'un BL signé, et qu'une fois.</b> Le bon doit être « Signé »
 *    (le client a repris le bus), et un même BL ne donne qu'une facture — deux
 *    factures pour un bus rendu, c'est un client qui paie deux fois.
 * 2. <b>le montant vient du devis accepté en amont, recopié.</b> La facture reprend
 *    les lignes du devis (BL → ordre → devis), pas un chiffre tapé : ce qui est
 *    couvert par le contrat n'a pas été devisé, et une pièce fournie par le client
 *    est déjà à 0. Les totaux se recalculent serveur, TVA comme partout ; un devis
 *    qui change de prix après coup ne réécrit pas la facture émise.
 * 3. <b>l'encaissement est un geste du guichet, daté du jour.</b> Un versement est
 *    borné à ce qui reste dû, sa date est le jour du clic, et celui qui l'écrit est
 *    lu dans le jeton. L'état (émise / partielle / payée / impayée) ne se tape pas :
 *    il se calcule ({@link FactureEtats}) à chaque lecture.
 *
 * L'écriture est réservée au guichet (ADMIN, GESTIONNAIRE_SAV) par la règle générique
 * de {@code SecurityConfig} sur POST /api/** — aucune règle nouvelle ici, et c'est
 * déjà lui qui voit les chiffres commerciaux. La lecture masque les montants pour
 * les autres rôles, côté serveur, comme le reste du portail.
 */
@Service
public class FactureService {

    /** L'échéance par défaut quand le guichet n'en écrit pas : un mois après l'émission. */
    private static final int JOURS_ECHEANCE_DEFAUT = 30;

    private final MaintenanceRepository documents;
    private final FactureRepository factures;
    private final EncaissementRepository encaissements;
    private final ClientRepository clients;
    private final VehicleRepository vehicles;
    private final AuditService audit;
    private final double tvaRate;

    public FactureService(MaintenanceRepository documents, FactureRepository factures,
                          EncaissementRepository encaissements, ClientRepository clients,
                          VehicleRepository vehicles, AuditService audit,
                          @Value("${app.tva.rate:19}") double tvaRate) {
        this.documents = documents;
        this.factures = factures;
        this.encaissements = encaissements;
        this.clients = clients;
        this.vehicles = vehicles;
        this.audit = audit;
        this.tvaRate = tvaRate;
    }

    // ------------------------------------------------------------------------- la lecture

    /** Les factures, de la plus récente à la plus ancienne, avec l'état du jour. */
    @Transactional(readOnly = true)
    public List<FactureSummary> list(boolean pricesVisible) {
        return factures.findAllByOrderByIssuedOnDescNumberDesc().stream()
                .map(f -> FactureSummary.of(f, numeroBl(f.getBlId()), nomClient(f.getClientId()),
                        registrationBus(f.getVehicleId()),
                        FactureEtats.sommeEncaissee(encaissements.findByFactureIdOrderByPaidOnAsc(f.getId())),
                        pricesVisible))
                .toList();
    }

    @Transactional(readOnly = true)
    public FactureResponse get(String id, boolean pricesVisible) {
        return reponse(factureDe(id), pricesVisible);
    }

    /**
     * La relance (I02) : les bons de livraison signés qu'aucune facture n'a encore
     * suivis. Le bus est rendu, le travail est fait, et l'argent n'est pas réclamé.
     * Les plus anciennement signés d'abord : c'est la créance qui dort depuis le plus
     * longtemps qu'il faut réveiller en premier.
     */
    @Transactional(readOnly = true)
    public List<AFacturerDto> aFacturer(boolean pricesVisible) {
        Set<String> dejaFactures = new HashSet<>(factures.findAllBlIds());
        return documents.findAll().stream()
                .filter(p -> p.getType() == MaintenanceType.BL && p.getStatus() == MaintenanceStatus.SIGNED)
                .filter(p -> !dejaFactures.contains(p.getId()))
                .sorted(Comparator.comparing(p -> p.getSignedOn() == null ? LocalDate.MAX : p.getSignedOn()))
                .map(bl -> {
                    MaintenanceItem devis = devisEnAmont(bl).orElse(null);
                    return AFacturerDto.of(bl, nomClient(bl.getClientId()), registrationBus(bl.getVehicleId()),
                            devis == null ? null : devis.getNumber(),
                            devis == null ? null : devis.getTotalTTC(), pricesVisible);
                })
                .toList();
    }

    // ------------------------------------------------------------------------- l'émission

    /**
     * Le clic qui transforme un BL signé en facture. Le montant n'est pas tapé : il
     * est recopié du devis accepté en amont, ligne à ligne, et les totaux se
     * recalculent serveur. L'échéance est celle convenue, sinon émission + 30 jours.
     */
    @Transactional
    public FactureResponse emettre(FactureEmissionRequest request, Jwt jwt, boolean pricesVisible) {
        String blId = request == null ? null : request.blId();
        if (blId == null || blId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dites quel bon de livraison facturer : la facture naît du papier que le client a signé"
                            + " en reprenant le bus, pas d'une page blanche.");
        }
        MaintenanceItem bl = documents.findById(blId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Bon de livraison introuvable : il ne peut pas donner de facture."));
        if (bl.getType() != MaintenanceType.BL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + bl.getNumber() + " » n'est pas un bon de livraison ("
                            + (bl.getType() == null ? "type inconnu" : bl.getType().label())
                            + ") : on ne facture que le papier qui rend le bus au client.");
        }
        if (bl.getStatus() != MaintenanceStatus.SIGNED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bon de livraison « " + bl.getNumber() + " » n'est pas signé (état « "
                            + (bl.getStatus() == null ? "sans état" : bl.getStatus().label(MaintenanceType.BL))
                            + " ») : le client n'a pas encore repris le bus. Marquez-le signé — le nom et le scan"
                            + " sont la preuve de la restitution — puis facturez.");
        }
        if (factures.existsByBlId(bl.getId())) {
            Facture existante = factures.findByBlId(bl.getId()).orElseThrow();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le bon de livraison « " + bl.getNumber() + " » a déjà donné la facture « "
                            + existante.getNumber() + " » : un même bus rendu ne se facture pas deux fois."
                            + " Relisez la facture existante, ou encaissez ce qui reste dû.");
        }
        MaintenanceItem devis = devisEnAmont(bl).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Le bon de livraison « " + bl.getNumber() + " » n'a pas de devis en amont : aucun montant"
                        + " accepté par le client à facturer. La facture reprend le devis que le client a"
                        + " signé, elle n'invente pas un prix — ouvrez le devis accepté dont ce travail sort."));
        if (devis.getStatus() != MaintenanceStatus.VALIDATED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » en amont n'est pas accepté par le client (état « "
                            + (devis.getStatus() == null ? "sans état" : devis.getStatus().label())
                            + " ») : on ne facture pas un prix que le client n'a pas signé.");
        }

        Facture facture = new Facture();
        facture.setId("fac-" + UUID.randomUUID());
        facture.setNumber(prochainNumero());
        facture.setBlId(bl.getId());
        facture.setDevisId(devis.getId());
        facture.setClientId(bl.getClientId());
        facture.setVehicleId(bl.getVehicleId());
        facture.setIssuedOn(LocalDate.now());
        LocalDate echeance = request.dueOn() == null
                ? facture.getIssuedOn().plusDays(JOURS_ECHEANCE_DEFAUT) : request.dueOn();
        if (echeance.isBefore(facture.getIssuedOn())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'échéance du " + echeance + " est avant l'émission de la facture « " + facture.getNumber()
                            + " » : une facture ne naît pas déjà en retard. Choisissez une échéance à partir"
                            + " d'aujourd'hui, ou laissez le délai par défaut (" + JOURS_ECHEANCE_DEFAUT + " jours).");
        }
        facture.setDueOn(echeance);
        facture.setCreatedBy(jwt == null ? null : jwt.getClaimAsString("email"));

        double totalHT = 0.0;
        for (MaintenanceDetail ligne : devis.getDetails()) {
            FactureLine copie = new FactureLine();
            copie.setReference(ligne.getReference());
            copie.setLabel(ligne.getLabel());
            copie.setQuantity(ligne.getQuantity());
            copie.setUnitPrice(ligne.getUnitPrice());
            copie.setNature(ligne.getNature());
            copie.setSuppliedBy(ligne.getSuppliedBy());
            double totalLigne = MaintenanceTotals.round2(nz(ligne.getQuantity()) * nz(ligne.getUnitPrice()));
            copie.setLineTotal(totalLigne);
            totalHT += totalLigne;
            facture.getLines().add(copie);
        }
        totalHT = MaintenanceTotals.round2(totalHT);
        double totalTTC = MaintenanceTotals.round2(totalHT * (1 + MaintenanceTotals.tvaFraction(tvaRate)));
        facture.setTotalHT(totalHT);
        facture.setTotalTTC(totalTTC);
        facture.setTotalTVA(MaintenanceTotals.round2(totalTTC - totalHT));

        factures.save(facture);
        audit.record("CREATE", "FACTURE", facture.getId(),
                "Facture « " + facture.getNumber() + " » émise depuis le bon de livraison « " + bl.getNumber()
                        + " » signé — " + String.format(java.util.Locale.ROOT, "%.2f", totalTTC) + " TTC (devis « "
                        + devis.getNumber() + " »), échéance le " + echeance, jwt);
        return reponse(facture, pricesVisible);
    }

    // ------------------------------------------------------------------------- l'encaissement

    /**
     * L'argent qui rentre. Le versement est borné à ce qui reste dû : on n'encaisse
     * pas plus que la créance, et une facture soldée n'attend plus rien. La date est
     * le jour du clic, celui qui l'écrit est lu dans le jeton — la preuve que l'argent
     * est venu, pas une case « payé » qu'on coche.
     */
    @Transactional
    public FactureResponse encaisser(String id, EncaissementRequest request, Jwt jwt, boolean pricesVisible) {
        Facture facture = factureDe(id);
        Double montant = request == null ? null : request.amount();
        if (montant == null || montant <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Écrivez le montant rentré sur la facture « " + facture.getNumber()
                            + " » : un encaissement nul ou négatif n'est pas de l'argent qui vient.");
        }
        double encaisse = FactureEtats.sommeEncaissee(encaissements.findByFactureIdOrderByPaidOnAsc(id));
        double restant = FactureEtats.restantDu(encaisse, facture.getTotalTTC());
        if (restant <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La facture « " + facture.getNumber() + " » est déjà payée : il ne reste rien à encaisser."
                            + " Un versement de trop se rembourse, il ne se note pas sur la créance.");
        }
        double versement = MaintenanceTotals.round2(montant);
        if (versement > restant + 0.005) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le versement de " + String.format(java.util.Locale.ROOT, "%.2f", versement)
                            + " dépasse ce qui reste dû (" + String.format(java.util.Locale.ROOT, "%.2f", restant)
                            + ") sur la facture « " + facture.getNumber() + " » : on n'encaisse pas plus que"
                            + " la créance. Notez le montant exact reçu.");
        }

        Encaissement e = new Encaissement();
        e.setId("enc-" + UUID.randomUUID());
        e.setFactureId(facture.getId());
        e.setAmount(versement);
        e.setPaidOn(LocalDate.now());
        e.setMode(propre(request.mode()));
        e.setRecordedBy(jwt == null ? null : jwt.getClaimAsString("email"));
        e.setNote(propre(request.note()));
        encaissements.save(e);

        double nouveauRestant = FactureEtats.restantDu(
                FactureEtats.sommeEncaissee(encaissements.findByFactureIdOrderByPaidOnAsc(id)),
                facture.getTotalTTC());
        audit.record("CREATE", "FACTURE", facture.getId(),
                "Encaissement de " + String.format(java.util.Locale.ROOT, "%.2f", versement) + " sur la facture « "
                        + facture.getNumber() + " »" + (e.getMode() == null ? "" : " (" + e.getMode() + ")")
                        + " — reste " + String.format(java.util.Locale.ROOT, "%.2f", nouveauRestant) + " dû", jwt);
        return reponse(facture, pricesVisible);
    }

    // ------------------------------------------------------------------------- petits lecteurs

    private FactureResponse reponse(Facture facture, boolean pricesVisible) {
        List<Encaissement> sesEncaissements = encaissements.findByFactureIdOrderByPaidOnAsc(facture.getId());
        String devisNumber = facture.getDevisId() == null ? null
                : documents.findById(facture.getDevisId()).map(MaintenanceItem::getNumber).orElse(null);
        return FactureResponse.of(facture, numeroBl(facture.getBlId()), devisNumber,
                nomClient(facture.getClientId()), registrationBus(facture.getVehicleId()),
                sesEncaissements, pricesVisible);
    }

    private Facture factureDe(String id) {
        return factures.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Facture introuvable."));
    }

    /**
     * Le devis accepté dont l'argent vient, en remontant la chaîne : BL → ordre →
     * devis. Les deux maillons sont facultatifs en base (un BL a pu être créé à la
     * main) ; sans devis, il n'y a pas de montant accepté à facturer.
     */
    private Optional<MaintenanceItem> devisEnAmont(MaintenanceItem bl) {
        if (bl.getSourceItemId() == null) {
            return Optional.empty();
        }
        MaintenanceItem amont = documents.findById(bl.getSourceItemId()).orElse(null);
        if (amont == null) {
            return Optional.empty();
        }
        if (amont.getType() == MaintenanceType.DEVIS) {
            return Optional.of(amont);
        }
        if (amont.getSourceItemId() == null) {
            return Optional.empty();
        }
        return documents.findById(amont.getSourceItemId()).filter(d -> d.getType() == MaintenanceType.DEVIS);
    }

    private String numeroBl(String blId) {
        return blId == null ? null : documents.findById(blId).map(MaintenanceItem::getNumber).orElse(null);
    }

    private String nomClient(String clientId) {
        return clientId == null ? null : clients.findById(clientId).map(Client::getName).orElse(null);
    }

    private String registrationBus(String vehicleId) {
        return vehicleId == null ? null : vehicles.findById(vehicleId).map(Vehicle::getRegistration).orElse(null);
    }

    /** FA- sur la suite de l'année, comme DV- / OR- / BL- : le plus haut numéro déjà posé décide. */
    private String prochainNumero() {
        String prefixe = "FA-" + LocalDate.now().getYear() + "-";
        return NumberSequence.suivant(prefixe, factures.findNumbersByPrefix(prefixe), 3,
                factures::existsByNumberIgnoreCase);
    }

    private static double nz(Double value) {
        return value == null ? 0.0 : value;
    }

    private static String propre(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}