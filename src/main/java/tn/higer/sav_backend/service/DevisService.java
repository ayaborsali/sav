package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenanceDtos.RefusRequest;
import tn.higer.sav_backend.model.LineNature;
import tn.higer.sav_backend.model.MaintenanceDetail;
import tn.higer.sav_backend.model.MaintenanceItem;
import tn.higer.sav_backend.model.MaintenanceStatus;
import tn.higer.sav_backend.model.MaintenanceType;
import tn.higer.sav_backend.model.PieceRechange;
import tn.higer.sav_backend.repository.MaintenanceAttachmentRepository;
import tn.higer.sav_backend.repository.MaintenanceRepository;
import tn.higer.sav_backend.repository.PieceRechangeRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Les états du devis, et rien d'autre.
 *
 * Un devis n'est pas un travail : c'est une question posée au client (« est-ce que
 * tu acceptes de payer ça ? »). Tant que la réponse n'est pas écrite, l'atelier
 * n'a rien à exécuter, et l'argent n'existe pas encore. Le portail disait « validé »
 * sans savoir qui avait validé quoi, et le formulaire pouvait écrire n'importe quel
 * état à la main — un accord daté d'hier en tapant une date.
 *
 * Trois gestes, trois clics, trois dates posées par le serveur :
 *
 * 1. {@link #envoyer} — le papier part chez le client. Il était brouillon, il est
 *    « envoyé ». Dès ici le contenu se ferme : ce qui est parti est une parole, et
 *    changer un montant après l'envoi ne s'appelle pas corriger.
 * 2. {@link #accord} — le client a dit oui, et la preuve est jointe (le scan du
 *    devis qu'il a signé). Sans fichier, il n'y a pas d'accord : un « oui » que
 *    personne ne peut montrer se retourne contre l'atelier à la première facture
 *    contestée.
 * 3. {@link #refus} — le client a dit non, avec la raison écrite. Le palier du
 *    forfait reste dû (un devis ne décompte rien, même accepté), et le refus se
 *    relit des mois après sur le papier.
 *
 * Le devis est le seul papier de l'atelier dont l'état se décide ici : l'ordre et le
 * bon de livraison gardent les leurs pour les lots suivants.
 */
@Service
public class DevisService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MaintenanceRepository documents;
    private final MaintenanceAttachmentRepository piecesJointes;
    private final PieceRechangeRepository catalogue;
    private final AuditService audit;

    public DevisService(MaintenanceRepository documents, MaintenanceAttachmentRepository piecesJointes,
                        PieceRechangeRepository catalogue, AuditService audit) {
        this.documents = documents;
        this.piecesJointes = piecesJointes;
        this.catalogue = catalogue;
        this.audit = audit;
    }

    /** « Envoyer au client » : le devis n'est plus modifiable à partir d'ici. */
    @Transactional
    public MaintenanceItem envoyer(String id, Jwt jwt) {
        MaintenanceItem devis = devisDe(id);
        if (devis.getStatus() != MaintenanceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » n'est plus brouillon (état « "
                            + mot(devis) + " »" + dateDe(devis) + ") : il ne se renvoie pas au client.");
        }
        if (devis.getDetails().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » n'a aucune ligne : on n'envoie pas un papier vide"
                            + " à un client. Chiffrez au moins une ligne — main-d'œuvre, pièce ou déplacement.");
        }
        verifierLignes(devis);
        devis.setStatus(MaintenanceStatus.SENT);
        devis.setSentOn(LocalDate.now());
        documents.save(devis);
        audit.record("UPDATE", "MAINTENANCE", devis.getId(),
                "Devis « " + devis.getNumber() + " » envoyé au client le " + jour(devis.getSentOn()), jwt);
        return devis;
    }

    /**
     * « Enregistrer l'accord » : le client a signé. La preuve doit être déjà jointe au
     * document — c'est le seul endroit où l'atelier peut la relire quand le client ne
     * se souvient plus avoir dit oui.
     */
    @Transactional
    public MaintenanceItem accord(String id, Jwt jwt) {
        MaintenanceItem devis = devisDe(id);
        if (devis.getStatus() == MaintenanceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » n'a pas encore été envoyé au client : enregistrez"
                            + " d'abord l'envoi. Un accord porte sur un papier que le client a lu.");
        }
        if (devis.getStatus() == MaintenanceStatus.VALIDATED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » est déjà accepté" + dateDe(devis)
                            + " : un accord ne se pose pas deux fois.");
        }
        if (devis.getStatus() == MaintenanceStatus.REFUSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » a été refusé" + dateDe(devis) + " (motif : « "
                            + avisDeRefus(devis) + " ») : un refus ne se retourne pas en accord. Établissez un"
                            + " nouveau devis si le client change d'avis.");
        }
        if (devis.getStatus() == MaintenanceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » est annulé : il n'est plus chez le client.");
        }
        if (piecesJointes.countByMaintenanceItemId(devis.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucun papier joint au devis « " + devis.getNumber() + " » : l'accord se prouve par le"
                            + " devis que le client a signé et renvoyé (scan ou photo). Joignez-le, puis"
                            + " enregistrez l'accord.");
        }
        devis.setStatus(MaintenanceStatus.VALIDATED);
        devis.setAcceptedOn(LocalDate.now());
        documents.save(devis);
        audit.record("UPDATE", "MAINTENANCE", devis.getId(),
                "Devis « " + devis.getNumber() + " » accepté par le client le " + jour(devis.getAcceptedOn()), jwt);
        return devis;
    }

    /** « Enregistrer le refus » : le client ne signe pas, et la raison est écrite sur le papier. */
    @Transactional
    public MaintenanceItem refus(String id, RefusRequest request, Jwt jwt) {
        MaintenanceItem devis = devisDe(id);
        String motif = request == null || request.motif() == null ? "" : request.motif().trim();
        if (motif.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Écrivez le motif du refus du devis « " + devis.getNumber() + " » : trop cher, travail"
                            + " reporté, pièce attendue ailleurs. Sans raison écrite, la question reste posée"
                            + " au client et le palier du forfait reste dû sans que personne le sache.");
        }
        if (devis.getStatus() == MaintenanceStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » n'a jamais été envoyé au client : il ne peut pas"
                            + " l'avoir refusé. Enregistrez l'envoi d'abord.");
        }
        if (devis.getStatus() == MaintenanceStatus.REFUSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » est déjà refusé" + dateDe(devis) + " (motif : « "
                            + avisDeRefus(devis) + " ») : un refus ne se pose pas deux fois.");
        }
        if (devis.getStatus() == MaintenanceStatus.VALIDATED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » est accepté" + dateDe(devis)
                            + " : on n'enregistre pas un refus après un accord. Annulez le devis, ou laissez"
                            + " l'ordre fait.");
        }
        if (devis.getStatus() == MaintenanceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + devis.getNumber() + " » est annulé : il n'attend plus de réponse.");
        }
        devis.setStatus(MaintenanceStatus.REFUSED);
        devis.setRefusedOn(LocalDate.now());
        devis.setRefusalReason(motif);
        documents.save(devis);
        audit.record("UPDATE", "MAINTENANCE", devis.getId(),
                "Devis « " + devis.getNumber() + " » refusé par le client le " + jour(devis.getRefusedOn())
                        + " — motif : « " + motif + " »", jwt);
        return devis;
    }

    // ------------------------------------------------------------------ les gardes du formulaire

    /**
     * Un devis commence brouillon, toujours. Ce n'est pas une préférence d'écran :
     * si le formulaire peut écrire « accepté », un accord sans client et sans preuve
     * ouvre un ordre, et l'ordre coche un palier du forfait.
     */
    public void verifierCreation(MaintenanceType type, MaintenanceStatus statusDemandee) {
        if (type != MaintenanceType.DEVIS || statusDemandee == null || statusDemandee == MaintenanceStatus.DRAFT) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Un devis ne naît pas « " + statusDemandee.label().toLowerCase() + " » : il commence brouillon."
                        + " Enregistrez son envoi au client, puis son accord — les deux sont des clics sur le"
                        + " papier, pas des cases du formulaire.");
    }

    /**
     * Le verrou du contenu, et le verrou de l'état, sur un devis déjà engagé.
     *
     * Deux refus distincts parce qu'ils n'ont pas le même remède : modifier un devis
     * envoyé veut dire refaire le papier (le client a lu l'autre), et changer un état
     * au clavier veut dire écrire un accord que personne n'a donné.
     */
    public void verifierModification(MaintenanceItem avant, MaintenanceStatus statusDemande) {
        if (avant.getType() != MaintenanceType.DEVIS) {
            return;
        }
        if (statusDemande != null && statusDemande != avant.getStatus()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "L'état du devis « " + avant.getNumber() + " » ne se change pas au clavier (« "
                            + mot(avant) + " » demandé contre « " + statusDemande.label() + " ») : le devis"
                            + " avance par « Envoyer au client », « Enregistrer l'accord » ou « Enregistrer le"
                            + " refus », et le bon de commande naît du clic sur un devis accepté.");
        }
        MaintenanceStatus etat = avant.getStatus();
        if (etat == MaintenanceStatus.SENT || etat == MaintenanceStatus.VALIDATED
                || etat == MaintenanceStatus.REFUSED || etat == MaintenanceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le devis « " + avant.getNumber() + " » n'est plus modifiable : il est « " + mot(avant) + " »"
                            + dateDe(avant) + ". " + remedeSelonEtat(etat));
        }
    }

    private static String remedeSelonEtat(MaintenanceStatus etat) {
        return switch (etat) {
            case SENT -> "Ce qui est parti chez le client est une parole donnée : enregistrez son refus — ou"
                    + " son accord — puis établissez un nouveau devis si le prix change.";
            case VALIDATED -> "Le client a signé ce montant-là : le travail se lit sur l'ordre de travail, pas"
                    + " sur le devis.";
            case REFUSED -> "Le client a dit non : établissez un nouveau devis s'il change d'avis.";
            case CANCELLED -> "Le papier est annulé : rétablissez un devis.";
            default -> "";
        };
    }

    /**
     * Ce que le devis chiffre, ligne par ligne.
     *
     * Trois questions que le papier doit pouvoir répondre tout seul, sans que
     * l'atelier se souvienne de la saisie : qu'est-ce que c'est (le libellé), de quoi
     * s'agit-il (nature), et qui paye la pièce (fourni par). Le contrat de
     * maintenance ne couvre que la main-d'œuvre — tout le reste se devise, et une
     * ligne non naturée ne dit pas au client ce qu'il accepte.
     */
    public void verifierLignes(MaintenanceItem devis) {
        if (devis.getType() != MaintenanceType.DEVIS) {
            return;
        }
        int numeroDeLigne = 0;
        for (MaintenanceDetail ligne : devis.getDetails()) {
            numeroDeLigne++;
            String ou = "Ligne " + numeroDeLigne + " du devis « " + devis.getNumber() + " »";
            if (ligne.getLabel() == null || ligne.getLabel().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " : écrivez la désignation. Une ligne sans nom ne se retrouve ni sur la facture"
                                + " du client, ni dans le dossier du constructeur.");
            }
            if (ligne.getNature() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " (« " + ligne.getLabel().trim() + " ») : dites ce que vous chiffrez —"
                                + " main-d'œuvre, pièce ou déplacement. Le contrat ne couvre que la"
                                + " main-d'œuvre ; le reste se facture au client.");
            }
            if (ligne.getNature() == LineNature.PART && ligne.getSuppliedBy() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " (« " + ligne.getLabel().trim() + " », " + ligne.getNature().label().toLowerCase()
                                + ") : qui fournit cette pièce, Saphir ou le client ? C'est cette réponse qui"
                                + " décide si la ligne se facture.");
            }
            if (ligne.getNature() != LineNature.PART && ligne.getSuppliedBy() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " (« " + ligne.getLabel().trim() + " », " + ligne.getNature().label().toLowerCase()
                                + ") : un « fourni par » ne se met que sur une pièce. Laissez la case vide.");
            }
        }
    }

    // ------------------------------------------------------------------ la pièce du catalogue (V46)

    /**
     * Ce que le serveur re-vérifie sur une ligne qui cite une pièce du catalogue.
     *
     * L'écran pose la ligne en recopiant la référence, la désignation et le prix de
     * vente du catalogue — mais l'écran ne décide pas de l'argent. À l'écriture, le
     * serveur relit la pièce en base et refuse :
     *
     *  — une pièce qui n'existe pas (ou plus) : la ligne renverrait à un papier
     *    introuvable ;
     *  — une référence ou une désignation qui ne sont pas celles du catalogue : la
     *    ligne prétendrait citer la pièce A en facturant les mots de la pièce B ;
     *  — une nature autre que « Pièce » : le client ne saurait pas ce qu'il paye ;
     *  — une pièce sans prix de vente, ou une ligne à 0 : un devis ne porte jamais
     *    un prix inventé ni une pièce offerte par accident ;
     *  — un prix qui s'écarte du catalogue quand le compte n'a pas le droit aux
     *    chiffres commerciaux. Seuls ADMIN et GESTIONNAIRE_SAV ajustent un prix de
     *    pièce sur un devis ; le chef d'atelier garde la main sur le travail, pas sur
     *    le montant que le client signe.
     *
     * Le prix une fois accepté est figé sur la ligne : un catalogue qui change six
     * mois plus tard ne réécrit pas un devis accepté.
     */
    public void verifierLignesDeCatalogue(MaintenanceItem devis, boolean prixModifiable) {
        if (devis.getType() != MaintenanceType.DEVIS) {
            return;
        }
        int numeroDeLigne = 0;
        for (MaintenanceDetail ligne : devis.getDetails()) {
            numeroDeLigne++;
            String pieceId = ligne.getPieceId();
            if (pieceId == null || pieceId.isBlank()) {
                continue;
            }
            String ou = "Ligne " + numeroDeLigne + " du devis « " + devis.getNumber() + " »";
            PieceRechange piece = catalogue.findById(pieceId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ou + " : la pièce « " + pieceId + " » n'est pas au catalogue. Une ligne de"
                                    + " catalogue se choisit dans Gestion PR ; une pièce retirée ne se"
                                    + " propose plus."));
            if (!memeMot(ligne.getReference(), piece.getReference())
                    || !memeMot(ligne.getLabel(), piece.getDesignationFr())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " : la référence et la désignation doivent être celles du catalogue (« "
                                + piece.getReference() + " — " + piece.getDesignationFr() + " »). Une ligne"
                                + " liée à une pièce ne se retouche pas au clavier : retirez-la et"
                                + " choisissez la pièce à nouveau.");
            }
            if (ligne.getNature() != LineNature.PART) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " : une pièce du catalogue est une « Pièce ». La nature « "
                                + (ligne.getNature() == null
                                        ? "vide" : ligne.getNature().label().toLowerCase())
                                + " » ne dit pas ce que le client paye ici.");
            }
            if (piece.getPrixVente() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " : la pièce « " + piece.getReference() + " » n'a pas de prix de vente au"
                                + " catalogue. Chiffrez-la dans Gestion PR (Prix de vente), puis reposez la"
                                + " ligne : un devis ne porte ni 0 ni une case vide pour une pièce.");
            }
            if (ligne.getUnitPrice() == null || ligne.getUnitPrice() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " : le prix de la pièce « " + piece.getReference() + " » est vide ou nul. Le"
                                + " catalogue la chiffre à " + piece.getPrixVente().toPlainString() + " DT :"
                                + " reprenez ce prix, ou faites-le ajuster par un compte autorisé.");
            }
            if (!prixModifiable && !memeMontant(ligne.getUnitPrice(), piece.getPrixVente())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ou + " : le prix de la pièce « " + piece.getReference() + " » vient du catalogue ("
                                + piece.getPrixVente().toPlainString() + " DT). Seul un compte administrateur"
                                + " ou gestionnaire SAV peut l'ajuster sur un devis : demandez-lui la"
                                + " correction, ou gardez le prix du catalogue.");
            }
        }
    }

    private static boolean memeMot(String ecrit, String attendu) {
        return ecrit != null && attendu != null && ecrit.trim().equalsIgnoreCase(attendu.trim());
    }

    private static boolean memeMontant(Double ecrit, BigDecimal attendu) {
        return ecrit != null && attendu != null && BigDecimal.valueOf(ecrit).compareTo(attendu) == 0;
    }

    // ------------------------------------------------------------------ petits lecteurs

    private MaintenanceItem devisDe(String id) {
        MaintenanceItem document = documents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document de maintenance introuvable."));
        if (document.getType() != MaintenanceType.DEVIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "« " + document.getNumber() + " » n'est pas un devis (" + labelDuType(document)
                            + ") : l'accord du client ne se demande que sur un devis.");
        }
        return document;
    }

    private static String labelDuType(MaintenanceItem document) {
        return document.getType() == null ? "type inconnu" : document.getType().label();
    }

    private static String mot(MaintenanceItem devis) {
        return devis.getStatus() == null ? "sans état" : devis.getStatus().label();
    }

    /** La date qui va avec l'état, dans le message : « accepté le 03/11/2026 », pas « accepté ». */
    private static String dateDe(MaintenanceItem devis) {
        LocalDate jour = switch (devis.getStatus() == null ? MaintenanceStatus.DRAFT : devis.getStatus()) {
            case SENT -> devis.getSentOn();
            case VALIDATED -> devis.getAcceptedOn();
            case REFUSED -> devis.getRefusedOn();
            default -> null;
        };
        return jour == null ? "" : " le " + jour(jour);
    }

    private static String avisDeRefus(MaintenanceItem devis) {
        return devis.getRefusalReason() == null || devis.getRefusalReason().isBlank()
                ? "aucun motif écrit" : devis.getRefusalReason().trim();
    }

    private static String jour(LocalDate date) {
        return date == null ? "—" : date.format(JOUR);
    }
}
