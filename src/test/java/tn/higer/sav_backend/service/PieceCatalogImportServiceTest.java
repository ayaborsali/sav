package tn.higer.sav_backend.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.PieceRechangeDtos.Issue;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportCommit;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportLine;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportPreview;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceImportSummary;
import tn.higer.sav_backend.dto.PieceRechangeDtos.PieceRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * La lecture d'un classeur de pièces, sans base de données.
 *
 * Le principe tenu ici, le même que pour l'import des forfaits : un fichier ne
 * remplace jamais la validation humaine. Ces tests vérifient que l'aperçu
 * <b>ne propose</b> que ce que le classeur dit, qu'il nomme la ligne et la raison
 * de chaque refus, et qu'un prix calculé ne se lit jamais dans le fichier — les
 * deux prix sortent du calcul serveur, sinon deux postes s'écrivent l'un l'autre
 * sous la même référence.
 *
 * Le catalogue porte <b>deux</b> coefficients — revient et vente — et ce sont des
 * taux, pas des résultats : leurs colonnes se lisent. Une colonne de <i>prix</i> de
 * vente, elle, est signalée et ignorée comme celle du prix de revient : un prix
 * calculé ailleurs ne serait plus le chiffre que le serveur sait recalculer.
 *
 * La famille (le « type PR ») se lit aussi, mais autrement : elle est
 * <b>obligatoire</b> et fermée à sept mots. Une case vide ou un mot hors liste
 * refuse la ligne, et le repli sur « Divers » n'existe nulle part.
 *
 * Ce qui est vérifié aussi, en sens inverse : le tolérant dont un document
 * constructeur a besoin. Un tableau recopié à la main écrit « designation » un jour
 * et « article » le lendemain, « 86,500 » avec une virgule, et un csv exporté
 * d'Excel français arrive en point-virgule et en Latin-1. Un fichier qui ne se lit
 * pas pour ces raisons-là est une pièce manquante au catalogue, donc un devis
 * chiffré à la main.
 */
class PieceCatalogImportServiceTest {

    private final PieceCatalogService catalogue = Mockito.mock(PieceCatalogService.class);
    private final PieceCatalogImportService importService = new PieceCatalogImportService(catalogue);

    /** Les entêtes du modèle, dans l'ordre où le classeur les écrit. */
    private static final String[] ENTETE = {
            "Référence", "Désignation FR", "Désignation EN", "Famille", "Prix Ex Works", "Devise",
            "Fournisseur", "Coefficient de revient", "Coefficient de vente"};

