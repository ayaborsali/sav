package tn.higer.sav_backend.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.ImportPreview;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.OperationDto;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanRequest;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.StepDto;
import tn.higer.sav_backend.model.PlanOperationType;
import tn.higer.sav_backend.repository.MaintenancePlanRepository;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La lecture du classeur Higer.
 *
 * Le principe tenu ici : un fichier ne remplace jamais la validation humaine. Ces
 * tests vérifient que l'import <b>ne propose</b> que ce que le fichier dit, qu'il
 * signale chaque incompréhension au lieu de la combler, et que rien n'est écrit en
 * base avant la confirmation. Ils vérifient aussi le tolérant : un document
 * recopié à la main écrit « désignation » un jour et « article » le lendemain, et
 * une kilometrance « 45 000 » avec un espace ne doit pas devenir zéro.
 */
class MaintenancePlanImportServiceTest {

    private final MaintenancePlanImportService importService = new MaintenancePlanImportService();

    /** Un classeur conforme : deux paliers, deux opérations, une grille à croix. */
    private static Workbook classeur() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet plan = workbook.createSheet("Plan");
        ligne(plan, 0, "champ", "valeur");
        ligne(plan, 1, "nom", "BUS HIGER 26 WBE – URBAIN");
        ligne(plan, 2, "modele", "BUS HIGER CONFORT");
        ligne(plan, 3, "km_annuel", "80 000");
        ligne(plan, 4, "vidange_km", "10000");
        ligne(plan, 5, "vidange_jours", "45");
        ligne(plan, 6, "duree_annees", "3");

        Sheet paliers = workbook.createSheet("Paliers");
        ligne(paliers, 0, "ref", "libelle", "km", "jours", "prix_ht");
        ligne(paliers, 1, "P1", "visite des 10 000 km", "10 000", null, null);
        ligne(paliers, 2, "P2", "révision des 12 mois", null, "365", null);

        Sheet operations = workbook.createSheet("Operations");
        ligne(operations, 0, "ref", "organe", "article", "reference", "quantite", "action",
                "frequence_km", "prix_unitaire_ht");
        ligne(operations, 1, "O1", "Moteur", "Vidange huile moteur", "15W-40", "7,5 L",
                "remplacer", "10000", null);
        ligne(operations, 2, "O2", "Moteur", "Courroie d'accessoires", null, null,
                "contrôler", null, null);

