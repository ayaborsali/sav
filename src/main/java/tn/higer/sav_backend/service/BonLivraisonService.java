package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.BonLivraisonDtos.BonDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.CompteurDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.EtatSignatureDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.IntervenantDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.PieceDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.TravailDto;
import tn.higer.sav_backend.dto.MaintenanceDtos.SignatureRequest;
import tn.higer.sav_backend.model.Intervention;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PartSupplier;
import tn.higer.sav_backend.model.Technician;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.InterventionRepository;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.TechnicianRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Le bon de livraison : le papier que le client tient en main à la restitution du bus.
 *
 * Trois portes se ferment ici, et une seule s'ouvre :
 *
 * 1. <b>il ne chiffre pas</b> (V39). Le prix est sur le devis que le client a signé ;
 *    le remettre sur le rendu du bus donnerait trois montants à défendre pour un même
 *    travail. {@link MaintenanceTotals} ne calcule donc plus pour ce papier, et le
 *    journal ne mentionne plus son argent.
 * 2. <b>il ne répare pas</b>. Une pièce se pose sur l'ordre de travail
 *    ({@link ItemPartService}) : le BL rend ce qui a été posé, il n'en pose pas.
 * 3. <b>il ne décompte pas le palier</b>. Le palier est acquitté par le papier qui a
 *    travaillé ; le BL l'efface en naissant ({@link MaintenanceChainService}).
 * 4. <b>il se signe</b>. « Marquer signé » exige le nom de qui reprend le bus et le
 *    scan du papier signé, joint au document. La date est le jour du clic, jamais une
 *    case du formulaire. Un BL signé verrouille le BL et l'ordre dont il est né : le
 *    client a tenu les deux en main, et une ligne changée après sa signature n'est plus
 *    une correction mais une autre marchandise.
 *
 * Le compteur saisi sur le papier n'est pas une copie décorative de la fiche du bus : il
 * rejoint {@code vehicles.mileage} par la porte unique ({@link VehicleMileageService}),
 * qui refuse une baisse sans motif écrit. C'est par là que la restitution du bus fait
 * avancer le rappel du palier suivant.
 */
