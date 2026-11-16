package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.ClaimRequest;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.SavCaseStatus;
import tn.higer.sav_backend.model.SavCaseType;
import tn.higer.sav_backend.repository.SavCaseRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La seconde vie d'un dossier garantie : ce que le constructeur en a fait.
 *
 * Ce qui est testé ici n'est pas une saisie confortable, c'est une preuve. Le
 * remboursement de Saphir par Higer dépend d'un papier où l'état, la date de la
 * décision et le montant accordé sont cohérents ; un claim « réglé » sans somme, un
 * refus sans motif ou une décision qui recule sont exactement ce qu'un contrôleur
 * constructeur annule. Et le portail ne calcule rien : le barème de remboursement
 * n'a jamais été fourni, donc un chiffre inventé serait un faux.
 */
@ExtendWith(MockitoExtension.class)
class WarrantyClaimServiceTest {

    @Mock private SavCaseRepository savCases;
    @Mock private AuditService audit;

    private static final String ID = "sav-2";

    private WarrantyClaimService service() {
        return new WarrantyClaimService(savCases, audit);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jeton").header("alg", "HS256")
                .subject("chef@saphir.tn").claim("email", "chef@saphir.tn")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    /** Un dossier garantie né d'une réclamation, à l'état d'appel en garantie donné. */
    private static SavCase claimDe(ClaimState etat) {
        SavCase c = new SavCase();
        c.setId(ID);
        c.setCaseNumber("SAV-WAR-2026-001");
        c.setType(SavCaseType.GARANTIES);
        c.setTitle("Claim garantie — Climatisation hors service");
        c.setStatus(SavCaseStatus.CLOSED);
        c.setClaimState(etat);
        return c;
    }

    private SavCase depot(SavCase claim) {
        when(savCases.findById(ID)).thenReturn(Optional.of(claim));
        return claim;
    }

    private ResponseStatusException refuse(ClaimRequest request, SavCase claim) {
        depot(claim);
        return assertThrows(ResponseStatusException.class,
                () -> service().maj(ID, request, jwt()));
    }

    @Test
    void seulUnDossierGarantiePeutPorterUnClaim() {
        SavCase visite = claimDe(ClaimState.DEPOSE);
        visite.setType(SavCaseType.VISITES);

        ResponseStatusException e = refuse(new ClaimRequest(ClaimState.ACCORDE, null,
                LocalDate.of(2026, 10, 20), null, null), visite);

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("n'est pas un dossier garantie"), e.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void unClaimRegleNeSeRedeposePas() {
        ResponseStatusException e = refuse(new ClaimRequest(ClaimState.DEPOSE, null, null, null, null),
                claimDe(ClaimState.REGLE));

        assertTrue(e.getReason().contains("Ce qui est réglé est réglé"), e.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void unAccordeSeContesteMaisUnDeposeNeSeReglePas() {
        // Accordé → contesté : le montant accordé est plus bas que demandé, c'est la suite normale.
        SavCase accorde = claimDe(ClaimState.ACCORDE);
        accorde.setClaimDecision("Prise en charge partielle");
        accorde.setClaimDecisionAt(LocalDate.of(2026, 10, 10));
        depot(accorde);
        service().maj(ID, new ClaimRequest(ClaimState.CONTESTE, "Montant divisé par deux sans raison",
                LocalDate.of(2026, 10, 22), null, null), jwt());
        assertEquals(ClaimState.CONTESTE, accorde.getClaimState());

        // Déposé → réglé : le constructeur n'a jamais rien accordé, rien n'est réglé.
        ResponseStatusException e = refuse(new ClaimRequest(ClaimState.REGLE, null,
                LocalDate.of(2026, 10, 22), null, 500.0), claimDe(ClaimState.DEPOSE));
        assertTrue(e.getReason().contains("ne revient pas de « Déposé »"), e.getReason());
    }

    @Test
    void unRefusSansMotifEcritEstRefuse() {
        ResponseStatusException e = refuse(new ClaimRequest(ClaimState.REFUSE, "   ",
                LocalDate.of(2026, 10, 20), null, null), claimDe(ClaimState.DEPOSE));

        assertTrue(e.getReason().contains("décision du constructeur"), e.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void uneDecisionSansDateEstRefusee() {
        // Sans date, impossible de prouver que le claim n'a pas dormi au-delà du délai constructeur.
        ResponseStatusException e = refuse(new ClaimRequest(ClaimState.ACCORDE, "Prise en charge accordée",
                null, null, 800.0), claimDe(ClaimState.DEPOSE));

        assertTrue(e.getReason().contains("a été décidé à une date"), e.getReason());
        verify(savCases, never()).save(any());
    }

    @Test
    void unRefusAvecMotifEtDatePasse() {
        SavCase depose = depot(claimDe(ClaimState.DEPOSE));

        service().maj(ID, new ClaimRequest(ClaimState.REFUSE, " Usure, hors garantie ",
                LocalDate.of(2026, 10, 20), null, null), jwt());

        assertEquals(ClaimState.REFUSE, depose.getClaimState());
        assertEquals("Usure, hors garantie", depose.getClaimDecision(), "la saisie est nettoyée, pas déformée");
        assertEquals(LocalDate.of(2026, 10, 20), depose.getClaimDecisionAt());
        verify(audit).record(eq("UPDATE"), eq("SAV_CASE"), eq(ID), contains("Refusé"), any());
    }

    @Test
    void unReglementSansMontantAccordeEstRefuse() {
        SavCase accorde = claimDe(ClaimState.ACCORDE);
        accorde.setClaimDecision("Prise en charge accordée");
        accorde.setClaimDecisionAt(LocalDate.of(2026, 10, 10));
        accorde.setClaimAmountClaimed(1200.0);

        ResponseStatusException e = refuse(new ClaimRequest(ClaimState.REGLE, null, null, null, null), accorde);

        assertTrue(e.getReason().contains("sans montant accordé"), e.getReason());
        assertEquals(ClaimState.ACCORDE, accorde.getClaimState(), "l'état n'a pas bougé");
        verify(savCases, never()).save(any());
    }

    @Test
    void unMontantNegatifEstRefuse() {
        ResponseStatusException e = refuse(new ClaimRequest(null, null, null, -1200.0, null),
                claimDe(ClaimState.DEPOSE));

        assertTrue(e.getReason().contains("négatif"), e.getReason());
    }

    @Test
    void lePortailNeCalculeAucunMontant() {
        SavCase aDeposer = depot(claimDe(ClaimState.A_DEPOSER));

        service().maj(ID, new ClaimRequest(ClaimState.DEPOSE, null, null, 1200.0, null), jwt());

        assertEquals(1200.0, aDeposer.getClaimAmountClaimed());
        assertNull(aDeposer.getClaimAmountGranted(),
                "rien n'est accordé tant que le constructeur n'a pas écrit de chiffre");
        assertEquals(ClaimState.DEPOSE, aDeposer.getClaimState());
    }

    @Test
    void unChampLaisseVideNeVidePasCeQuiEstDejaEcrit() {
        SavCase accorde = claimDe(ClaimState.ACCORDE);
        accorde.setClaimDecision("Prise en charge accordée");
        accorde.setClaimDecisionAt(LocalDate.of(2026, 10, 10));
        accorde.setClaimAmountClaimed(1200.0);
        accorde.setClaimAmountGranted(950.0);
        depot(accorde);

        // L'atelier n'a voulu que corriger la date de réponse du constructeur.
        service().maj(ID, new ClaimRequest(null, null, LocalDate.of(2026, 10, 12), null, null), jwt());

        assertEquals(1200.0, accorde.getClaimAmountClaimed());
        assertEquals(950.0, accorde.getClaimAmountGranted());
        assertEquals("Prise en charge accordée", accorde.getClaimDecision());
        assertEquals(LocalDate.of(2026, 10, 12), accorde.getClaimDecisionAt());
        assertEquals(ClaimState.ACCORDE, accorde.getClaimState(), "aucun état demandé : aucun état touché");
    }

    @Test
    void uneDecisionPeutEtreEriteAvantLEtat() {
        SavCase depose = depot(claimDe(ClaimState.DEPOSE));

        // La lettre du constructeur est arrivée, l'état sera posé après lecture.
        service().maj(ID, new ClaimRequest(null, "Refus pour cause de pièces hors barème", null, null, null), jwt());

        assertEquals(ClaimState.DEPOSE, depose.getClaimState());
        assertEquals("Refus pour cause de pièces hors barème", depose.getClaimDecision());
    }
}
