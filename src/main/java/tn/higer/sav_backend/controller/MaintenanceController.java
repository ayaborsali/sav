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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.ClotureRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceLineDto;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.RefusRequest;
import tn.higer.sav_backend.dto.MaintenanceDtos.SignatureRequest;
import tn.higer.sav_backend.dto.OrdreDtos.EtatClotureDto;
import tn.higer.sav_backend.dto.OrdreDtos.FicheResponse;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.security.Access;
import tn.higer.sav_backend.service.AuditService;
import tn.higer.sav_backend.service.BonLivraisonService;
import tn.higer.sav_backend.service.DevisService;
import tn.higer.sav_backend.service.MaintenanceAttachmentService;
import tn.higer.sav_backend.service.MaintenanceChainService;
import tn.higer.sav_backend.service.MaintenanceDocumentService;
import tn.higer.sav_backend.service.MaintenancePdfService;
import tn.higer.sav_backend.service.MaintenanceTotals;
import tn.higer.sav_backend.service.OrdreService;
import tn.higer.sav_backend.service.SoldPlanService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD des documents de maintenance (devis, ordres de travail, bons de livraison).
 * Lecture : tous les rôles connectés (règle générique GET /api/**), sauf la fiche d'un
 * ordre et son état de clôture — atelier et guichet seulement.
 * Écriture : admin + gestionnaire_sav (règle générique /api/** — consultation bloquée),
 * et le chef d'atelier sur les gestes de l'atelier : écrire sur un papier, clôturer
 * l'ordre, rendre le bus.
 * Les totaux sont calculés côté serveur : total HT = somme des lignes, total TTC =
 * total HT × (1 + TVA) — sauf pour l'ordre de travail, qui ne chiffre pas (V38).
 * La liste est paginée. Les écritures sont auditées.
 *
 * Depuis la phase C, un document peut se rattacher à un forfait vendu et à un de ses
 * paliers — les deux champs restent facultatifs, et un BL hors forfait se comporte
 * exactement comme avant. Le rattachement est vérifié et le palier fait avancer par
 * {@link SoldPlanService} : ce contrôleur ne décide rien sur les forfaits, il demande.
 *
 * Les états ne se tapent plus : le devis avance par {@code /envoi}, {@code /accord} et
 * {@code /refus} ({@link DevisService}), l'ordre se termine par {@code /cloturer}
 * ({@link OrdreService}), le bon de livraison se signe par {@code /signature}
 * ({@link BonLivraisonService}). La case état du formulaire ne fait que relire le papier.
 *
 * Depuis V39, le bon de livraison ne chiffre plus rien non plus : {@code MaintenanceTotals}
 * ne calcule que pour le devis, et ses montants à lui portent le bus et les pièces rendues,
 * pas leur prix.
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    /**
     * Taux de TVA tunisien appliqué au calcul du total TTC. Lu de façon défensive :
     * la valeur par défaut est 19 (%). Une valeur entre 0 et 1 est acceptée comme fraction,
     * une valeur > 1 est comprise comme un pourcentage, une valeur négative retombe sur 19 %.
     */
    @Value("${app.tva.rate:19}")
    private double tvaRateConfig;

    private final MaintenanceRepository maintenance;
    private final SoldPlanService forfaits;
    private final MaintenanceAttachmentService pieces;
    private final MaintenanceChainService chaine;
    private final DevisService devis;
    private final OrdreService ordre;
    private final BonLivraisonService bons;
    private final MaintenanceDocumentService documentService;
    private final MaintenancePdfService pdfService;
    private final AuditService audit;

    public MaintenanceController(MaintenanceRepository maintenance, SoldPlanService forfaits,
                                 MaintenanceAttachmentService pieces, MaintenanceChainService chaine,
                                 DevisService devis, OrdreService ordre, BonLivraisonService bons,
                                 MaintenanceDocumentService documentService, MaintenancePdfService pdfService,
                                 AuditService audit) {
        this.maintenance = maintenance;
        this.forfaits = forfaits;
        this.pieces = pieces;
        this.chaine = chaine;
        this.devis = devis;
        this.ordre = ordre;
        this.bons = bons;
        this.documentService = documentService;
        this.pdfService = pdfService;
        this.audit = audit;
    }

    @GetMapping
    public Page<MaintenanceResponse> list(
            @PageableDefault(size = 20, sort = "date", direction = Sort.Direction.DESC) Pageable pageable) {
        return maintenance.findAll(pageable).map(MaintenanceResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse create(@Valid @RequestBody MaintenanceRequest request,
                                      @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        devis.verifierCreation(request.type(), request.status());
        ordre.verifierNaissance(request.type(), request.status());
        bons.verifierCreation(request.type(), request.status());
        String numero = numeroDuPapier(request.number(), request.type(), null);
        if (maintenance.existsByNumberIgnoreCase(numero)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce numéro de document existe déjà.");
        }
        MaintenanceItem m = new MaintenanceItem();
        apply(m, request, numero);
        m.setId("maint-" + UUID.randomUUID());
        if (m.getDate() == null) {
            m.setDate(LocalDate.now());
        }
        // Le rattachement est vérifié avant l'écriture : un refus ne doit jamais laisser
        // un devis à moitié enregistré avec un palier coché.
        devis.verifierLignes(m);
        // La ligne qui cite une pièce du catalogue est vérifiée sur la pièce réelle :
        // mots et prix. Le prix ne s'écarte du catalogue que pour un compte qui a
        // droit aux chiffres commerciaux — c'est la règle validée par l'atelier.
        devis.verifierLignesDeCatalogue(m, Access.seesCommercialFigures(auth));
        forfaits.verifierRattachement(m);
        // Idem pour le compteur du bus : la porte unique écrit avant tout refus, jamais
        // après — une fiche de bus touchée par un papier qui n'a pas été enregistré
        // serait une mesure sans preuve.
        bons.poserLesCases(m, request.mileageAtDelivery(), request.partsReturned(), jwt);
        maintenance.save(m);
        forfaits.appliquerSuivi(m, null, null, jwt);
        audit.record("CREATE", "MAINTENANCE", m.getId(),
                "Création du document « " + m.getNumber() + " »" + mentionArgent(m), jwt);
        return MaintenanceResponse.from(m);
    }

    @PutMapping("/{id}")
    public MaintenanceResponse update(@PathVariable String id, @Valid @RequestBody MaintenanceRequest request,
                                      @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        MaintenanceItem m = maintenance.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document de maintenance introuvable."));
        // Le verrou du devis se lit sur l'état du papier TEL QU'IL EST en base, avant
        // n'importe quelle écriture : un devis envoyé ou accepté ne se modifie plus.
        devis.verifierModification(m, request.status());
        ordre.verifierModification(m, request.status());
        bons.verifierModification(m, request.status());
        String numero = numeroDuPapier(request.number(), m.getType(), m.getNumber());
        if (maintenance.existsByNumberIgnoreCaseAndIdNot(numero, id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce numéro de document existe déjà.");
        }
        String ancienForfait = m.getSoldPlanId();
        String ancienPalier = m.getPlanStepId();
        apply(m, request, numero);
        devis.verifierLignes(m);
        devis.verifierLignesDeCatalogue(m, Access.seesCommercialFigures(auth));
        forfaits.verifierRattachement(m);
        bons.poserLesCases(m, request.mileageAtDelivery(), request.partsReturned(), jwt);
        maintenance.save(m);
        forfaits.appliquerSuivi(m, ancienForfait, ancienPalier, jwt);
        audit.record("UPDATE", "MAINTENANCE", id,
                "Mise à jour du document « " + m.getNumber() + " »" + mentionArgent(m), jwt);
        return MaintenanceResponse.from(m);
    }

    /**
     * « Clôturer l'ordre » : le papier qui dit que l'atelier a travaillé se termine par
     * ce clic, jamais par la case état du formulaire. Le serveur écrit la date, réclame
     * les opérations cochées, les heures réelles quand le papier acquitte un palier du
     * forfait, et une preuve — la photo « après » de la sortie d'origine, la fiche
     * d'atelier signée, ou une raison écrite qui part au journal.
     */
    @PostMapping("/{id}/cloturer")
    public MaintenanceResponse cloturer(@PathVariable String id,
                                        @RequestBody(required = false) ClotureRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(ordre.cloturer(id, request, jwt));
    }

    /** Ce qui manque pour clôturer, calculé par le serveur : l'écran colore, il ne décide pas. */
    @GetMapping("/{id}/cloture")
    public EtatClotureDto etatDeCloture(@PathVariable String id) {
        return ordre.etatDeCloture(id);
    }

    /** La fiche du papier : ses origines nommées, le forfait qu'il touche, les photos de sa sortie, les pièces posées. */
    @GetMapping("/{id}/fiche")
    public FicheResponse fiche(@PathVariable String id) {
        return ordre.fiche(id);
    }

    /**
     * Le papier de l'atelier en PDF A4, avec l'en-tête de Saphir : une feuille que l'on garde,
     * pas une page d'écran. Le document est reconstruit intégralement depuis la base à chaque
     * demande (le même {@code fiche()} que l'écran), il ne peut donc pas rester en retard sur le
     * papier — et la mise en page est déléguée à un script Python, comme le contrat et le bon
     * d'intervention.
     *
     * L'argent suit le papier, pas l'impression : le PDF d'un devis porte ses totaux, celui d'un
     * ordre (V38) et d'un bon de livraison (V39) n'en reçoit aucun. L'édition est journalisée —
     * une sortie d'impression se sait.
     */
    @GetMapping("/{id}/document.pdf")
    public ResponseEntity<byte[]> document(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> plan = documentService.payload(id);
        byte[] pdf = pdfService.render(plan);
        String numero = String.valueOf(plan.getOrDefault("number", id));
        String genre = String.valueOf(plan.getOrDefault("kind", "papier de l'atelier"));
        String type = String.valueOf(plan.getOrDefault("type", ""));
        // Le nom du fichier suit la nature du papier, jamais son numéro : un poste qui a gardé
        // une numérotation d'avant (« DV-… » sur un ordre) ne doit pas faire sortir un « devis ».
        String suffixe = switch (type) {
            case "devis" -> "devis";
            case "operation" -> "ordre-de-travail";
            case "bl" -> "bon-de-livraison";
            default -> "papier";
        };
        audit.record("DOWNLOAD", "MAINTENANCE", id, "Édition du document « " + numero + " » (" + genre
                + ") en PDF — " + ("devis".equals(suffixe) ? "avec les montants" : "aucun montant"), jwt);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(numero + "-" + suffixe + ".pdf", StandardCharsets.UTF_8)
                        .build().toString())
                .body(pdf);
    }

    /**
     * « Envoyer au client » : le devis part, et son contenu se ferme avec lui. La date
     * est posée par le serveur — un accord dont la date se tape à la main se recule
     * quand le prix dérange.
     */
    @PostMapping("/{id}/envoi")
    public MaintenanceResponse envoi(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(devis.envoyer(id, jwt));
    }

    /** Le client a signé, la preuve est jointe : le devis est accepté, l'ordre peut s'ouvrir. */
    @PostMapping("/{id}/accord")
    public MaintenanceResponse accord(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(devis.accord(id, jwt));
    }

    /** Le client ne signe pas : le motif est écrit sur le papier, et le palier du forfait reste dû. */
    @PostMapping("/{id}/refus")
    public MaintenanceResponse refus(@PathVariable String id, @RequestBody RefusRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(devis.refus(id, request, jwt));
    }

    /**
     * « Marquer signé » : le client reprend son bus et signe le papier. Le serveur écrit
     * le jour du clic, réclame le nom de qui signe et le scan du bon signé, et verrouille
     * avec le bon de livraison l'ordre dont il est né.
     */
    @PostMapping("/{id}/signature")
    public MaintenanceResponse signature(@PathVariable String id,
                                         @RequestBody(required = false) SignatureRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(bons.signer(id, request, jwt));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        MaintenanceItem m = maintenance.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document de maintenance introuvable."));
        // Le papier part, ses preuves avec, et le palier qu'il avait fait se libère :
        // sans ce relâchement le forfait resterait amputé d'une visite que plus rien
        // ne justifie. Mais un papier qui a déjà fait naître le suivant ne se dérobe
        // pas : la chaîne se coupe par le bout d'après.
        chaine.refuserSiEnfantDejaNe(m);
        int fichiersOtes = pieces.supprimerAvecDocument(id);
        forfaits.relacherLiens(m, jwt);
        maintenance.deleteById(id);
        audit.record("DELETE", "MAINTENANCE", id,
                "Suppression du document « " + m.getNumber() + " »"
                        + (fichiersOtes > 0 ? " (" + fichiersOtes + " pièce(s) jointe(s))" : ""), jwt);
    }

    /**
     * Le devis a l'accord du client : l'atelier exécute. Le clic fait l'ordre de
     * travail, avec les lignes du devis reprises telles quelles et le lien écrit en
     * base (V35). Refusé tant que le devis est brouillon, et une seule fois.
     */
    @PostMapping("/{id}/ordre")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse ordreDepuisDevis(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(chaine.ordreDepuisDevis(id, jwt));
    }

    /** Le travail est fait : le bus et les pièces rentrent chez le client. */
    @PostMapping("/{id}/bon-livraison")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse bonLivraisonDepuisOrdre(@PathVariable String id,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return MaintenanceResponse.from(chaine.bonLivraisonDepuisOrdre(id, jwt));
    }

    private void apply(MaintenanceItem m, MaintenanceRequest r, String numero) {
        m.setType(r.type());
        m.setNumber(numero);
        m.setVehicleId(r.vehicleId());
        m.setClientId(r.clientId() != null ? r.clientId() : "");
        if (r.date() != null) {
            m.setDate(r.date());
        }
        m.setStatus(r.status() != null ? r.status() : MaintenanceStatus.DRAFT);
        m.setTechnician(r.technician());
        m.setDescription(r.description());
        ordre.verifierAnomalies(m.getType(), r.anomalies());
        // La case « Anomalies constatées » est celle de l'ordre : ce qui a été vu sur le
        // bus pendant le travail. Vide veut dire vide, pas « on garde ce qui était écrit »
        // — le formulaire relit le papier avant d'envoyer, donc il renvoie la case telle
        // quelle, et l'atelier qui l'efface l'a réellement effacée.
        m.setAnomalies(blankToNull(r.anomalies()));
        // Une case vide du formulaire veut dire « hors forfait », pas « identifiant vide ».
        m.setSoldPlanId(blankToNull(r.soldPlanId()));
        m.setPlanStepId(blankToNull(r.planStepId()));

        List<MaintenanceDetail> details = new ArrayList<>();
        int numeroDeLigne = 0;
        if (r.details() != null) {
            for (MaintenanceLineDto line : r.details()) {
                MaintenanceDetail d = new MaintenanceDetail();
                d.setReference(line.reference());
                d.setLabel(line.label());
                d.setQuantity(line.quantity() != null ? line.quantity() : 0.0);
                d.setUnitPrice(line.unitPrice() != null ? line.unitPrice() : 0.0);
                d.setQuantityText(line.quantityText());
                // La pièce du catalogue dont la ligne est la copie : une case vide veut
                // dire « saisie à la main », pas « pièce inconnue ».
                d.setPieceId(blankToNull(line.pieceId()));
                numeroDeLigne++;
                // Le coche « fait » et les heures réelles sont la matière de la clôture de
                // l'ordre. Sur un devis ou un bon de livraison, la case n'existe pas à
                // l'écran : elle reste vide et ne veut rien dire.
                d.setDone(line.done());
                d.setRealHours(line.realHours());
                if (line.realHours() != null && line.realHours() < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Ligne " + numeroDeLigne + " : les heures réelles ne se reculent pas (« "
                                    + line.realHours() + " »). Le temps passé sur le bus est une durée ; si le"
                                    + " chiffre est faux, écrivez le bon.");
                }
                d.setNature(natureDe(line.nature(), numeroDeLigne));
                d.setSuppliedBy(fournisseurDe(line.suppliedBy(), numeroDeLigne));
                details.add(d);
            }
        }
        m.getDetails().clear();
        m.getDetails().addAll(details);

        // Totaux autoritaires serveur : on ignore les montants envoyés par le client.
        // Le même calcul sert à la clôture d'une sortie d'entretien (MaintenanceTotals).
        MaintenanceTotals.appliquer(m, tvaRateConfig);
    }

    /**
     * Le numéro du papier : celui que le poste a tapé s'il en met un, sinon celui que
     * le serveur pose sur la suite de la famille (DV-, OR-, BL-). Sur une modification,
     * une case vide ne renumérote pas le document — l'atelier relit le même papier.
     */
    private String numeroDuPapier(String demande, MaintenanceType type, String numeroActuel) {
        String tape = blankToNull(demande);
        if (tape != null) {
            return tape;
        }
        return numeroActuel != null ? numeroActuel : chaine.prochainNumero(type);
    }

    /**
     * Un mot de nature inconnu se refuse, il ne s'ignore pas : une ligne dont la nature
     * est jetée serait chiffrée sans que personne ne sache ce qu'elle chiffre — et le
     * client le découvre sur la facture. (C'est le même piège qu'un nom de case inconnu
     * sur un dossier SAV, ignoré en silence avec un 200.)
     */
    private static LineNature natureDe(String valeur, int ligne) {
        String mot = blankToNull(valeur);
        if (mot == null) {
            return null;
        }
        try {
            return LineNature.valueOf(mot.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ligne " + ligne + " : « " + mot + " » n'est pas une nature. Ce qu'on chiffre est une"
                            + " main-d'œuvre, une pièce ou un déplacement.");
        }
    }

    private static PartSupplier fournisseurDe(String valeur, int ligne) {
        String mot = blankToNull(valeur);
        if (mot == null) {
            return null;
        }
        try {
            return PartSupplier.valueOf(mot.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ligne " + ligne + " : « " + mot + " » ne dit pas qui fournit la pièce. La réponse est"
                            + " Saphir ou le client.");
        }
    }

    /**
     * Ce que le journal dit de l'argent du papier. Seul le devis chiffre : l'ordre de
     * travail décrit le travail (V38) et le bon de livraison le rend (V39). Écrire
     * « TTC null » à côté de ces deux-là ne veut rien dire, et laisser passer un montant
     * en voudrait dire deux à défendre pour un même travail.
     */
    private static String mentionArgent(MaintenanceItem m) {
        return m.getType() == MaintenanceType.DEVIS ? " (TTC " + m.getTotalTTC() + ")" : "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