@Service
public class BonLivraisonService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MaintenanceRepository documents;
    private final MaintenanceAttachmentRepository piecesJointes;
    private final InterventionRepository sorties;
    private final TechnicianRepository technicians;
    private final VehicleRepository vehicles;
    private final VehicleMileageService compteur;
    private final AuditService audit;

    public BonLivraisonService(MaintenanceRepository documents, MaintenanceAttachmentRepository piecesJointes,
                               InterventionRepository sorties, TechnicianRepository technicians,
                               VehicleRepository vehicles, VehicleMileageService compteur, AuditService audit) {
        this.documents = documents;
        this.piecesJointes = piecesJointes;
        this.sorties = sorties;
        this.technicians = technicians;
        this.vehicles = vehicles;
        this.compteur = compteur;
        this.audit = audit;
    }

    // ---------------------------------------------------------------------- la signature

    /**
     * Le clic qui rend le bus. Le nom du signataire et le scan sont les deux seules
     * preuves que quelqu'un, chez le client, a repris le bus — le portail n'a pas de
     * compte client, et un « oui » que personne ne peut montrer se retourne contre
     * l'atelier à la première réserve contestée.
     */
    @Transactional
    public MaintenanceItem signer(String id, SignatureRequest request, Jwt jwt) {
        MaintenanceItem bl = bonDe(id);
        if (bl.getStatus() == MaintenanceStatus.SIGNED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bon de livraison « " + bl.getNumber() + " » est déjà signé" + dateDeSignature(bl)
                            + " (par " + signataireDe(bl) + ") : une signature ne se pose pas deux fois. Si le"
                            + " client reprend le bus plus tard avec une réserve qu'il n'avait pas dite, ouvrez"
                            + " le dossier qui la portera — ne refaites pas le papier qu'il a signé.");
        }
        if (bl.getStatus() == MaintenanceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bon de livraison « " + bl.getNumber() + " » est annulé : il n'attend plus de signature."
                            + " Éditez le bon de livraison sur l'ordre qui a travaillé.");
        }
        String nom = propre(request == null ? null : request.signataireNom());
        if (nom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Écrivez le nom de qui reprend le bus sur « " + bl.getNumber() + " » : le portail n'a pas de"
                            + " compte client, alors sans nom sur le papier, personne ne peut dire à qui le bus"
                            + " a été rendu le jour où une réserve se discute.");
        }
        if (piecesJointes.countByMaintenanceItemId(bl.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucun papier joint au bon de livraison « " + bl.getNumber() + " » : la signature se prouve"
                            + " par le scan du bon que le client a signé et rendu (scan ou photo). Joignez-le,"
                            + " puis marquez signé.");
        }
        bl.setStatus(MaintenanceStatus.SIGNED);
        bl.setSignedOn(LocalDate.now());
        bl.setSignataireNom(nom);
        bl.setSignataireQualite(propre(request.signataireQualite()));
        String reserves = propre(request.reserves());
        if (reserves != null) {
            bl.setReserves(reserves);
        }
        documents.save(bl);
        audit.record("UPDATE", "MAINTENANCE", bl.getId(),
                "Bon de livraison « " + bl.getNumber() + " » signé par " + nom
                        + (bl.getSignataireQualite() == null ? "" : " (" + bl.getSignataireQualite() + ")")
                        + " le " + jour(bl.getSignedOn())
                        + mentionPieces(bl)
                        + (reserves == null ? "" : " — réserves du client : « " + reserves + " »"), jwt);
        return bl;
    }

    // ------------------------------------------------------------------ les gardes du formulaire

    /**
     * Un bon de livraison commence brouillon. Il n'a ni envoi, ni accord, ni refus à
     * son nom : le seul état qui l'avance est la signature du client, et elle se pose
     * par le clic, pas par la case état du formulaire — un « signé » tapé au clavier
     * serait un bus rendu que personne n'a repris.
     */
    public void verifierCreation(MaintenanceType type, MaintenanceStatus demandee) {
        if (type != MaintenanceType.BL || demandee == null || demandee == MaintenanceStatus.DRAFT) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Un bon de livraison ne naît pas « " + demandee.label(MaintenanceType.BL).toLowerCase()
                        + " » : il commence brouillon. « Marquer signé » écrit l'état, et le clic réclame le nom"
                        + " du signataire et le scan du papier que le client a signé.");
    }

    /**
     * Ce que le formulaire peut encore changer sur un bon de livraison.
     *
     * Tant qu'il est brouillon, tout : la date de restitution, le compteur, les pièces
     * remises. Dès qu'il est signé, plus rien — et l'ordre dont il est né est verrouillé
     * avec lui ({@link OrdreService#verifierModification}), parce que le client a eu les
     * deux papiers en main.
     */
    public void verifierModification(MaintenanceItem avant, MaintenanceStatus demandee) {
        if (avant.getType() != MaintenanceType.BL) {
            return;
        }
        if (avant.getStatus() == MaintenanceStatus.SIGNED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bon de livraison « " + avant.getNumber() + " » est signé" + dateDeSignature(avant)
                            + " (par " + signataireDe(avant) + ") : il ne se réécrit plus" + verrouDeLOrdre(avant)
                            + ". Ce que le client a signé est la marche telle qu'elle s'est passée. Si une réserve"
                            + " est apparue après coup, ouvrez le dossier qui la portera.");
        }
        if (demandee != null && demandee != avant.getStatus()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'état du bon de livraison « " + avant.getNumber() + " » ne se tape pas (« "
                            + demandee.label(MaintenanceType.BL) + " » demandé, le papier est « "
                            + mot(avant) + " ») : il est brouillon tant que le client n'a pas signé, et « Marquer"
                            + " signé » est le seul geste qui l'avance.");
        }
    }

    /**
     * Les deux cases propres au bon de livraison, et le compteur qui passe par sa porte.
     *
     * Le compteur n'est poussé vers la fiche du bus que s'il change : un papier qu'on
     * relit et qu'on renvoie tel quel ne doit pas réécrire le kilométrage du bus à
     * chaque enregistrement. Une valeur plus basse que ce que le portail sait déjà se
     * refuse AVANT l'enregistrement du papier — la porte unique écrit le refus, et le
     * bon de livraison reste à sa valeur d'avant.
     */
    @Transactional
    public void poserLesCases(MaintenanceItem papier, Long valeurDemandee, Boolean piecesRemises, Jwt jwt) {
        boolean aRienDeSaisi = valeurDemandee == null && piecesRemises == null;
        if (papier.getType() != MaintenanceType.BL) {
            if (aRienDeSaisi) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le compteur à la restitution et les pièces remises se notent sur un bon de livraison, pas"
                            + " sur un « " + (papier.getType() == null ? "papier sans nature"
                                    : papier.getType().label()) + " » (« " + papier.getNumber()
                            + " »). Le bus se rend sur le papier que le client signe ; l'ordre, lui, note le"
                            + " travail et le temps qu'il a pris.");
        }
        if (piecesRemises != null) {
            papier.setPartsReturned(piecesRemises);
        }
        if (valeurDemandee == null || valeurDemandee.equals(papier.getMileageAtDelivery())) {
            return;
        }
        Vehicle bus = vehicles.findById(papier.getVehicleId()).orElse(null);
        if (bus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le bus du bon de livraison « " + papier.getNumber() + " » est introuvable : le compteur"
                            + " n'a pas où être écrit. Reprenez le bus du papier sur sa fiche.");
        }
        // La porte unique, sans motif : une baisse volontaire du compteur se motive sur
        // la fiche du bus, pas sur un papier que le client signe.
        compteur.saisieAtelier(bus, valeurDemandee, null, jwt);
        papier.setMileageAtDelivery(valeurDemandee);
    }

    // ------------------------------------------------------------------------- la fiche

    /**
     * Le bloc du bon de livraison : le travail tel que l'ordre l'a fait et vu, les pièces
     * que Saphir a fournies, l'homme et ses heures tels que la sortie les a écrits, le
     * compteur des deux côtés de la case, et ce qui manque pour signer.
     */
    BonDto blocDeBon(MaintenanceItem bl, Intervention sortie) {
        MaintenanceItem ordre = ordreDe(bl).orElse(null);
        List<PieceDto> fourniesParSaphir = new ArrayList<>();
        for (MaintenanceDetail ligne : lignesDuTravail(bl, ordre)) {
            if (ligne.getNature() == LineNature.PART && ligne.getSuppliedBy() == PartSupplier.SAPHIR) {
                fourniesParSaphir.add(new PieceDto(propre(ligne.getReference()), propre(ligne.getLabel()),
                        ligne.getQuantity()));
            }
        }
        return new BonDto(
                new TravailDto(ordre == null ? null : ordre.getNumber(),
                        ordre == null ? null : ordre.getClosedOn(),
                        ordre == null ? null : propre(ordre.getAnomalies())),
                fourniesParSaphir,
                intervenantDe(sortie),
                compteurDe(bl),
                piecesJointes.countByMaintenanceItemId(bl.getId()) > 0,
                etatSignatureDe(bl));
    }

    /**
     * Les lignes que le client voit à la restitution : celles du bon de livraison, reprises
     * de l'ordre (V39). Un BL né avant ce jour n'a pas d'heures sur ses lignes — elles se
     * relisent alors sur l'ordre, et le portail ne les invente pas.
     */
    private List<MaintenanceDetail> lignesDuTravail(MaintenanceItem bl, MaintenanceItem ordre) {
        if (bl.getDetails().stream().anyMatch(ligne -> ligne.getRealHours() != null || ligne.getDone() != null)) {
            return bl.getDetails();
        }
        return ordre == null || ordre.getDetails().isEmpty() ? bl.getDetails() : ordre.getDetails();
    }

    /** L'homme et ses heures, relus sur la sortie d'origine — jamais retapés à l'atelier. */
    private IntervenantDto intervenantDe(Intervention sortie) {
        if (sortie == null) {
            return new IntervenantDto(null, null, null, null, null);
        }
        return new IntervenantDto(sortie.getId(), sortie.getNumber(), nomDe(sortie),
                sortie.getDepartedAt(), sortie.getFinishedAt());
    }

    /** Le compteur d'en face, celui que la porte unique va comparer à la saisie. */
    private CompteurDto compteurDe(MaintenanceItem bl) {
        Vehicle bus = bl.getVehicleId() == null ? null
                : vehicles.findById(bl.getVehicleId()).orElse(null);
        if (bus == null) {
            return new CompteurDto(null, null, null, null, null, null, null);
        }
        VehicleMileageService.DernierReleve releve = compteur.dernierReleve(bus.getId());
        return new CompteurDto(bus.getMileage(),
                bus.getMileageOrigin() == null ? null : bus.getMileageOrigin().label(),
                bus.getMileageRecordedBy(),
                releve == null ? null : releve.km(), releve == null ? null : releve.sortie(),
                releve == null ? null : releve.par(),
                releve == null || releve.quand() == null ? null : releve.quand().toLocalDate());
    }

    /** Ce qui manque pour signer, calculé une seule fois : l'écran colore, le clic refuse. */
    private EtatSignatureDto etatSignatureDe(MaintenanceItem bl) {
        List<String> ceQuiManque = new ArrayList<>();
        if (bl.getStatus() == MaintenanceStatus.SIGNED) {
            ceQuiManque.add("ce bon de livraison est déjà signé" + dateDeSignature(bl)
                    + " (par " + signataireDe(bl) + ")");
        } else if (bl.getStatus() == MaintenanceStatus.CANCELLED) {
            ceQuiManque.add("ce bon de livraison est annulé : il n'attend plus de signature");
        } else if (piecesJointes.countByMaintenanceItemId(bl.getId()) == 0) {
            ceQuiManque.add("aucun papier joint à ce bon de livraison : le scan du bon que le client a signé est"
                    + " la seule preuve de la restitution");
        }
        String message = ceQuiManque.isEmpty()
                ? "Prêt à signer — il restera à écrire le nom de qui reprend le bus."
                : "Signature refusée : " + ceQuiManque.get(0) + ".";
        return new EtatSignatureDto(ceQuiManque.isEmpty(), ceQuiManque, message);
    }

    // ------------------------------------------------------------------------- petits lecteurs

    private MaintenanceItem bonDe(String id) {
        MaintenanceItem papier = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document de maintenance introuvable."));
        if (papier.getType() != MaintenanceType.BL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + papier.getNumber() + " » n'est pas un bon de livraison ("
                            + (papier.getType() == null ? "type inconnu" : papier.getType().label()) + ") : on"
                            + " ne marque signé que le papier que le client tient à la restitution du bus.");
        }
        return papier;
    }

    /** L'ordre dont ce bon est né, s'il est nommé — c'est lui qui est verrouillé avec le BL. */
    private Optional<MaintenanceItem> ordreDe(MaintenanceItem bl) {
        return bl.getSourceItemId() == null ? Optional.empty()
                : documents.findById(bl.getSourceItemId())
                        .filter(avant -> avant.getType() == MaintenanceType.OPERATION);
    }

    /** Le verrou qui tient avec la signature : le papier d'après ferme le papier d'avant. */
    private String verrouDeLOrdre(MaintenanceItem bl) {
        return ordreDe(bl).map(avant -> ", et l'ordre « " + avant.getNumber() + " » dont il est né non plus")
                .orElse("");
    }

    private String nomDe(Intervention sortie) {
        if (sortie.getTechnicianId() == null) {
            return null;
        }
        return technicians.findById(sortie.getTechnicianId()).map(Technician::getName).orElse(null);
    }

    private static String signataireDe(MaintenanceItem bl) {
        String nom = propre(bl.getSignataireNom());
        if (nom == null) {
            return "aucun nom écrit";
        }
        String qualite = propre(bl.getSignataireQualite());
        return qualite == null ? nom : nom + ", " + qualite.toLowerCase();
    }

    private static String dateDeSignature(MaintenanceItem bl) {
        return bl.getSignedOn() == null ? "" : " le " + bl.getSignedOn().format(JOUR);
    }

    /** Ce que le journal dit des pièces remises — le constructeur les réclame, le client les garde parfois. */
    private static String mentionPieces(MaintenanceItem bl) {
        if (bl.getPartsReturned() == null) {
            return " — pièces remplacées : remise non indiquée";
        }
        return " — pièces remplacées " + (bl.getPartsReturned() ? "remises au client" : "gardées par Saphir");
    }

    private static String mot(MaintenanceItem bl) {
        return bl.getStatus() == null ? "sans état" : bl.getStatus().label(MaintenanceType.BL);
    }

    private static String jour(LocalDate date) {
        return date == null ? "—" : date.format(JOUR);
    }

    private static String propre(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
