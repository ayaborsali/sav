package tn.higer.sav_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.BonLivraisonDtos.BonDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.CompteurDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.EtatSignatureDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.IntervenantDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.PieceDto;
import tn.higer.sav_backend.dto.BonLivraisonDtos.TravailDto;
import tn.higer.sav_backend.dto.ItemPartDtos.ItemPartResponse;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceLineDto;
import tn.higer.sav_backend.dto.MaintenanceDtos.MaintenanceResponse;
import tn.higer.sav_backend.dto.OrdreDtos.EtatClotureDto;
import tn.higer.sav_backend.dto.OrdreDtos.FicheResponse;
import tn.higer.sav_backend.dto.OrdreDtos.JustificatifDto;
import tn.higer.sav_backend.dto.OrdreDtos.OrigineDto;
import tn.higer.sav_backend.model.Client;
import tn.higer.sav_backend.model.Vehicle;
import tn.higer.sav_backend.repository.ClientRepository;
import tn.higer.sav_backend.repository.VehicleRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Le plan JSON du papier de l'atelier, lu tel que la mise en page le recevra.
 *
 * <p>Le contrôle qui compte est celui de l'argent : un ordre (V38) et un bon de livraison (V39)
 * ne doivent porter <b>ni</b> clé {@code totals}, <b>ni</b> colonne de prix. Le script Python ne
 * connait pas cette règle — il rend ce qu'on lui envoie — donc si le JSON laissait passer un
 * montant, le papier imprimé en porterait un. C'est ici que ça se joue, et le test le vérifie
 * colonne par colonne.</p>
 *
 * <p>Le reste porte sur ce qui ne doit pas être inventé : une signature que le serveur ne connaît
 * pas laisse la case vide, une identité d'atelier non configurée s'imprime avec son libellé suivi
 * d'un tiret, et une nature de papier inconnue se refuse plutôt que de retomber sur « devis ».</p>
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceDocumentServiceTest {

    private static final String PAPIER_ID = "maint-1";

    @Mock
    private OrdreService ordre;

    @Mock
    private VehicleRepository vehicles;

    @Mock
    private ClientRepository clients;

    /** L'atelier tel que la configuration le donne aujourd'hui : nom et adresse, rien d'autre. */
    private MaintenanceDocumentService serviceAvec(FicheResponse fiche) {
        return service(fiche, "", "", 19);
    }

    private MaintenanceDocumentService service(FicheResponse fiche, String registre, String matricule,
                                               double tva) {
        lenient().when(ordre.fiche(PAPIER_ID)).thenReturn(fiche);
        lenient().when(vehicles.findById("veh-1")).thenReturn(Optional.of(bus()));
        lenient().when(clients.findById("cli-1")).thenReturn(Optional.of(client()));
        return new MaintenanceDocumentService(ordre, vehicles, clients,
                "Saphir Véhicules et Automobiles", "Route de Tunis, Ben Arous",
                registre, matricule, "", tva);
    }

    private static Vehicle bus() {
        Vehicle bus = new Vehicle();
        bus.setId("veh-1");
        bus.setVin("LTSD45H32D0000123");
        bus.setRegistration("123456 11");
        bus.setModel("Higer KLQ6129");
        bus.setClientId("cli-1");
        bus.setMileage(48_120L);
        return bus;
    }

    private static Client client() {
        Client client = new Client();
        client.setId("cli-1");
        client.setName("SNTRI");
        client.setCode("CL-01");
        return client;
    }

    /** Une ligne de devis : nature « pièce », fournie par Saphir, deux à 11,500 DT. */
    private static MaintenanceLineDto ligneDevis() {
        return new MaintenanceLineDto("F-10", "Filtre à huile", 2.0, 11.5, null,
                "part", "saphir", "Pièce", "Saphir", null, null, null);
    }

    /** La même ligne sur un ordre : le travail se coche et se chronomètre, il ne se chiffre pas. */
    private static MaintenanceLineDto ligneOrdre() {
        return new MaintenanceLineDto("OP-1", "Vidange moteur", 1.0, null, "0,9 h",
                "labor", "saphir", "Main-d'œuvre", "Saphir", true, 1.25, null);
    }

    /**
     * Les 31 cases de {@code MaintenanceResponse}, dans l'ordre du record. Les trois papiers
     * partagent la même table : ce qui change, c'est la queue — l'accord du devis, la clôture de
     * l'ordre, la réception du bon de livraison. Les trois groupes de la queue sont nommés pour
     * qu'un rang mal placé se lise dans l'appel.
     */
    private static MaintenanceResponse papier(String type, String numero, String status,
                                              Double ht, Double ttc, List<MaintenanceLineDto> lignes,
                                              LocalDate envoye, LocalDate accepte, LocalDate refuse,
                                              String motifRefus,
                                              LocalDate cloture, String anomalies, String raisonEcrite,
                                              String signataire, String qualite, LocalDate signe,
                                              String reserves, Boolean piecesRemises, Long compteur) {
        return new MaintenanceResponse(
                PAPIER_ID, type, numero, "veh-1", "cli-1",
                LocalDate.of(2026, 11, 2), status, ht, ttc, "ayaborsali",
                "Vidange et filtre", null, null, lignes,
                null, null, labelDe(type), labelDetat(status),
                envoye, accepte, refuse, motifRefus,
                cloture, anomalies, raisonEcrite,
                signataire, qualite, signe, reserves, piecesRemises, compteur);
    }

    private static MaintenanceResponse devisChiffre() {
        return papier("devis", "DV-2026-0007", "validated", 120.0, 142.8, List.of(ligneDevis()),
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static MaintenanceResponse ordreTermine() {
        return papier("operation", "OR-2026-0011", "completed", null, null, List.of(ligneOrdre()),
                null, null, null, null,
                LocalDate.of(2026, 11, 4), "surchauffe constatée", "photo après versée sur la sortie",
                null, null, null, null, null, null);
    }

    /** Le bon de livraison, signé ou non : les six cases du bas sont les siennes (V39). */
    private static MaintenanceResponse bon(boolean signe) {
        return papier("bl", "BL-2026-0094", signe ? "signed" : "delivered", null, null, List.of(),
                null, null, null, null,
                null, null, null,
                signe ? "Moez Ben Salah" : null,
                signe ? "Directeur d'exploitation" : null,
                signe ? LocalDate.of(2026, 11, 4) : null,
                signe ? null : "carrossière rayée à droite",
                signe, signe ? 48_500L : null);
    }

    private static String labelDe(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "devis" -> "Devis";
            case "operation" -> "Ordre de travail";
            default -> "Bon de livraison";
        };
    }

    private static String labelDetat(String status) {
        return switch (status) {
            case "signed" -> "Signé";
            case "validated" -> "Accepté";
            case "completed" -> "Terminé";
            case "delivered" -> "Livré";
            default -> status;
        };
    }

    // ------------------------------------------------------------------ les blocs du bon

    private static BonDto bonSigne() {
        return new BonDto(new TravailDto("OR-2026-0011", LocalDate.of(2026, 11, 4), "surchauffe constatée"),
                List.of(new PieceDto("F-10", "Filtre à huile", 2.0)),
                new IntervenantDto("sor-1", "DP-2026-010", "ayaborsali",
                        LocalDateTime.of(2026, 11, 4, 8, 0), LocalDateTime.of(2026, 11, 4, 10, 6)),
                new CompteurDto(48_120L, "Bon de livraison", "atelier@saphir.tn",
                        48_500L, "DP-2026-010", "ayaborsali", LocalDate.of(2026, 11, 4)),
                true, new EtatSignatureDto(false, List.of("ce bon de livraison est déjà signé"),
                        // Ce que le serveur répond RÉELLEMENT sur un bon signé (vu sur son
                        // BL-2026-0094) : « pret » retombe à faux, parce qu'un bon ne se signe
                        // pas deux fois. La feuille d'archive ne doit pas répéter ce refus.
                        "Signature refusée : ce bon de livraison est déjà signé le 04/11/2026 (par Moez Ben Salah)."));
    }

    private static BonDto bonNonSigne() {
        return new BonDto(new TravailDto("OR-2026-0011", LocalDate.of(2026, 11, 4), "surchauffe constatée"),
                List.of(new PieceDto("F-10", "Filtre à huile", 2.0)),
                new IntervenantDto("sor-1", "DP-2026-010", "ayaborsali",
                        LocalDateTime.of(2026, 11, 4, 8, 0), LocalDateTime.of(2026, 11, 4, 10, 6)),
                new CompteurDto(48_120L, "Bon de livraison", "atelier@saphir.tn",
                        48_500L, "DP-2026-010", "ayaborsali", LocalDate.of(2026, 11, 4)),
                false, new EtatSignatureDto(false, List.of("joindre le scan du papier signé"),
                        "Il manque le scan du papier signé."));
    }

    /** Une pièce posée, avec son sort : retournée chez le constructeur avec sa date. */
    private static ItemPartResponse piecePosee() {
        return new ItemPartResponse("ip-1", PAPIER_ID, "veh-1", null, "F-10", "Filtre à huile",
                2.0, LocalDate.of(2026, 11, 4), true, LocalDate.of(2026, 11, 5), "remboursement AG");
    }

    private static EtatClotureDto cloturePret() {
        return new EtatClotureDto(true, List.of(), List.of(),
                new JustificatifDto("sor-1", "DP-2026-010", true, false, false, "Preuve trouvée"),
                List.of(), "L'ordre peut être clôturé.");
    }

    // ------------------------------------------------------------------ l'argent

    @Test
    void leDevisEstLeSeulPapierQuiEnvoieSesTotaux() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(devisChiffre(), null, null,
                List.of(), List.of(), null, null)).payload(PAPIER_ID);

        assertEquals(List.of("Total HT", "120,000"), totaux(plan).get(0));
        assertEquals(List.of("TVA 19 %", "22,800"), totaux(plan).get(1));
        assertEquals(List.of("Total TTC", "142,800"), totaux(plan).get(2));

        // La ligne du bas doit se recompter sur la table : quantité × prix unitaire.
        assertEquals(List.of("Référence", "Désignation", "Nature", "Fourni par",
                "Quantité", "P.U. HT (DT)", "Montant HT (DT)"), colonnes(plan).get(0));
        assertEquals("23,000", lignesDeTable(plan, 0).get(0).get(6), "2 × 11,500");
        assertTrue(footer(plan).contains("dinars tunisiens"), footer(plan));
        assertTrue(plan.keySet().contains("totals"), "le devis chiffre");
    }

    /** Le taux se dit avec la valeur de la config, en fraction comme en pourcentage. */
    @Test
    void leTauxDeLaConfigSeDitEnPourcentage() {
        FicheResponse fiche = new FicheResponse(devisChiffre(), null, null, List.of(), List.of(), null, null);

        assertEquals("TVA 19 %", totaux(service(fiche, "", "", 0.19).payload(PAPIER_ID)).get(1).get(0));
        assertEquals("TVA 5,5 %", totaux(service(fiche, "", "", 5.5).payload(PAPIER_ID)).get(1).get(0));
    }

    @Test
    void lordreDeTravailNenvoieAucunMontant() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(ordreTermine(), null, null,
                List.of(), List.of(piecePosee()), cloturePret(), null)).payload(PAPIER_ID);

        assertFalse(plan.containsKey("totals"), "l'ordre n'a pas de totaux à imprimer");
        aucuneColonneDePrix(plan);
        assertTrue(badge(plan).contains("ne le facture pas"), badge(plan));
        assertTrue(footer(plan).contains("aucun montant"), footer(plan));

        // Ce que l'ordre imprime à la place du prix : le temps alloué et le temps réalisé.
        assertEquals(List.of("N°", "Tâches", "Temps alloué", "Réalisé", "Observations"),
                colonnes(plan).get(0));
        assertEquals("OP-1 — Vidange moteur", lignesDeTable(plan, 0).get(0).get(1));
        assertEquals("0,9 h", lignesDeTable(plan, 0).get(0).get(2));
        assertEquals("1,25", lignesDeTable(plan, 0).get(0).get(3));
        assertEquals("Fait", lignesDeTable(plan, 0).get(0).get(4));
        assertEquals(List.of("N°", "Désignation", "Référence", "Prévue", "Consommée"),
                colonnes(plan).get(1));
        assertEquals(List.of("N°", "Nom et prénom", "Fonction"), colonnes(plan).get(2));
    }

    /**
     * Les tâches et les pièces ne se mélangent plus : la main-d'œuvre se lit en tâches numérotées
     * avec son temps, les pièces dans leur tableau (prévue / consommée), et le déplacement dans
     * aucun des deux — il ne se « fait » pas.
     */
    @Test
    void lordreSepareLesTachesDesPiecesEtOublieLeDeplacement() {
        MaintenanceLineDto depose = new MaintenanceLineDto(null, "Dépose boîte de vitesses", 2.5, null, null,
                "labor", "saphir", "Main-d'œuvre", "Saphir", null, null, null);
        MaintenanceLineDto kit = new MaintenanceLineDto("SA-884", "Roulement pilote", 1.0, null, null,
                "part", "saphir", "Pièce", "Saphir", null, null, null);
        MaintenanceLineDto plaquette = new MaintenanceLineDto("TEST-010", "Plaquette de frein AV", 2.0, null, null,
                "part", "saphir", "Pièce", "Saphir", null, null, null);
        MaintenanceLineDto trajet = new MaintenanceLineDto(null, "Déplacement", 1.0, null, null,
                "travel", null, "Déplacement", null, null, null, null);
        MaintenanceResponse ordre = papier("operation", "OR-2026-0216", "draft", null, null,
                List.of(depose, kit, plaquette, trajet), null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        ItemPartResponse posee = new ItemPartResponse("ip-9", PAPIER_ID, "veh-1", null, "sa-884 ",
                "Roulement pilote", 1.0, LocalDate.of(2026, 11, 26), false, null, null);
        ItemPartResponse horsDevis = new ItemPartResponse("ip-10", PAPIER_ID, "veh-1", null, "JT-1",
                "Joint spi", 3.0, LocalDate.of(2026, 11, 26), false, null, null);

        Map<String, Object> plan = serviceAvec(new FicheResponse(ordre, null, null,
                List.of(), List.of(posee, horsDevis), null, null)).payload(PAPIER_ID);

        // Une seule tâche : la dépose. Le temps vient des heures chiffrées, le numéro est donné.
        assertEquals(1, lignesDeTable(plan, 0).size(), "ni les pièces ni le déplacement ne sont des tâches");
        assertEquals(List.of("1", "OP-1 — Dépose boîte de vitesses", "2,5 h", "—", "—"),
                lignesDeTable(plan, 0).get(0));

        // Les pièces : prévues d'abord, puis la pièce posée qui n'était pas au devis.
        assertEquals(3, lignesDeTable(plan, 1).size());
        assertEquals(List.of("1", "Roulement pilote", "SA-884", "1", "1"), lignesDeTable(plan, 1).get(0),
                "la référence se compare sans tenir compte de la casse ni des espaces");
        assertEquals(List.of("2", "Plaquette de frein AV", "TEST-010", "2", ""), lignesDeTable(plan, 1).get(1),
                "rien de posé : « consommée » reste vide, ce n'est pas un zéro");
        assertEquals(List.of("3", "Joint spi", "JT-1", "—", "3"), lignesDeTable(plan, 1).get(2),
                "une pièce posée hors devis s'imprime, avec « prévue » vide");

        // Le groupe : l'homme nommé, puis des lignes vides.
        assertEquals(List.of("1", "ayaborsali", "Technicien"), lignesDeTable(plan, 2).get(0));
        assertEquals(List.of("2", "", ""), lignesDeTable(plan, 2).get(1));
    }

    @Test
    void leBonDeLivraisonNenvoieAucunMontant() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(bon(true), null, null,
                List.of(), List.of(piecePosee()), null, bonSigne())).payload(PAPIER_ID);

        assertFalse(plan.containsKey("totals"), "le bon de livraison ne chiffre pas (V39)");
        aucuneColonneDePrix(plan);
        assertEquals(List.of("Référence", "Désignation", "Quantité"), colonnes(plan).get(0),
                "les pièces se rendent, elles ne se facturent pas ici");
        assertEquals("2", lignesDeTable(plan, 0).get(0).get(2), "deux pièces, pas « 2.0 »");
        assertTrue(footer(plan).contains("aucun montant"), footer(plan));
    }

    // ------------------------------------------------------------------ les signatures

    /** Le serveur connaît le signataire d'un BL signé : la case porte son nom. */
    @Test
    void unBonSigneECritLeNomDuSignataire() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(bon(true), null, null,
                List.of(), List.of(), null, bonSigne())).payload(PAPIER_ID);

        assertEquals("Moez Ben Salah", signatures(plan).get(0).get("name"));
        assertEquals("Le client — bus repris, lu et approuvé", signatures(plan).get(0).get("label"));
        assertTrue(badge(plan).contains("Bon de livraison signé"), badge(plan));
        assertEquals("État : Signé", String.valueOf(plan.get("status_label")));
        assertEquals("Moez Ben Salah", cases(plan).get("La réception du bus").get("Signataire"));
        assertEquals("oui", cases(plan).get("La réception du bus").get("Pièces remplacées remises"));
        assertFalse(badge(plan).contains("refusée"),
                "le bandeau d'un papier signé ne répète pas le refus du second clic : " + badge(plan));
        assertFalse(textes(plan).containsKey("Pour que la signature passe"),
                "un bon déjà signé n'a plus de liste d'attendus sur sa feuille : " + textes(plan).keySet());
    }

    /**
     * Un BL non signé laisse les deux cases vides : une signature préremplie par l'atelier n'est
     * pas une signature. Le refus du serveur (« il manque le scan ») se lit sur le papier.
     */
    @Test
    void unBonNonSigneLaisseLesCasesVidesEtDitCeQuiManque() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(bon(false), null, null,
                List.of(), List.of(), null, bonNonSigne())).payload(PAPIER_ID);

        assertEquals("", signatures(plan).get(0).get("name"));
        assertEquals("", signatures(plan).get(1).get("name"));
        assertTrue(badge(plan).contains("NON signé"), badge(plan));
        assertTrue(badge(plan).contains("Il manque le scan du papier signé"), "le refus du serveur se lit dès le bandeau : " + badge(plan));
        assertEquals("joindre le scan du papier signé",
                textes(plan).get("Pour que la signature passe"),
                "le papier écrit ce qui manque, dans l'ordre où l'atelier le corrige");
        assertEquals("carrossière rayée à droite",
                textes(plan).get("Réserves du client en reprenant le bus"));

        // Les cases que le client n'a pas remplies restent lisibles, avec un tiret.
        assertEquals("—", cases(plan).get("La réception du bus").get("Signataire"));
    }

    // ------------------------------------------------------------------ l'en-tête, sans rien inventer

    @Test
    @SuppressWarnings("unchecked")
    void uneIdentiteNonConfigureeSImprimeAvecSonLibelle() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(bon(true), null, null,
                List.of(), List.of(), null, bonSigne())).payload(PAPIER_ID);

        assertEquals(List.of("Route de Tunis, Ben Arous", "RC : —", "Matricule fiscal : —"),
                (List<String>) plan.get("brand_lines"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lidentiteConfigureeSImprime() {
        Map<String, Object> plan = service(new FicheResponse(bon(true), null, null,
                List.of(), List.of(), null, bonSigne()), "1234567A/RNM", "0012345/A/M/000", 19)
                        .payload(PAPIER_ID);

        assertEquals(List.of("Route de Tunis, Ben Arous", "RC : 1234567A/RNM",
                "Matricule fiscal : 0012345/A/M/000"), (List<String>) plan.get("brand_lines"));
    }

    /**
     * Une nature que le service ne connaît pas se refuse : retomber sur « devis » imprimait des
     * prix sur un papier dont personne ne sait ce qu'il rend.
     */
    @Test
    void uneNatureInconnueRefuseLimpression() {
        ResponseStatusException inconnue = assertThrows(ResponseStatusException.class, () -> serviceAvec(
                new FicheResponse(papier("garantie", "AG-2026-0001", "draft", null, null, List.of(),
                        null, null, null, null, null, null, null, null, null, null, null, null, null),
                        null, null, List.of(), List.of(), null, null)).payload(PAPIER_ID));
        assertEquals(HttpStatus.CONFLICT, inconnue.getStatusCode());
        assertTrue(inconnue.getReason().contains("refuse de deviner"), inconnue.getReason());

        ResponseStatusException sansNature = assertThrows(ResponseStatusException.class, () -> serviceAvec(
                new FicheResponse(papier(null, "XX-1", "draft", null, null, List.of(),
                        null, null, null, null, null, null, null, null, null, null, null, null, null),
                        null, null, List.of(), List.of(), null, null)).payload(PAPIER_ID));
        assertEquals(HttpStatus.CONFLICT, sansNature.getStatusCode());
        assertTrue(sansNature.getReason().contains("n'a pas de nature"), sansNature.getReason());
    }

    /** Le papier dit d'où il vient, et le compteur se relit des deux côtés de la case. */
    @Test
    void lesOriginesEtLeCompteurSeRelisentSurLePapier() {
        Map<String, Object> plan = serviceAvec(new FicheResponse(bon(true),
                new OrigineDto("OR-2026-0011", "Ordre de travail", "DV-2026-0007",
                        "mis-1", "MT-2026-0004", "sor-1", "DP-2026-010"),
                null, List.of(), List.of(), null, bonSigne())).payload(PAPIER_ID);

        Map<String, String> origine = cases(plan).get("D'où vient ce papier");
        assertEquals("Ordre de travail OR-2026-0011", origine.get("Né de"));
        assertEquals("DV-2026-0007", origine.get("Devis de la chaîne"));
        assertEquals("MT-2026-0004", origine.get("Demande (mission)"));
        assertEquals("48120 km", cases(plan).get("Le client et le bus").get("Compteur de la fiche du bus"));
        assertEquals("Bon de livraison (atelier@saphir.tn)",
                cases(plan).get("Le compteur, des deux côtés de la case").get("Écrit par"));
        assertEquals("04/11/2026 à 08h00", cases(plan).get("L'intervention sur site").get("Départ"));
    }

    // ------------------------------------------------------------------ lecture du plan

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("tables");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> colonnes(Map<String, Object> plan) {
        return tables(plan).stream().map(table -> (List<String>) table.get("columns")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> lignesDeTable(Map<String, Object> plan, int index) {
        return (List<List<String>>) tables(plan).get(index).get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> totaux(Map<String, Object> plan) {
        return (List<List<String>>) plan.get("totals");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> signatures(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("signatures");
    }

    /** Les cases relues par leur titre puis par leur libellé : deux fiches peuvent avoir le même mot. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> cases(Map<String, Object> plan) {
        Map<String, Map<String, String>> parCase = new LinkedHashMap<>();
        for (Map<String, Object> box : (List<Map<String, Object>>) plan.get("boxes")) {
            Map<String, String> lignes = new LinkedHashMap<>();
            for (List<String> row : (List<List<String>>) box.get("rows")) {
                lignes.put(row.get(0), row.get(1));
            }
            parCase.put(String.valueOf(box.get("heading")), lignes);
        }
        return parCase;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> textes(Map<String, Object> plan) {
        Map<String, String> parTitre = new LinkedHashMap<>();
        for (Map<String, Object> bloc : (List<Map<String, Object>>) plan.get("texts")) {
            parTitre.put(String.valueOf(bloc.get("heading")), String.valueOf(bloc.get("body")));
        }
        return parTitre;
    }

    private static String badge(Map<String, Object> plan) {
        return String.valueOf(plan.get("badge"));
    }

    private static String footer(Map<String, Object> plan) {
        return String.valueOf(plan.get("footer"));
    }

    /** La règle de l'argent, vérifiée sur chaque colonne de chaque table du plan. */
    private static void aucuneColonneDePrix(Map<String, Object> plan) {
        for (List<String> colonnes : colonnes(plan)) {
            for (String colonne : colonnes) {
                String mot = colonne.toUpperCase(Locale.ROOT);
                assertFalse(mot.contains("HT") || mot.contains("TTC") || mot.contains("P.U")
                        || mot.contains("DT") || mot.contains("PRIX") || mot.contains("MONTANT"),
                        "une colonne de prix sur un papier qui ne chiffre pas : " + colonne);
            }
        }
    }
}