    private static Workbook classeur(String... lignes) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, String.join("|", ENTETE));
        for (int r = 0; r < lignes.length; r++) {
            row(sheet, r + 1, lignes[r]);
        }
        return workbook;
    }

    /** Une ligne au format du modèle : neuf cases séparées par « | », une case vide se dit « ». */
    private static void row(Sheet sheet, int index, String valeurs) {
        Row row = sheet.createRow(index);
        String[] cases = valeurs.split("\\|", -1);
        for (int c = 0; c < cases.length; c++) {
            if (!cases[c].isEmpty()) {
                row.createCell(c).setCellValue(cases[c]);
            }
        }
    }

    private static MockMultipartFile fichier(Workbook workbook) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return new MockMultipartFile("file", "pieces.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private PieceImportPreview apercu(Workbook workbook, String coefficientRevient) {
        return apercu(workbook, coefficientRevient, null);
    }

    /** Les deux valeurs saisies avant l'import, quand le classeur ne porte pas les colonnes. */
    private PieceImportPreview apercu(Workbook workbook, String coefficientRevient, String coefficientVente) {
        return importService.preview(fichier(workbook), coefficientRevient, coefficientVente);
    }

    @Test
    void unClasseurConformeDonneDeuxLignesEtAucunRefus() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|EUR|Higer Parts|1,7200|1,5000",
                "2115U-001|Plaquette de frein AV|Front brake pad|Freinage|124,380|EUR|Higer Parts|1,68|1,4000"),
                null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(2, apercu.lignesValides());
        assertEquals(0, apercu.misesAJourPrevues(), "Aucune de ces références n'est au catalogue.");

        PieceImportLine premiere = apercu.lignes().get(0);
        // Le numéro de ligne est celui qu'Excel affiche : l'atelier corrige son
        // classeur, il ne compte pas à partir de zéro.
        assertEquals(2, premiere.row());
        assertEquals("9.8001.045", premiere.reference());
        assertEquals("Filtre à gasoil", premiere.designationFr());
        assertEquals("Fuel filter", premiere.designationEn());
        // La famille arrive sous le nom que le service relit, pas sous l'orthographe
        // du classeur : deux fichiers ne peuvent pas écrire deux mots pour un range.
        assertEquals("MECANIQUE", premiere.famille());
        assertEquals(new BigDecimal("86.500"), premiere.prixExWorks());
        assertEquals(new BigDecimal("1.7200"), premiere.coefficientRevient());
        assertEquals("EUR", premiere.devise());
        assertEquals("Higer Parts", premiere.fournisseur());
        // Les deux coefficients viennent de leurs colonnes : ce sont des taux, et le
        // serveur s'en sert pour recalculer les deux prix à l'enregistrement.
        assertEquals(new BigDecimal("1.5000"), premiere.coefficientVente());

        // Un coefficient écrit « 1,68 » au lieu de « 1,6800 » reste le même nombre.
        assertEquals(new BigDecimal("1.68"), apercu.lignes().get(1).coefficientRevient());
        assertEquals(new BigDecimal("1.4000"), apercu.lignes().get(1).coefficientVente());
        assertEquals("FREINAGE", apercu.lignes().get(1).famille());
    }

    @Test
    void uneDeviseVidePrendLEuroParDefaut() {
        // Le classeur Higer n'écrit pas toujours la colonne Devise : une pièce sans
        // étiquette ne doit pas rester sans devise.
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500||Higer Parts|1,72|1,50"), null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals("EUR", apercu.lignes().get(0).devise());
    }

    @Test
    void uneDeviseEnMinusculesEstLueEnMajuscules() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|usd|Higer Parts|1,72|1,50"), null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals("USD", apercu.lignes().get(0).devise());
    }

    @Test
    void uneDeviseInconnueRefuseLaLigneAuLieuDeLaRelabeler() {
        // « GBP » ramené à EUR en silence ferait changer le prix d'un devis de monnaie.
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|GBP|Higer Parts|1,72|1,50"), null);

        assertFalse(apercu.canImporter());
        assertEquals(0, apercu.lignesValides());
        Issue refus = apercu.erreurs().stream()
                .filter(issue -> "Devise".equals(issue.column()))
                .findFirst().orElseThrow();
        assertEquals(2, refus.row());
        assertTrue(refus.message().contains("EUR"), refus.message());
    }

    @Test
    void leCoefficientSaisiAvantImportRemplitLesLignesSansColonne() {
        // Le cas normal d'un document constructeur : un prix Ex Works et rien d'autre.
        // Les deux coefficients saisis avant l'aperçu tiennent alors pour toutes les
        // lignes — l'un porte le transport, l'autre la marge.
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works");
        row(sheet, 1, "2165J-002|Capteur de pression|Électricité|36,200");
        row(sheet, 2, "10107-11|Joint de culasse|Mécanique|18,750");

        PieceImportPreview apercu = apercu(workbook, "1,7200", "1,5000");

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(new BigDecimal("1.7200"), apercu.lignes().get(0).coefficientRevient());
        assertEquals(new BigDecimal("1.5000"), apercu.lignes().get(0).coefficientVente());
        assertEquals(new BigDecimal("1.7200"), apercu.lignes().get(1).coefficientRevient());
        assertEquals(new BigDecimal("1.5000"), apercu.lignes().get(1).coefficientVente());
    }

    @Test
    void sansColonneEtSansValeurSaisieLeCoefficientRefuseLaLigneEnLaNommant() {
        // Rien n'est inventé : un coefficient posé par le serveur se facture au
        // client sur le devis d'après. Le coefficient de vente est fourni pour que
        // le refus observé soit bien celui du revient, la première case du modèle.
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works");
        row(sheet, 1, "2165J-002|Capteur de pression|Électricité|36,200");

        PieceImportPreview apercu = apercu(workbook, null, "1,5000");

        assertFalse(apercu.canImporter());
        assertEquals(0, apercu.lignesValides());
        Issue refus = apercu.erreurs().get(0);
        assertEquals(2, refus.row());
        assertEquals("Coefficient de revient", refus.column());
        assertTrue(refus.message().contains("saisie avant l'import"), refus.message());
    }

    @Test
    void uneReferenceEnDoubleDansLeFichierGardeLaDerniereLigneEtLeDit() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|EUR|Higer Parts|1,72|1,50",
                "2115U-001|Plaquette de frein AV|Front brake pad|Freinage|124,380|EUR|Higer Parts|1,68|1,50",
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|91,000|EUR|Higer Parts|1,72|1,50"),
                null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(2, apercu.lignesValides(), "La première des deux lignes identiques est abandonnée.");
        assertEquals(new BigDecimal("91.000"),
                apercu.lignes().stream().filter(l -> l.reference().equals("9.8001.045"))
                        .findFirst().orElseThrow().prixExWorks());

        Issue signal = apercu.avertissements().stream()
                .filter(issue -> issue.message().contains("revient ligne"))
                .findFirst().orElseThrow();
        assertEquals(2, signal.row(), "C'est la ligne abandonnée qui est nommée.");
        assertTrue(signal.message().contains("ligne 4"), signal.message());
    }

    @Test
    void uneLigneSansReferenceEstRefuseeParceQueRienNeLaRetrouve() {
        PieceImportPreview apercu = apercu(classeur(
                        "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|EUR|Higer Parts|1,72|1,50",
                        "|Courroie sans numéro||Mécanique|12,00|||||")
                , null);

        assertFalse(apercu.canImporter());
        assertEquals(1, apercu.lignesValides());
        Issue refus = apercu.erreurs().stream()
                .filter(issue -> "Référence".equals(issue.column()))
                .findFirst().orElseThrow();
        assertEquals(3, refus.row());
        assertTrue(refus.message().contains("ni ajoutée ni retrouvée"), refus.message());
    }

    @Test
    void unPrixQuiNeSeLitPasEstRefuseAvecCeQuiEtaitEcrit() {
        PieceImportPreview apercu = apercu(classeur("9.8001.045|Filtre à gasoil||Mécanique|voir facture|EUR||1,72|1,50"), null);

        assertFalse(apercu.canImporter());
        Issue refus = apercu.erreurs().get(0);
        assertEquals(2, refus.row());
        assertTrue(refus.message().contains("voir facture"), refus.message());
    }

    @Test
    void leMillimeEstLaSeulePrecisionAdmiseAuPrix() {
        PieceImportPreview apercu = apercu(classeur("9.8001.045|Filtre à gasoil||Mécanique|86,5001||||1,72|1,50"), null);

        assertFalse(apercu.canImporter());
        assertTrue(apercu.erreurs().get(0).message().contains("3"), apercu.erreurs().get(0).message());
    }

    @Test
    void unPrixNegatifEstRefuse() {
        PieceImportPreview apercu = apercu(classeur("9.8001.045|Filtre à gasoil||Mécanique|-86,500|EUR|Higer|1,72|1,50"), null);

        assertFalse(apercu.canImporter());
        assertTrue(apercu.erreurs().get(0).message().contains("positif"), apercu.erreurs().get(0).message());
    }

    @Test
    void uneColonneDePrixCalculeEstSignaleeEtJamaisImportee() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works|Prix de revient HT|Coefficient de revient");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|Mécanique|86,500|1 999,00|1,72");

        PieceImportPreview apercu = apercu(workbook, null, "1,5000");

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        // Le prix du fichier n'a pas glissé dans la case du prix fournisseur.
        assertEquals(new BigDecimal("86.500"), apercu.lignes().get(0).prixExWorks());
        Issue signal = apercu.avertissements().stream()
                .filter(issue -> "Prix de revient HT".equals(issue.column()))
                .findFirst().orElseThrow();
        assertTrue(signal.message().contains("calcul serveur"), signal.message());
    }

    /**
     * Le coefficient de vente se lit : c'est un taux, et le prix se déduit du
     * revient. C'est la différence avec une colonne de prix de vente, qui, elle,
     * est un résultat et se signale.
     */
    @Test
    void uneColonneDeCoefficientDeVenteSeLitEtDonneLeTaux() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works|Coefficient de revient|Coefficient de vente");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|Mécanique|86,500|1,72|1,3500");

        PieceImportPreview apercu = apercu(workbook, null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(new BigDecimal("1.3500"), apercu.lignes().get(0).coefficientVente());
        // Le coefficient de revient garde sa case : les deux taux ne se mélangent pas.
        assertEquals(new BigDecimal("1.72"), apercu.lignes().get(0).coefficientRevient());
        // Le prix fournisseur n'a pas été déplacé par la colonne de vente.
        assertEquals(new BigDecimal("86.500"), apercu.lignes().get(0).prixExWorks());
        assertTrue(apercu.avertissements().isEmpty(), () -> "Faux signal : " + apercu.avertissements());
    }

    /**
     * Une case qui n'est pas un nombre part en erreur, et la ligne avec elle. Un
     * coefficient illisible ne devient pas « absent » : la pièce n'aurait alors
     * aucun prix de vente, et l'atelier croirait avoir importé sa marge.
     */
    @Test
    void unCoefficientDeVenteQuiNeSeLitPasRefuseLaLigneEnLaNommant() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works|Coefficient de revient|Coefficient de vente");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|Mécanique|86,500|1,72|gratuit");

        PieceImportPreview apercu = apercu(workbook, null);

        assertFalse(apercu.canImporter());
        assertEquals(0, apercu.lignesValides());
        Issue refus = apercu.erreurs().get(0);
        assertEquals("Coefficient de vente", refus.column());
        assertTrue(refus.message().contains("1,7200"), refus.message());
    }

    /** Un coefficient nul est refusé : il ne donne pas une pièce gratuite, il ne donne aucun prix. */
    @Test
    void unCoefficientDeVenteNulEstRefusePlutotQuUnPrixGratuit() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works|Coefficient de revient|Coefficient de vente");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|Mécanique|86,500|1,72|0,0000");

        PieceImportPreview apercu = apercu(workbook, null);

        assertFalse(apercu.canImporter());
        assertTrue(apercu.erreurs().get(0).message().contains("positif"), apercu.erreurs().get(0).message());
    }

    /**
     * Un prix de vente venu d'un classeur est un résultat, pas un taux : il est
     * signalé et ignoré, exactement comme le prix de revient. Le classeur reste
     * lisible — sa colonne de vente ne rentre simplement pas.
     */
    @Test
    void uneColonneDePrixDeVenteEstSignaleeEtIgnoree() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works|Coefficient de revient|Prix de vente (DT)");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|Mécanique|86,500|1,72|348,750");

        PieceImportPreview apercu = apercu(workbook, null, "1,5000");

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(new BigDecimal("1.5000"), apercu.lignes().get(0).coefficientVente(),
                "C'est la valeur saisie avant l'import qui tient : le prix du fichier ne s'invente pas un taux.");
        Issue signal = apercu.avertissements().stream()
                .filter(issue -> "Prix de vente (DT)".equals(issue.column()))
                .findFirst().orElseThrow();
        assertTrue(signal.message().contains("calcul serveur"), signal.message());
    }

    @Test
    void uneColonneDeCoefficientDeVenteNeDevientJamaisLeCoefficientDeRevient() {
        // Deux taux voisins dans le classeur d'un bureau : chacun reste dans sa case.
        // Reprendre « 1,35 » comme coefficient de revient écrirait un prix de la
        // pièce qui n'a jamais été décidé, sous la même référence.
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Famille|Prix Ex Works|Coefficient de vente");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|Mécanique|86,500|1,35");

        PieceImportPreview apercu = apercu(workbook, "1,72");

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(new BigDecimal("1.72"), apercu.lignes().get(0).coefficientRevient(),
                "Le revient tient sa valeur saisie avant l'import, jamais la colonne de vente.");
        assertEquals(new BigDecimal("1.35"), apercu.lignes().get(0).coefficientVente(),
                "La colonne de vente reste la colonne de vente.");
    }

    @Test
    void lesColonnesDuModeleNeSontPasSignaleesCommeIgnorees() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|EUR|Higer|1,72|1,50"), null);

        assertTrue(apercu.avertissements().isEmpty(), () -> "Faux signal : " + apercu.avertissements());
    }

    // ------------------------------------------------------------------ la famille

    /**
     * « mecanique », « FREINAGE » et « Boîte de vitesse » disent la même chose que
     * leurs voisins accentués : un document constructeur écrit en majuscules et un
     * tableur tapé à la main doivent rentrer tels quels.
     */
    @Test
    void uneFamilleSansAccentNiCasseSeLitCommeLesAutres() {
        PieceImportPreview apercu = apercu(classeur(
                "A1|Filtre||mecanique|10,000|||1,72|1,50",
                "A2|Plaquette||FREINAGE|10,000|||1,72|1,50",
                "A3|Boîte||Boîte de vitesse|10,000|||1,72|1,50",
                "A4|Pont||pont arriere|10,000|||1,72|1,50"), null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(List.of("MECANIQUE", "FREINAGE", "BOITE_VITESSE", "PONT_ARRIERE"),
                apercu.lignes().stream().map(PieceImportLine::famille).toList());
    }

    /** Le mot anglais d'un document Higer range la pièce comme le mot français. */
    @Test
    void uneFamilleEcriteEnAnglaisSeLit() {
        PieceImportPreview apercu = apercu(classeur(
                "A1|Plaquette AV||Brake|10,000|||1,72|1,50",
                "A2|Couche de boite||gearbox|10,000|||1,72|1,50",
                "A3|Pignon de pont||Rear axle|10,000|||1,72|1,50"), null);

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals(List.of("FREINAGE", "BOITE_VITESSE", "PONT_ARRIERE"),
                apercu.lignes().stream().map(PieceImportLine::famille).toList());
    }

    /**
     * La colonne « Famille » peut s'appeler « Type » dans un classeur qui vient
     * d'ailleurs : c'est le mot que l'atelier emploie pour le même range.
     */
    @Test
    void uneColonneTypeSeLitCommeLaFamille() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Type|Prix Ex Works|Coefficient de revient");
        row(sheet, 1, "8110-002|Rétroviseur extérieur droit|Divers|22,000|1,72");

        PieceImportPreview apercu = apercu(workbook, null, "1,5000");

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals("DIVERS", apercu.lignes().get(0).famille());
    }

    /**
     * Le cœur du lot : un mot que le catalogue ne connaît pas refuse la ligne, il ne
     * se range pas « Divers » pour faire passer le classeur. « Divers » est un choix
     * de l'atelier ; en mettre un par défaut enlèverait la pièce de toutes les listes
     * filtrées sur sa vraie famille.
     */
    @Test
    void uneFamilleHorsListeRefuseLaLigneEnNommantLesSeptMots() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil||Carrosserie|86,500|EUR|Higer|1,72|1,50"), null);

        assertFalse(apercu.canImporter());
        assertEquals(0, apercu.lignesValides(), "Le mot inconnu n'est pas rangé en « Divers » pour faire passer la ligne.");
        Issue refus = apercu.erreurs().stream()
                .filter(issue -> "Famille".equals(issue.column()))
                .findFirst().orElseThrow();
        assertEquals(2, refus.row());
        assertTrue(refus.message().contains("Carrosserie"), refus.message());
        assertTrue(refus.message().contains("Pont arrière"), refus.message());
    }

    /** Case vide = refus, là où le prix de vente vide, lui, passe. */
    @Test
    void uneFamilleVideRefuseLaLigneEtNeLaRangePasTouteSeule() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter||86,500|EUR|Higer|1,72|1,50"), null);

        assertFalse(apercu.canImporter());
        assertEquals(0, apercu.lignesValides());
        Issue refus = apercu.erreurs().stream()
                .filter(issue -> "Famille".equals(issue.column()))
                .findFirst().orElseThrow();
        assertTrue(refus.message().contains("vide"), refus.message());
    }

    /**
     * Un classeur qui n'a pas la colonne ne se lit pas : c'est dit une fois, à
     * l'entête, plutôt que ligne après ligne. L'atelier ajoute une colonne, il ne
     * corrige pas cent refus.
     */
    @Test
    void unClasseurSansColonneFamilleEstRefuseEnLeDisantALentete() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Référence|Désignation FR|Prix Ex Works|Coefficient de revient");
        row(sheet, 1, "9.8001.045|Filtre à gasoil|86,500|1,72");

        PieceImportPreview apercu = apercu(workbook, null);

        assertFalse(apercu.canImporter());
        Issue refus = apercu.erreurs().get(0);
        assertEquals(1, refus.row());
        assertTrue(refus.message().contains("colonne « Famille »"), refus.message());
        assertTrue(refus.message().contains("Mécanique"), refus.message());
    }

    /**
     * Une famille mal écrite refuse SA ligne et n'entraîne pas les autres : l'atelier
     * corrige la case visée, il ne reprend pas tout le classeur.
     */
    @Test
    void uneSeuleMauvaiseFamilleNeFaitPasTomberLesAutresLignes() {
        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil||Mécanique|86,500|EUR|Higer|1,72|1,50",
                "2115U-001|Plaquette de frein AV||Pneumatique|124,380|EUR|Higer|1,68|1,50",
                "11B-04010|Amortisseur AV||Suspension|70,000|EUR|Higer|1,68|1,50"), null);

        assertFalse(apercu.canImporter());
        assertEquals(2, apercu.lignesValides());
        assertEquals(3, apercu.erreurs().get(0).row(), "C'est la ligne 3 qui porte le mot inconnu.");
        assertEquals("11B-04010", apercu.lignes().get(1).reference());
    }

    @Test
    void uneFeuilleDEtudeOuDExempleNeSePrendJamaisPourLeCatalogue() throws IOException {
        // Les feuilles du modèle lui-même : si l'atelier les laisse dans le fichier
        // qu'il renvoie, l'exemple « EXEMPLE » ne doit pas entrer au catalogue.
        byte[] octets = importService.modele();
        MockMultipartFile renvoye = new MockMultipartFile("file", "modele-pieces-rechange.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", octets);

        PieceImportPreview apercu = importService.preview(renvoye, "1,72", "1,50");

        assertEquals(0, apercu.lignesValides(), "La feuille d'exemple n'est pas un catalogue.");
        assertFalse(apercu.canImporter(), "Un classeur vide de pièces doit le dire, pas faire croire à un import nul.");
    }

    @Test
    void unCsvExcelFrançaisEnLatin1SeLitAvecSesAccents() {
        // « Filtre à gasoil » écrit en Latin-1 et séparé par des points-virgules :
        // c'est exactement ce qu'Excel renvoie quand on y fait « Enregistrer sous →
        // CSV ». Mal décodé, la pièce entrerait au catalogue avec des points
        // d'interrogation dans sa désignation.
        String texte = "Référence;Désignation FR;Famille;Prix Ex Works;Coefficient de revient\n"
                + "9.8001.045;Filtre à gasoil;Mécanique;1 250,50;1,72\n";
        MockMultipartFile csv = new MockMultipartFile("file", "pieces.csv", "text/csv",
                texte.getBytes(StandardCharsets.ISO_8859_1));

        PieceImportPreview apercu = importService.preview(csv, null, "1,5000");

        assertTrue(apercu.canImporter(), () -> "Refus inattendus : " + apercu.erreurs());
        assertEquals("Filtre à gasoil", apercu.lignes().get(0).designationFr());
        assertEquals(new BigDecimal("1250.50"), apercu.lignes().get(0).prixExWorks());
        assertEquals("MECANIQUE", apercu.lignes().get(0).famille());
    }

    @Test
    void unFichierRenommeNaPasDeContenuValide() {
        MockMultipartFile faux = new MockMultipartFile("file", "pieces.txt", "text/plain",
                "réf;designation\n1;filtre".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> importService.preview(faux, "1,72", "1,50"));
        assertEquals(HttpStatus.BAD_REQUEST, refus.getStatusCode());
        assertTrue(refus.getReason().contains(".xlsx"), refus.getReason());
    }

    @Test
    void unClasseurSansColonneDeReferenceEstRefuseEnLeDisant() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Pieces");
        row(sheet, 0, "Désignation|Famille|Prix");
        row(sheet, 1, "Filtre|Mécanique|86,50");

        PieceImportPreview apercu = apercu(workbook, "1,72");

        assertFalse(apercu.canImporter());
        assertTrue(apercu.erreurs().get(0).message().contains("référence"), apercu.erreurs().get(0).message());
    }

    @Test
    void lapercuSignaleLesPiecesDejaAuCatalogue() {
        Mockito.when(catalogue.existe("9.8001.045")).thenReturn(true);

        PieceImportPreview apercu = apercu(classeur(
                "9.8001.045|Filtre à gasoil|Fuel filter|Mécanique|86,500|EUR|Higer|1,72|1,50",
                "2115U-001|Plaquette de frein AV||Freinage|124,380|EUR|Higer|1,68|1,50"), null);

        assertEquals(1, apercu.misesAJourPrevues());
        assertTrue(apercu.lignes().get(0).dejaAuCatalogue());
        assertFalse(apercu.lignes().get(1).dejaAuCatalogue());
    }

    @Test
    void confirmerEnregistreLigneParLigneEtCompteCeQuiABouge() {
        Mockito.when(catalogue.upsertFromImport(any(), any())).thenReturn(true, false);
        PieceImportLine neuf = PieceImportLine.of(2, new PieceRequest(
                "9.8001.045", "Filtre à gasoil", null, "MECANIQUE", new BigDecimal("86.500"),
                new BigDecimal("1.7200"), new BigDecimal("1.5000"), null, null), false);
        PieceImportLine existant = PieceImportLine.of(3, new PieceRequest(
                "2115U-001", "Plaquette de frein AV", null, "FREINAGE", new BigDecimal("124.380"),
                new BigDecimal("1.6800"), new BigDecimal("1.4000"), null, null), true);

        PieceImportSummary resume = importService.confirmer(
                new PieceImportCommit(List.of(neuf, existant)), null);

        assertEquals(1, resume.ajoutees());
        assertEquals(1, resume.misesAJour());
        assertEquals(0, resume.refusees());

        // Les deux coefficients traversent l'import jusqu'au service : s'ils se
        // perdaient ici, l'atelier verrait son classeur accepté à l'aperçu et un prix
        // de vente vide en base.
        ArgumentCaptor<PieceImportLine> capture = ArgumentCaptor.forClass(PieceImportLine.class);
        Mockito.verify(catalogue, Mockito.times(2)).upsertFromImport(capture.capture(), any());
        assertEquals(new BigDecimal("1.5000"), capture.getAllValues().get(0).coefficientVente());
        assertEquals(new BigDecimal("1.4000"), capture.getAllValues().get(1).coefficientVente());
        // La famille traverse le même chemin : perdue ici, la pièce écrirait « null »
        // dans une colonne que la base ne laisse pas vide.
        assertEquals("MECANIQUE", capture.getAllValues().get(0).famille());
    }

    @Test
    void confirmerSansLigneRefusePlutotQueDeFaireCroireUnImportReussi() {
        ResponseStatusException refus = assertThrows(ResponseStatusException.class,
                () -> importService.confirmer(new PieceImportCommit(List.of()), null));
        assertTrue(refus.getReason().contains("relance l'aperçu"), refus.getReason());
    }

    @Test
    void unRefusDuServiceALecritureResteVisibleDansLeResume() {
        Mockito.when(catalogue.upsertFromImport(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "La référence « X » est déjà au catalogue."));

        PieceImportSummary resume = importService.confirmer(new PieceImportCommit(List.of(
                PieceImportLine.of(2, new PieceRequest(
                        "X", "Pièce", null, "DIVERS", new BigDecimal("1.000"),
                        new BigDecimal("1.7200"), new BigDecimal("1.5000"), null, null), false))), null);

        assertEquals(1, resume.refusees());
        assertEquals(0, resume.ajoutees());
        assertTrue(resume.details().get(0).message().contains("déjà au catalogue"),
                resume.details().get(0).message());
    }

    @Test
    void leModeleSeTelechargeAvecSesEntetesEtSansFaussePiece() throws IOException {
        byte[] octets = importService.modele();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(octets))) {
            Sheet pieces = workbook.getSheet("Pieces");
            assertEquals(0, pieces.getLastRowNum(),
                    "La feuille de saisie ne porte QUE l'entête : un exemple importé serait une pièce fausse au catalogue.");
            assertEquals("Référence", pieces.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Famille", pieces.getRow(0).getCell(3).getStringCellValue());
            assertEquals("Coefficient de revient", pieces.getRow(0).getCell(7).getStringCellValue());
            assertEquals("Coefficient de vente", pieces.getRow(0).getCell(8).getStringCellValue());
            assertEquals(9, pieces.getRow(0).getLastCellNum(),
                    "Le modèle porte UNE famille et DEUX coefficients (revient, vente), aucun prix calculé.");
            assertNotNull(workbook.getSheet("Notice"));
            assertNotNull(workbook.getSheet("Exemple"));
        }
    }
}
