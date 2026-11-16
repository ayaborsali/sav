package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartRequest;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartResponse;
import tn.higer.sav_backend.dto.ItemPartDtos.RetourRequest;
import tn.higer.sav_backend.model.ItemPart;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.repository.ItemPartRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * La seule porte d'écriture des pièces posées.
 *
 * Trois raisons à ce qu'elle existe séparément du document de maintenance :
 *  - le bus d'une pièce n'est jamais saisi : il est celui du papier, et le serveur
 *    va le lire lui-même. Deux portes pour « dans quel bus », c'est deux réponses
 *    le jour où il faut les additionner ;
 *  - le sort de la pièce déposée est une décision humaine datée, pas un booléen
 *    qu'on coche au vol : c'est la case qui fait perdre un remboursement ;
 *  - une pièce déjà réclamée au constructeur ne s'efface plus : le papier déposé
 *    la nomme, et la disparaître de la base laisserait un claim sans preuve.
 */
@Service
public class ItemPartService {

    private final ItemPartRepository parts;
    private final MaintenanceRepository items;
    private final AuditService audit;

    public ItemPartService(ItemPartRepository parts, MaintenanceRepository items, AuditService audit) {
        this.parts = parts;
        this.items = items;
        this.audit = audit;
    }

    public List<ItemPartResponse> pourDocument(String maintenanceItemId) {
        return parts.findByMaintenanceItemIdOrderByFittedOnAsc(maintenanceItemId).stream()
                .map(ItemPartResponse::from).toList();
    }

    public List<ItemPartResponse> pourBus(String vehicleId) {
        return parts.findByVehicleIdOrderByFittedOnDesc(vehicleId).stream()
                .map(ItemPartResponse::from).toList();
    }

