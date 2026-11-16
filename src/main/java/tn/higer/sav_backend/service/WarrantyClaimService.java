package tn.higer.sav_backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.ClaimRequest;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.repository.SavCaseRepository;

import java.time.LocalDate;

/**
 * La seconde vie d'un dossier garantie : ce que le constructeur en a fait.
 *
 * Elle est tenue à part du statut du dossier parce que les deux n'ont pas la même
 * main. « Approuvé » / « Rejeté » du dossier sont posés par un humain de chez
 * Saphir ; ici c'est le constructeur qui parle, et Saphir n'a pas le droit
 * d'écrire à sa place une décision qui engage son remboursement.
 *
 * Le serveur ne calcule aucun montant. Le barème de remboursement du constructeur
 * n'a jamais été fourni : une case vide se corrige, un chiffre inventé par le
 * portail part dans un papier officiel. Il se contente d'interdire l'absurde.
 */
@Service
public class WarrantyClaimService {

    private final SavCaseRepository savCases;
    private final AuditService audit;

    public WarrantyClaimService(SavCaseRepository savCases, AuditService audit) {
        this.savCases = savCases;
        this.audit = audit;
    }

    /**
     * Ce que l'atelier écrit après la réponse du constructeur.
     *
     * Quatre gardes, dans l'ordre où elles coûtent le plus cher :
     *  - seul un dossier de la famille GARANTIES porte un claim ;
     *  - l'état n'avance pas en arrière — un claim réglé ne se redépose pas ;
     *  - un refus ou une contestation s'écrit avec son motif, sinon le papier ne
     *    dit plus pourquoi l'argent n'est pas venu ;
     *  - un montant négatif, ou un règlement sans montant accordé, est refusé :
     *    ce sont les deux façons de faire mentir une somme devant un comptable.
     */
    @Transactional
    public SavCase maj(String caseId, ClaimRequest request, Jwt jwt) {
        SavCase c = savCases.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier SAV introuvable."));
        if (c.getType() != SavCaseType.GARANTIES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le dossier " + c.getCaseNumber() + " n'est pas un dossier garantie : il n'a rien à"
                            + " réclamer au constructeur. Ouvrez le claim depuis la réclamation du client.");
        }

        ClaimState voulu = request.state();
        boolean changementEtat = voulu != null && voulu != c.getClaimState();
        if (changementEtat) {
            if (c.getClaimState() != null && !voulu.avanceDepuis(c.getClaimState())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "L'appel en garantie de " + c.getCaseNumber() + " ne revient pas de « "
                                + c.getClaimState().label() + " » à « " + voulu.label() + " »."
                                + " Ce qui est réglé est réglé.");
            }
            boolean decisionEcrite = request.decision() != null && !request.decision().isBlank();
            if (voulu.exigeUnMotif() && !decisionEcrite && (c.getClaimDecision() == null
                    || c.getClaimDecision().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "« " + voulu.label() + " » est une décision du constructeur : écrivez-la."
                                + " Un refus sans motif ne se conteste pas et ne s'explique pas six mois plus tard.");
            }
            if (veutUneDate(voulu) && request.decisionAt() == null && c.getClaimDecisionAt() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "« " + voulu.label() + " » a été décidé à une date : indiquez-la. C'est elle qui"
                                + " prouve que le claim n'a pas dormi au-delà du délai du constructeur.");
            }
        }

        // Les montants sont lus avant d'être écrits : un montant négatif doit refuser
        // le geste entier, pas laisser l'état changé à mi-chemin.
        Double demande = montant(request.amountClaimed(), c.getClaimAmountClaimed(), "demandé");
        Double accorde = montant(request.amountGranted(), c.getClaimAmountGranted(), "accordé");
        ClaimState etatApres = changementEtat ? voulu : c.getClaimState();
        if (etatApres == ClaimState.REGLE && (accorde == null || accorde <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un appel réglé sans montant accordé ne règle rien : indiquez ce que le constructeur"
                            + " a alloué (ou l'avoir pris), sinon la ligne reste une intention.");
        }

        if (changementEtat) {
            c.setClaimState(voulu);
        }
        if (request.decision() != null && !request.decision().isBlank()) {
            c.setClaimDecision(request.decision().trim());
        }
        if (request.decisionAt() != null) {
            c.setClaimDecisionAt(request.decisionAt());
        }
        c.setClaimAmountClaimed(demande);
        c.setClaimAmountGranted(accorde);

        c.setUpdatedAt(LocalDate.now());
        savCases.save(c);
        audit.record("UPDATE", "SAV_CASE", c.getId(),
                "Appel en garantie de " + c.getCaseNumber() + " : "
                        + (c.getClaimState() == null ? "état laissé en attente" : c.getClaimState().label()), jwt);
        return c;
    }

    /** Les quatre états où le constructeur a parlé : ils portent une date. */
    private static boolean veutUneDate(ClaimState etat) {
        return etat == ClaimState.ACCORDE || etat == ClaimState.REFUSE
                || etat == ClaimState.CONTESTE || etat == ClaimState.REGLE;
    }

    /** Null = on ne touche pas à ce qui est déjà écrit ; négatif = refusé. */
    private static Double montant(Double recu, Double dejaEcrit, String nom) {
        if (recu == null) {
            return dejaEcrit;
        }
        if (recu < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le montant " + nom + " ne peut pas être négatif : une soustraction à faire"
                            + " s'écrit dans le dossier, pas dans un montant de claim.");
        }
        return recu;
    }
}