        Sheet grille = workbook.createSheet("Grille");
        ligne(grille, 0, "ref_operation", "P1", "révision des 12 mois");
        ligne(grille, 1, "O1", "X", "X");
        ligne(grille, 2, "O2", "-", "X");
        return workbook;
    }

    private static void ligne(Sheet sheet, int index, String... valeurs) {
        Row row = sheet.createRow(index);
        for (int c = 0; c < valeurs.length; c++) {
            if (valeurs[c] != null) row.createCell(c).setCellValue(valeurs[c]);
        }
    }

    private static MockMultipartFile fichier(Workbook workbook) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return new MockMultipartFile("file", "plan.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ImportPreview lire(Workbook workbook) {
        return importService.preview(fichier(workbook));
    }

    private static String erreurs(ImportPreview preview) {
        return preview.errors().toString();
    }

    // -------------------------------------------------------------- le cas normal

    @Test
    void unClasseurConformeEstProposeSansErreur() {
        ImportPreview preview = lire(classeur());

        assertTrue(preview.canImport(), erreurs(preview));
        assertTrue(preview.errors().isEmpty(), erreurs(preview));
        PlanRequest demande = preview.plan();
        assertEquals("BUS HIGER 26 WBE – URBAIN", demande.name());
        assertEquals("BUS HIGER CONFORT", demande.busModel());
        assertEquals(80000L, demande.annualKm());
        assertEquals(10000, demande.oilChangeIntervalKm());
        assertEquals(45, demande.oilChangeIntervalDays());
        assertEquals(3, demande.durationYears());
        assertEquals(2, demande.steps().size());
        assertEquals(2, demande.operations().size());
    }

    /** Les croix du document Higer se rangent dans le bon palier. */
    @Test
    void chaqueCroixSeReporteSurLePalierConcerne() {
        List<StepDto> paliers = lire(classeur()).plan().steps();

        assertEquals(List.of("O1"), paliers.get(0).operationRefs());
        assertEquals(List.of("O1", "O2"), paliers.get(1).operationRefs());
    }

    @Test
    void uneCaseVideDePrixNestJamaisUneErreur() {
        ImportPreview preview = lire(classeur());

        assertNull(preview.plan().steps().get(0).priceHt());
        assertNull(preview.plan().operations().get(0).unitPriceHt());
        assertTrue(preview.errors().isEmpty(), erreurs(preview));
    }

    /** Ce que l'import propose doit passer le contrôle du service : sinon le bouton « importer » serait un piège. */
    @Test
    void ceQueLimportProposeEstAcceptableParLeService() {
        MaintenancePlanRepository plans = Mockito.mock(MaintenancePlanRepository.class);
        AuditService audit = Mockito.mock(AuditService.class);

        assertDoesNotThrow(() -> new MaintenancePlanService(plans,
                Mockito.mock(tn.higer.sav_backend.repository.SoldPlanStepRepository.class), audit)
                .create(lire(classeur()).plan(), null, true));
    }

    // ------------------------------------------------------------- le tolérant

    @Test
    void lesNombresAvecSeparateursSeLisent() {
        ImportPreview preview = lire(classeur());
        assertEquals(10000L, preview.plan().steps().get(0).km());
        assertEquals(365, preview.plan().steps().get(1).days());
    }

    @Test
    void uneCaseNumeriqueDExcelSeLitCommeDuTexte() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet paliers = workbook.getSheet("Paliers");
        Row rang = paliers.getRow(2);
        rang.getCell(3).setCellValue(365);   // la cellule est un nombre, pas une chaîne

        assertEquals(365, lire(workbook).plan().steps().get(1).days());
    }

    @Test
    void unCompteurQuiResisteALaLectureEstSignalePlutotQueDevine() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet paliers = workbook.getSheet("Paliers");
        ligne(paliers, 3, "P3", "visite à moitie", "10,5", null, null);

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("10,5"), erreurs(preview));
    }

    // ------------------------------------------------------------- les signalements

    @Test
    void uneFeuilleManquanteEstDiteEtRienNestPropose() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        workbook.removeSheetAt(workbook.getSheetIndex("Grille"));

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertNull(preview.plan());
        assertTrue(erreurs(preview).contains("Grille"), erreurs(preview));
        assertTrue(erreurs(preview).contains("modèle"), erreurs(preview));
    }

    @Test
    void unPalierSansBorneEstSignaleEtNonPropose() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet paliers = workbook.getSheet("Paliers");
        ligne(paliers, 3, "P3", "quand on veut", null, null, null);

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("quand on veut"), erreurs(preview));
        assertEquals(2, preview.plan().steps().size());
    }

    @Test
    void uneCroixSurUneOperationInconnueEstSignalee() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet grille = workbook.getSheet("Grille");
        ligne(grille, 3, "O77", "X", null);

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("O77"), erreurs(preview));
    }

    @Test
    void uneColonneDeGrilleQuiNeCorrespondAUnPalierEstSignalee() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet grille = workbook.getSheet("Grille");
        ligne(grille, 0, "ref_operation", "P1", "palier inventé");
        ligne(grille, 1, "O1", "X", "X");

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("palier inventé"), erreurs(preview));
        // Le P1 toujours compréhensible est proposé malgré tout.
        assertEquals(List.of("O1"), preview.plan().steps().get(0).operationRefs());
    }

    @Test
    void deuxReferencesIdentiquesDansLesOperationsSontSignalees() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet operations = workbook.getSheet("Operations");
        ligne(operations, 3, "O1", "Freinage", "Contrôle plaquettes", null, null, "contrôler", null, null);

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("même ref"), erreurs(preview));
    }

    @Test
    void uneActionInconnueEstSignaleeAvecLesCinqAcceptees() {
        XSSFWorkbook workbook = (XSSFWorkbook) classeur();
        Sheet operations = workbook.getSheet("Operations");
        ligne(operations, 3, "O3", "Freinage", "Contrôle plaquettes", null, null, "démonter", null, null);

        ImportPreview preview = lire(workbook);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("démonter"), erreurs(preview));
        assertTrue(erreurs(preview).contains("graisser"), erreurs(preview));
    }

    /** Un « - », un « non » ou un « 0 » dans la grille veut dire : rien à faire ici. */
    @Test
    void uneCaseNegaiveDeLaGrilleNeCocheRien() {
        List<String> croixDeP1 = lire(classeur()).plan().steps().get(0).operationRefs();

        assertEquals(List.of("O1"), croixDeP1);
        assertFalse(croixDeP1.contains("O2"));
    }

    @Test
    void unFichierVideEstRefuseAvantDEtreOuvert() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> importService.preview(new MockMultipartFile("file", new byte[]{})));
        assertTrue(e.getReason().contains("Aucun fichier reçu"), e.getReason());
    }

    @Test
    void unFichierQuiNestPasUnClasseurEstRefuseAvecLaCause() {
        MockMultipartFile faux = new MockMultipartFile("file", "plan.pdf",
                "application/pdf", "ceci n'est pas un xlsx".getBytes());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> importService.preview(faux));
        assertTrue(e.getReason().contains("n'a pas pu être lu"), e.getReason());
        assertEquals(400, e.getStatusCode().value());
    }

    // ------------------------------------------------------------- le modèle vide

    @Test
    void leModeleProduitLesQuatreFeuillesEtSeRelitLuiMeme() {
        byte[] modele = importService.template();

        try (Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory
                .create(new java.io.ByteArrayInputStream(modele))) {
            assertEquals(5, workbook.getNumberOfSheets());
            List<String> noms = List.of("Plan", "Paliers", "Operations", "Grille", "Notice");
            noms.forEach(nom -> assertNotNull(workbook.getSheet(nom), nom));
        } catch (Exception e) {
            fail("Le modèle produit n'est pas un classeur lisible : " + e);
        }
    }

    @Test
    void leModeleSansRienDeRempliEstSignaleCommeVide() {
        MockMultipartFile vide = new MockMultipartFile("file", "modele.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                importService.template());

        ImportPreview preview = importService.preview(vide);

        assertFalse(preview.canImport());
        assertTrue(erreurs(preview).contains("nom de plan"), erreurs(preview));
        assertTrue(erreurs(preview).contains("Aucun palier"), erreurs(preview));
    }

    /** Le document d'atelier écrit « Désignation » et « Type d'action » : elles doivent passer. */
    @Test
    void lesEntetesVariantsPassent() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet plan = workbook.createSheet("Plan");
        ligne(plan, 0, "Nom", "Valeur");
        ligne(plan, 1, "Nom du plan", "26 WBE périurbain");
        ligne(plan, 2, "gamme", "Standard");
        Sheet paliers = workbook.createSheet("Paliers");
        ligne(paliers, 0, "Code", "Visite", "Kilométrage", "Durée", "Prix");
        ligne(paliers, 1, "V1", "10 000 km", "45.000", null, null);
        Sheet operations = workbook.createSheet("Operations");
        ligne(operations, 0, "Code", "Système", "Désignation", "Type d'action");
        ligne(operations, 1, "L1", "Freins", "Plaquettes", "vérifier");
        Sheet grille = workbook.createSheet("Grille");
        ligne(grille, 0, "Operation", "V1");
        ligne(grille, 1, "L1", "x");

        ImportPreview preview = lire(workbook);

        assertTrue(preview.canImport(), erreurs(preview));
        assertEquals("26 WBE périurbain", preview.plan().name());
        assertEquals(45000L, preview.plan().steps().get(0).km());
        assertEquals("Plaquettes", preview.plan().operations().get(0).article());
        assertEquals("inspect", preview.plan().operations().get(0).operationType());
        assertEquals(List.of("L1"), preview.plan().steps().get(0).operationRefs());
    }

    /**
     * Le trajet complet d'une action, raté une première fois sur un vrai classeur :
     * le mot français du fichier ressort en jeton de l'API (« lubricate »,
     * « adjust »), et l'enregistrement relit ce jeton. Si la relecture ne connaît
     * que le français, l'aperçu accepté se refuse au commit — l'atelier voit son
     * propre fichier lui être renvoyé, avec une erreur qui l'accuse d'écrire une
     * action inconnue.
     */
    @Test
    void lesJetonsDactionRendusParLapercoSeRelisentALenregistrement() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet plan = workbook.createSheet("Plan");
        ligne(plan, 0, "champ", "valeur");
        ligne(plan, 1, "nom", "plan de test");
        ligne(plan, 2, "modele", "Confort");
        Sheet paliers = workbook.createSheet("Paliers");
        ligne(paliers, 0, "ref", "libelle", "km", "jours", "prix_ht");
        ligne(paliers, 1, "P1", "visite des 5 000 km", "5000", null, null);
        Sheet operations = workbook.createSheet("Operations");
        ligne(operations, 0, "ref", "organe", "article", "reference", "quantite", "action",
                "frequence_km", "prix_unitaire_ht");
        ligne(operations, 1, "O1", "Moteur", "Filtre à huile", null, null, "remplacer", null, null);
        ligne(operations, 2, "O2", "Freinage", "Plaquettes", null, null, "contrôler", null, null);
        ligne(operations, 3, "O3", "Châssis", "Cardans", null, null, "graisser", null, null);
        ligne(operations, 4, "O4", "Admission", "Filtre à air", null, null, "nettoyer", null, null);
        ligne(operations, 5, "O5", "Transmission", "Jeu d'embrayage", null, null, "régler", null, null);
        Sheet grille = workbook.createSheet("Grille");
        ligne(grille, 0, "ref_operation", "P1");
        for (int r = 1; r <= 5; r++) ligne(grille, r, "O" + r, "X");

        ImportPreview preview = lire(workbook);

        assertTrue(preview.canImport(), erreurs(preview));
        List<OperationDto> lignes = preview.plan().operations();
        assertEquals(List.of("replace", "inspect", "lubricate", "clean", "adjust"),
                lignes.stream().map(OperationDto::operationType).toList());
        assertEquals(List.of("REPLACE", "INSPECT", "LUBRICATE", "CLEAN", "ADJUST"),
                lignes.stream()
                        .map(operation -> PlanOperationType.fromLabel(operation.operationType()).name())
                        .toList());
        // Le libellé français rendu à l'écran doit se relire comme le jeton : les deux
        // formes reviennent dans une demande, l'une par la grille, l'autre par la saisie.
        for (OperationDto operation : lignes) {
            assertSame(PlanOperationType.fromLabel(operation.operationType()),
                    PlanOperationType.fromLabel(operation.operationTypeLabel()),
                    "Le jeton « " + operation.operationType() + " » et son libellé « "
                            + operation.operationTypeLabel() + " » ne désignent pas la même action");
        }
    }
}