    /**
     * Une pièce posée pendant ce papier. {@code savCaseId} est laissé à null : la
     * pièce n'est réclamée à personne tant qu'un dossier garantie n'est pas né, et
     * c'est {@link #rattacherAuDossier} qui la nomme, plus tard, sans que l'atelier
     * ait à la rechoisir.
     */
    @Transactional
    public ItemPartResponse ajouter(String maintenanceItemId, ItemPartRequest request, Jwt jwt) {
        MaintenanceItem item = items.findById(maintenanceItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Papier de maintenance introuvable : la pièce n'a pas où être posée."));
        // Une pièce posée est un fait, pas une proposition. Le devis, lui, ne propose
        // que des chiffres au client : y attacher une pièce ferait croire à l'atelier
        // qu'elle est montée, alors que le client n'a peut-être même pas signé.
        if (item.getType() == MaintenanceType.DEVIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + item.getNumber() + " » est un devis : il chiffre, il ne répare pas. Une pièce se"
                            + " pose sur l'ordre de travail sorti de ce devis — l'ordre est le papier qui dit"
                            + " que l'atelier a travaillé sur ce bus.");
        }
        // Le bon de livraison rend les pièces, il n'en pose pas (V39). La maquette est
        // formelle : pas de bloc « Poser la pièce » sur ce papier. Y en poser une
        // ferait croire au magasin qu'elle est sortie pour ce bus alors que le travail
        // est écrit sur l'ordre d'avant.
        if (item.getType() == MaintenanceType.BL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + item.getNumber() + " » est un bon de livraison : il rend le bus et les pièces déjà"
                            + " posées, il n'en monte pas. Une pièce se pose sur l'ordre de travail dont ce bon"
                            + " est né — c'est lui qui porte le travail de l'atelier.");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Indiquez une quantité — une pièce posée en quantité inconnue ne se débite nulle part.");
        }
        if (estVide(request.reference()) && estVide(request.designation())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nommez la pièce : sa référence (celle du catalogue Higer) ou sa désignation."
                            + " Une ligne sans nom ne se retrouve pas quand le constructeur la demande.");
        }
        ItemPart p = new ItemPart();
        p.setId("part-" + UUID.randomUUID());
        p.setMaintenanceItemId(item.getId());
        p.setVehicleId(item.getVehicleId());
        p.setReference(traite(request.reference()));
        p.setDesignation(traite(request.designation()));
        p.setQuantity(request.quantity());
        p.setFittedOn(request.fittedOn() != null ? request.fittedOn() : item.getDate());
        parts.save(p);
        audit.record("CREATE", "ITEM_PART", p.getId(),
                "Pièce posée sur le papier " + item.getNumber() + " : " + libelle(p), jwt);
        return ItemPartResponse.from(p);
    }

    @Transactional
    public void supprimer(String partId, Jwt jwt) {
        ItemPart p = parts.findById(partId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pièce introuvable."));
        if (p.getSavCaseId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cette pièce est réclamée par un dossier garantie : on ne l'efface pas."
                            + " Corrigez le dossier, ou écrivez son sort dans la case retour de pièce.");
        }
        parts.delete(p);
        audit.record("DELETE", "ITEM_PART", partId, "Retrait d'une pièce posée : " + libelle(p), jwt);
    }

    /**
     * Le sort de la pièce déposée. Deux branches licites, jamais une moitié :
     *  - repartie chez le constructeur → la date du départ, sinon rien ne le prouve ;
     *  - pas repartie → la raison écrite, parce que c'est elle que le contrôleur
     *    constructeur lit quand il annule le remboursement.
     *
     * La date n'est jamais supposée à aujourd'hui : une pièce partie la semaine
     * dernière et datée d'aujourd'hui ferait mentir le délai de retour.
     */
    @Transactional
    public ItemPartResponse enregistrerRetour(String partId, RetourRequest request, Jwt jwt) {
        ItemPart p = parts.findById(partId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pièce introuvable."));
        if (request.envoyee()) {
            if (request.date() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La pièce est repartie : indiquez la date d'envoi. Sans date, le constructeur"
                                + " n'a rien à vérifier et le retour ne compte pas.");
            }
            p.setReturned(true);
            p.setReturnedAt(request.date());
            p.setReturnReason(traite(request.motif()));
        } else {
            if (estVide(request.motif())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La pièce n'est pas repartie : écrivez pourquoi. C'est précisément ce que le"
                                + " contrôleur demande avant d'annuler un remboursement.");
            }
            p.setReturned(false);
            p.setReturnedAt(null);
            p.setReturnReason(request.motif().trim());
        }
        parts.save(p);
        audit.record("UPDATE", "ITEM_PART", p.getId(),
                "Retour de la pièce " + libelle(p) + (p.isReturned()
                        ? " : envoyée le " + p.getReturnedAt() : " : pas envoyée, motif écrit"), jwt);
        return ItemPartResponse.from(p);
    }

    /**
     * Les pièces d'un papier deviennent la marchandise réclamée par un dossier
     * garantie. Appelé par le serveur à la naissance du claim, jamais depuis
     * l'écran : l'atelier ne rechoisit pas les pièces qu'il vient de poser.
     *
     * Un papier sans pièce ne rattache rien et ne dit rien — ce n'est pas une
     * erreur, c'est une réparation sans pièce changée.
     */
    @Transactional
    public int rattacherAuDossier(String maintenanceItemId, String savCaseId) {
        if (maintenanceItemId == null || savCaseId == null) {
            return 0;
        }
        List<ItemPart> libres = parts.findByMaintenanceItemIdOrderByFittedOnAsc(maintenanceItemId).stream()
                .filter(p -> p.getSavCaseId() == null)
                .toList();
        if (libres.isEmpty()) {
            // Une réparation sans pièce changée est une réparation quand même : rien à
            // rattacher, et rien à écrire non plus.
            return 0;
        }
        libres.forEach(p -> p.setSavCaseId(savCaseId));
        parts.saveAll(libres);
        return libres.size();
    }

    /** Combien de pièces un dossier réclame — pour que la ligne le dise sans les charger. */
    public long compterPourDossier(String savCaseId) {
        return parts.findBySavCaseId(savCaseId).size();
    }

    private static boolean estVide(String v) {
        return v == null || v.isBlank();
    }

    private static String traite(String v) {
        return estVide(v) ? null : v.trim();
    }

    private static String libelle(ItemPart p) {
        if (p.getReference() != null) {
            return p.getReference() + (estVide(p.getDesignation()) ? "" : " — " + p.getDesignation());
        }
        return p.getDesignation();
    }
}
