package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPapierJustifie;
import tn.higer.sav_backend.dto.SavCaseDtos.AgPieceJointe;
import tn.higer.sav_backend.dto.SavCaseDtos.AgSortieJointe;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.ClaimState;
import tn.higer.sav_backend.model.IncidentNature;
import tn.higer.sav_backend.model.SavCase;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.SavCaseRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * La feuille d'appel en garantie telle que le script de mise en page la reçoit.
 *
 * Quatre choses se protègent ici, et ce sont celles qui engagent la maison devant le
 * constructeur :
 *  - <b>les mots de la feuille sont ceux de l'écran</b> : une rubrique qui ne porte pas
 *    le même titre à l'écran et sur le papier oblige le guichet à traduire de tête,
 *    et c'est à la traduction de tête qu'un numéro se recopie dans la mauvaise case ;
 *  - <b>rien n'est calculé</b> : une case d'argent vide reste « non écrit », une case
 *    saisie se recopie telle quelle, et aucune ligne de la feuille ne totalise quoi
 *    que ce soit ;
 *  - <b>rien n'est inventé</b> : les quatre codes que seul Higer détient rendent un
 *    tiret, pas un numéro plausible ;
 *  - <b>les manques voyagent avec le papier</b> : la liste que l'atelier voit à
 *    l'écran est celle que la feuille imprime, sinon le PDF devient une façon de
 *    contourner la liste.
 */
@ExtendWith(MockitoExtension.class)
class AgPaperDocumentServiceTest {

    @Mock private AgPaperAssemblyService papier;
    @Mock private SavCaseRepository savCases;
    @Mock private VehicleRepository vehicles;
    @Mock private ClientRepository clients;

    private static final String DOSSIER = "sav-1";
    private static final String NUMERO = "SAV-2026-007";

    private AgPaperDocumentService service() {
        return new AgPaperDocumentService(papier, savCases, vehicles, clients,
                "Saphir Véhicules et Automobiles", "NZI Cité Ettaamir, 4000 Sousse");
    }

    private static Authentication chef() {
        return new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CHEF_ATELIER")));
    }

    // ------------------------------------------------------------------ fixtures

    private static SavCase dossier() {
        SavCase c = new SavCase();
        c.setId(DOSSIER);
        c.setCaseNumber(NUMERO);
        c.setTitle("Alternateur hors service sous garantie");
        c.setVehicleId("veh-1");
        c.setClientId("cli-1");
        c.setDescription("Le bus ne charge plus, tension mesurée à 24 V.");
        c.setResolution("Alternateur remplacé sous garantie constructeur.");
        c.setClaimState(ClaimState.A_DEPOSER);
        c.setClaimAmountClaimed(1200.0);
        c.setClaimAmountGranted(null);
        c.setWarrantyApprovalNumber("WA-2026-11");

        c.setAgWriterName("Mounir Bouazizi");
        c.setAgRepairOrderNumber("OR-2026-041");
        c.setAgRepairOrderDate(LocalDate.of(2026, 10, 12));
        c.setAgIncidentDate(LocalDate.of(2026, 10, 8));
        c.setAgKm(148320);
        c.setAgSecurityImpact(Boolean.FALSE);
        c.setAgIncidentType(IncidentNature.NOUVEAU);
        c.setAgConcernedPart("Recharge électrique");
        // Les quatre codes du constructeur : volontairement laissés vides.
        c.setAgCause("Défaut de fabrication du régulateur intégré.");
        c.setAgLaborOperationCode("EL-02");
        c.setAgLaborDescription("Remplacement de l'alternateur, contrôle de la tension de charge.");
        c.setAgAllocatedTime(2.1);
        c.setAgHourlyCost(null);
        c.setAgCausalPartReference("37300-10300");
        c.setAgDefectCode("2115U");
        return c;
    }

    private static Vehicle bus() {
        Vehicle v = new Vehicle();
        v.setId("veh-1");
        v.setVin("LTSD45H32D0000123");
        v.setRegistration("1234 567 11");
        v.setModel("XML6115J");
        v.setWarrantyEndDate(LocalDate.of(2027, 3, 31));
        return v;
    }

    private static AgPapierJustifie lecture() {
        return new AgPapierJustifie(NUMERO,
                List.of(new AgSortieJointe("DP-2026-007", "closed", true,
                        LocalDateTime.of(2026, 10, 8, 9, 30), LocalDateTime.of(2026, 10, 12, 16, 0),
                        148320L, 148405L, "2115U", "Alternateur", "OR-2026-041")),
                List.of(
                        new AgPieceJointe("37300-10300", "Alternateur 28V 120A", 1.0,
                                LocalDate.of(2026, 10, 12), "DP-2026-007", "OR-2026-041",
                                "montee", "Montée", false, null,
                                "Consignée au magasin, enlèvement à programmer"),
                        new AgPieceJointe("2115U", "Pompe à eau", 2.0,
                                LocalDate.of(2026, 10, 12), "DP-2026-007", null,
                                "montee_en_sortie", "Montée en sortie", null, null, null)),
                List.of("Le montant accordé par le constructeur reste vide."),
                List.of("La pièce 2115U a été montée pendant la sortie : le retour reste à prouver."),
                false);
    }

    private Map<String, Object> plan() {
        when(papier.relire(DOSSIER, chef())).thenReturn(lecture());
        when(savCases.findById(DOSSIER)).thenReturn(Optional.of(dossier()));
        when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus()));
        when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        return service().payload(DOSSIER, chef());
    }

    private static Client client() {
        Client c = new Client();
        c.setId("cli-1");
        c.setName("SNTRI Transport");
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> boxes(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("boxes");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> rowsOf(Map<String, Object> plan, String heading) {
        return boxes(plan).stream()
                .filter(b -> heading.equals(b.get("heading")))
                .findFirst()
                .map(b -> (List<List<String>>) b.get("rows"))
                .orElseThrow(() -> new AssertionError("rubrique absente : " + heading));
    }

    private static String valeur(Map<String, Object> plan, String heading, String label) {
        return rowsOf(plan, heading).stream()
                .filter(r -> r.get(0).equals(label))
                .findFirst()
                .map(r -> r.get(1))
                .orElseThrow(() -> new AssertionError("case absente : " + heading + " / " + label));
    }

    @SuppressWarnings("unchecked")
    private static String ligneDeTable(Map<String, Object> plan, int index, int colonne) {
        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        List<List<String>> rows = (List<List<String>>) tables.get(index).get("rows");
        return rows.get(0).get(colonne);
    }

    // ---------------------------------------------------------------- les mots de l'écran

    /**
     * Les rubriques du bloc AG, reprises mot pour mot. Le papier et l'écran doivent se
     * reconnaître : c'est la parité qui empêche un guichet de chercher une « Date
     * d'incident » là où la feuille écrit « Date incident ».
     */
    @Test
    void lesRubriquesDuBlocAGSeRetrouventMotPourMotSurLaFeuille() {
        Map<String, Object> plan = plan();
        List<String> titres = boxes(plan).stream().map(b -> (String) b.get("heading")).toList();

        assertTrue(titres.contains("Qui écrit le papier, et sur quel ordre"), titres.toString());
        assertTrue(titres.contains("Le bus chez Higer"), titres.toString());
        assertTrue(titres.contains("L'incident"), titres.toString());
        assertTrue(titres.contains("La pièce en cause et le défaut"), titres.toString());
        assertTrue(titres.contains("La main-d'œuvre, recopiée du papier"), titres.toString());
        assertEquals(NUMERO, plan.get("number"), "le document porte le numéro du dossier");
    }

    /**
     * Les trois cases longues de la rubrique « Ce que le client dit, ce que l'atelier en
     * tire » s'impriment sous leur libellé d'écran, en blocs de texte et non en
     * cellules : une cause tenue sur une ligne de fiche devient illisible, et le
     * guichet ne retrouve plus ce qu'il a écrit.
     */
    @Test
    @SuppressWarnings("unchecked")
    void lesCasesLonguesGardentLeurLibelleDEcran() {
        Map<String, Object> plan = plan();
        List<Map<String, Object>> texts = (List<Map<String, Object>>) plan.get("texts");
        List<String> titres = texts.stream().map(t -> (String) t.get("heading")).toList();

        assertTrue(titres.contains("Contexte client"), titres.toString());
        assertTrue(titres.contains("Cause"), titres.toString());
        assertTrue(titres.contains("Commentaires"), titres.toString());
        assertTrue(titres.contains("Description du travail"), titres.toString());
        assertTrue(texts.stream().anyMatch(t -> String.valueOf(t.get("body"))
                .contains("Défaut de fabrication du régulateur")), texts.toString());
    }

    // ---------------------------------------------------------------- l'argent

    /**
     * Le barème de Higer n'a jamais été fourni : la case saisie se recopie, la case
     * vide reste dite « non écrit », et la feuille ne totalise rien. Un « 0 » sur une
     * demande de remboursement se lit comme une décision, et un total calculé par le
     * portail engagerait quelqu'un qui ne l'a pas décidé.
     */
    @Test
    void unMontantSaisiSeRecopieEtUnMontantVideResteNonEcrit() {
        Map<String, Object> plan = plan();

        assertEquals("1 200", valeur(plan, "La demande", "Montant demandé"));
        assertEquals("non écrit", valeur(plan, "La demande", "Montant accordé"));
        assertEquals("non écrit", valeur(plan, "La main-d'œuvre, recopiée du papier",
                "Coût à l'heure (DT)"));
        assertEquals("2,1", valeur(plan, "La main-d'œuvre, recopiée du papier", "Temps alloué (h)"));

        String feuille = plan.toString();
        assertFalse(feuille.contains("Total"), "la feuille ne doit aucun total : " + feuille);
        assertFalse(feuille.contains("2 100"), "1 200 demandé + rien d'accordé ne fait pas un total");
    }

    /** Le temps alloué et les kilomètres se lisent en français : une virgule, pas un point. */
    @Test
    void lesNombresSImprimentEnFrancais() {
        Map<String, Object> plan = plan();

        assertEquals("148 320", valeur(plan, "L'incident", "Km au jour de l'incident"));
        assertTrue(ligneDeTable(plan, 0, 3).contains("148 320 km"), ligneDeTable(plan, 0, 3));
    }

    // ---------------------------------------------------------------- rien d'inventé

    /**
     * Un code que seul le constructeur détient reste vide : la feuille rend un tiret.
     * Un numéro plausible écrit par le portail sur un document officiel se retourne
     * contre son auteur, et le manquant est déjà dit dans la liste imprimée.
     */
    @Test
    void lesCodesQueSeulHigerDetientRendentUnTiret() {
        Map<String, Object> plan = plan();
        String rubrique = "Le bus chez Higer";

        assertEquals("—", valeur(plan, rubrique, "Importateur"));
        assertEquals("—", valeur(plan, rubrique, "N° distributeur"));
        assertEquals("—", valeur(plan, rubrique, "Unit No."));
        assertEquals("—", valeur(plan, rubrique, "CAM N°"));
        assertTrue(boxes(plan).toString().contains("Les quatre codes"),
                "la règle qui laisse ces cases vides doit se lire sur la feuille, sinon un "
                        + "lecteur croit qu'il peut les remplir lui-même");
    }

    /** Une case remplie se recopie, même à côté d'une case vide : la feuille n'efface rien. */
    @Test
    void ce_qui_est_ecrit_sur_le_dossier_parait_sur_la_feuille() {
        Map<String, Object> plan = plan();

        assertEquals("OR-2026-041", valeur(plan, "Qui écrit le papier, et sur quel ordre", "N° OR"));
        assertEquals("Mounir Bouazizi", valeur(plan, "Qui écrit le papier, et sur quel ordre",
                "Nom du rédacteur"));
        assertEquals("12/10/2026", valeur(plan, "Qui écrit le papier, et sur quel ordre", "Date d'OR"));
        assertEquals("Nouveau", valeur(plan, "L'incident", "Nature"));
        assertEquals("non", valeur(plan, "L'incident", "Sécurité en cause"));
        assertEquals("2115U", valeur(plan, "La pièce en cause et le défaut", "Code défaut"));
        assertEquals("LTSD45H32D0000123", valeur(plan, "Le bus", "Châssis (VIN)"));
        assertEquals("31/03/2027", valeur(plan, "Le bus", "Fin de garantie de la fiche"));
    }

    // ---------------------------------------------------------------- les justificatifs

    /**
     * La sortie jointe arrive sur la feuille avec ce que le constructeur regarde : son
     * numéro, son état en français, ses compteurs et le papier d'atelier né de la sortie.
     */
    @Test
    void laSortieJointeSeRecopieSansRienAjouter() {
        Map<String, Object> plan = plan();

        assertEquals("DP-2026-007", ligneDeTable(plan, 0, 0));
        assertEquals("clôturée", ligneDeTable(plan, 0, 1));
        assertTrue(ligneDeTable(plan, 0, 2).contains("08/10/2026"), ligneDeTable(plan, 0, 2));
        assertTrue(ligneDeTable(plan, 0, 3).contains("148 405 km"), ligneDeTable(plan, 0, 3));
        assertTrue(ligneDeTable(plan, 0, 4).contains("2115U"), ligneDeTable(plan, 0, 4));
        assertEquals("OR-2026-041", ligneDeTable(plan, 0, 5));
    }

    /**
     * Le sort de la pièce morte est la case que Higer vérifie avant de rembourser. Une
     * pièce montée en sortie sans trace de retour s'imprime comme non tracée : le
     * portail ne déclare pas repartie une pièce dont personne n'a écrit le départ.
     */
    @Test
    @SuppressWarnings("unchecked")
    void leSortDeLaPieceMorteSeLitCeQueLaBasePorte() {
        Map<String, Object> plan = plan();
        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        List<List<String>> pieces = (List<List<String>>) tables.get(1).get("rows");

        assertEquals(2, pieces.size());
        assertTrue(pieces.get(0).get(4).contains("non repartie"), pieces.get(0).toString());
        assertTrue(pieces.get(0).get(4).contains("enlèvement"), pieces.get(0).toString());
        assertEquals("retour non tracé", pieces.get(1).get(4));
        assertTrue(pieces.get(1).get(0).contains("2115U"), pieces.get(1).get(0).toString());
        assertEquals("2", pieces.get(1).get(1), "deux pièces s'écrivent 2, pas 2.0");
    }

    // ---------------------------------------------------------------- les manques

    /**
     * La liste que l'atelier lit à l'écran est celle que la feuille imprime, en tête de
     * page : un papier partiel qui partirait sans le dire serait un contournement du
     * contrôle, et le constructeur le reprocherait à Saphir, pas au portail.
     */
    @Test
    @SuppressWarnings("unchecked")
    void laListeDesManquesVoyageAvecLePapier() {
        Map<String, Object> plan = plan();

        assertEquals(lecture().manquants(), plan.get("manquants"));
        assertEquals(lecture().signes(), plan.get("signes"));
        assertTrue(((String) plan.get("manquants_heading")).contains("RESTE À ÉCRIRE"),
            "la feuille doit nommer sa liste en tête : " + plan.get("manquants_heading"));
        assertTrue(plan.toString().contains("barème") || plan.toString().contains("non écrit"),
                "la raison pour laquelle un montant manque se lit sur la feuille");
    }

    /**
     * Le refus de lecture n'est pas adouci pour faire plaisir au bouton : un dossier
     * qui n'est pas un appel en garantie ne reçoit pas de papier, et un compte sans
     * droit de regard sur le remboursement du constructeur non plus.
     */
    @Test
    void unRefusDeLectureSePropageAuDocument() {
        Authentication consultation = new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSULTATION")));
        when(papier.relire(DOSSIER, consultation)).thenThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "Le dossier d'appel en garantie et ses sorties ne se relisent"
                        + " qu'au guichet et à l'atelier."));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().payload(DOSSIER, consultation));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    /** Le pied de page dit la règle : le portail compte des lignes, il ne chiffre rien. */
    @Test
    void lePiedDePageDitQueRienNEstCalcule() {
        assertTrue(((String) plan().get("footer")).contains("aucun montant"),
                "la feuille doit le dire elle-même, sinon un lecteur croirait à une facture");
    }
}
