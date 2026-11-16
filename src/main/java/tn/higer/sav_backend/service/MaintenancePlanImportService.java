package tn.higer.sav_backend.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.ImportIssue;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.ImportPreview;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.OperationDto;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.PlanRequest;
import tn.higer.sav_backend.dto.MaintenancePlanDtos.StepDto;
import tn.higer.sav_backend.model.BusType;
import tn.higer.sav_backend.model.PlanOperationType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lecture d'un classeur Excel dans le catalogue des forfaits.
 *
 * Le principe tenu ici : <b>un fichier ne remplace jamais la validation
 * humaine</b>. L'import ne fait que proposer. Il renvoie ce qu'il a compris et la
 * liste de ce qu'il n'a pas compris, et rien n'est écrit en base avant qu'un
 * compte avec droit d'écriture ait confirmé. C'est la seule façon de laisser
 * l'atelier déposer son document Higer tel quel, sans qu'une colonne mal lue se
 * transforme en palier facturé.
 *
 * Le classeur attendu est celui que {@link #template()} fabrique : quatre
 * feuilles (« Plan », « Paliers », « Operations », « Grille »). Les noms de
 * colonnes sont reconnus sans accent, sans casse et avec des synonymes, parce
 * qu'un document recopié à la main écrit « désignation » un jour et « article »
 * le lendemain. Une colonne inconnue n'est pas une erreur : elle est signalée,
 * et le fichier garde sa forme d'origine.
 *
 * Les prix peuvent manquer : c'est l'état normal du catalogue aujourd'hui. Une
 * case de prix vide n'est donc jamais une erreur d'import.
 */
@Service
public class MaintenancePlanImportService {

    private static final String SHEET_PLAN = "plan";
    private static final String SHEET_STEPS = "paliers";
    private static final String SHEET_OPERATIONS = "operations";
    private static final String SHEET_GRID = "grille";

    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final DataFormatter formatter = new DataFormatter();

    /**
     * Lit le classeur et renvoie l'aperçu. Ne touche à aucune table.
     *
     * @return les lignes comprises et, à côté, chaque point que l'atelier devra
     *         corriger dans le fichier ou accepter avant l'enregistrement
     */
    public ImportPreview preview(MultipartFile file) {
        byte[] bytes = read(file);
        List<ImportIssue> errors = new ArrayList<>();
        List<ImportIssue> warnings = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet planSheet = sheet(workbook, SHEET_PLAN);
            Sheet stepsSheet = sheet(workbook, SHEET_STEPS);
            Sheet operationsSheet = sheet(workbook, SHEET_OPERATIONS);
            Sheet gridSheet = sheet(workbook, SHEET_GRID);

            List<String> missing = new ArrayList<>();
            if (planSheet == null) missing.add("Plan");
            if (stepsSheet == null) missing.add("Paliers");
            if (operationsSheet == null) missing.add("Operations");
            if (gridSheet == null) missing.add("Grille");
            if (!missing.isEmpty()) {
                errors.add(new ImportIssue("Classeur", null, null,
                        "Feuille(s) introuvable(s) : " + String.join(", ", missing)
                                + ". Télécharge le modèle du classeur et garde ces quatre feuilles."));
                return new ImportPreview(null, errors, warnings);
            }

            Map<String, String> header = readPlanSheet(planSheet, errors);
            List<StepDto> steps = readSteps(stepsSheet, errors, warnings);
            List<OperationDto> operations = readOperations(operationsSheet, errors, warnings);
            List<String[]> grid = readGrid(gridSheet, steps, operations, errors, warnings);

            // Les croix se reportent dans chaque palier, par référence d'opération.
            Map<String, List<String>> crossed = new HashMap<>();
            for (String[] cell : grid) {
                crossed.computeIfAbsent(cell[0], k -> new ArrayList<>()).add(cell[1]);
            }
            List<StepDto> withGrid = new ArrayList<>();
            for (StepDto step : steps) {
                List<String> refs = crossed.getOrDefault(step.ref(), List.of());
                withGrid.add(new StepDto(step.ref(), step.label(), step.km(), step.days(),
                        step.priceHt(), refs));
            }

            // Le nombre d'un classeur se présente sous trois formes (« 80 000 »,
            // « 80.000 », « 80000 »). Une seule ligne de lecture pour les quatre
            // champs du plan, et un format inattendu ne fait pas disparaître la
            // valeur en silence : elle est signalée pour que l'atelier corrige.
            PlanRequest request = new PlanRequest(
                    header.get("name"), header.get("busModel"),
                    asLong(header.get("annualKm"), "km_annuel", 0, "Plan", errors),
                    asInt(header.get("oilChangeIntervalKm"), "vidange_km", 0, "Plan", errors),
                    asInt(header.get("oilChangeIntervalDays"), "vidange_jours", 0, "Plan", errors),
                    asInt(header.get("durationYears"), "duree_annees", 0, "Plan", errors),
                    Boolean.TRUE, withGrid, operations);

            if (header.get("name") == null) {
                errors.add(new ImportIssue("Plan", 1, "nom",
                        "Le classeur ne porte pas de nom de plan : c'est ce nom que l'atelier citera au client."));
            }
            if (BusType.fromLabel(header.get("busModel")) == null) {
                errors.add(new ImportIssue("Plan", 2, "gamme",
                        "La case « gamme » doit dire Confort ou Standard : c'est elle qui décide à quels bus ce "
                                + "forfait peut être vendu."
                                + (header.get("busModel") == null ? "" : " Lu dans le fichier : « "
                                        + header.get("busModel") + " ».")));
            }
            if (steps.isEmpty()) {
                errors.add(new ImportIssue("Paliers", null, null,
                        "Aucun palier lisible dans la feuille. Un plan sans palier ne peut être vendu."));
            }
            if (operations.isEmpty()) {
                errors.add(new ImportIssue("Operations", null, null,
                        "Aucune opération lisible dans la feuille."));
            }
            return new ImportPreview(request, errors, warnings);
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le classeur n'a pas pu être lu : " + cause(e)
                            + ". Vérifie qu'il s'agit bien d'un fichier Excel (.xlsx ou .xls) et non d'un fichier renommé à la main.", e);
        }
    }

    /** Le classeur vide, avec les quatre feuilles, les entêtes et une page de notice. */
    public byte[] template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(workbook.createSheet("Plan"), List.of(
                    List.of("champ", "valeur"),
                    List.of("nom", ""),
                    List.of("gamme", "Confort"),
                    List.of("km_annuel", ""),
                    List.of("vidange_km", ""),
                    List.of("vidange_jours", ""),
                    List.of("duree_annees", "")));
            writeSheet(workbook.createSheet("Paliers"), List.of(
                    List.of("ref", "libelle", "km", "jours", "prix_ht")));
            writeSheet(workbook.createSheet("Operations"), List.of(
                    List.of("ref", "organe", "article", "reference", "quantite", "action",
                            "frequence_km", "prix_unitaire_ht")));
            writeSheet(workbook.createSheet("Grille"), List.of(
                    List.of("ref_operation")));
            writeSheet(workbook.createSheet("Notice"), List.of(
                    List.of("Comment remplir ce classeur"),
                    List.of("1. Une feuille par ce que contient ton document : le plan, ses paliers, ses opérations, la grille à croix."),
                    List.of("2. « ref » est une clé courte et unique (P1, O1, 10000…). Elle sert seulement à relier une croix à sa ligne ; le serveur donne les vrais identifiants à l'enregistrement."),
                    List.of("3. Dans « Grille », la première colonne est la ref de l'opération et chaque autre colonne est un palier (sa ref, ou son libellé). Écris X dans la case quand l'opération tombe à ce palier."),
                    List.of("4. Un palier doit avoir un km, des jours, ou les deux. Sans borne, aucun bus ne peut l'atteindre."),
                    List.of("5. « gamme » = Confort ou Standard. C'est la gamme d'autobus que ce plan entretient : un plan Confort ne se vend pas sur un bus Standard."),
                    List.of("6. Les prix peuvent rester vides. Ils seront ajoutés quand le gérant les aura fixés, et l'écran les signalera tant qu'ils manquent."),
                    List.of("7. Les noms de colonnes sont lus sans accent et sans casse. « désignation » passe à la place de « article », « frequence » à la place de « frequence_km »."),
                    List.of("8. Ne pré-remplis rien à la place de l'atelier : l'import ne crée que ce que ton fichier dit, et il te montre ses erreurs avant d'enregistrer.")));
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Le modèle de classeur n'a pas pu être produit.", e);
        }
    }

    // ------------------------------------------------------------------ lecture

    private Map<String, String> readPlanSheet(Sheet sheet, List<ImportIssue> errors) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String key = fold(text(row.getCell(0)));
            String value = trimToNull(text(row.getCell(1)));
            if (key.isEmpty() || value == null) continue;
            String field = switch (key) {
                case "nom", "name", "plan", "nomduplan" -> "name";
                case "modele", "model", "busmodele", "bus", "modelebus", "gamme", "type", "typedebus" -> "busModel";
                case "kmannuel", "annualkm", "kman", "km" -> "annualKm";
                case "vidangekm", "oilchangeintervalkm", "intervallevidangekm", "vidange" -> "oilChangeIntervalKm";
                case "vidangejours", "oilchangeintervaldays", "intervallevidangejours" -> "oilChangeIntervalDays";
                case "dureeannees", "durationyears", "duree", "annees" -> "durationYears";
                default -> null;
            };
            if (field == null) {
                errors.add(new ImportIssue("Plan", r + 1, text(row.getCell(0)),
                        "Champ du plan non reconnu : il sera ignoré."));
                continue;
            }
            values.put(field, value);
        }
        return values;
    }

    private List<StepDto> readSteps(Sheet sheet, List<ImportIssue> errors, List<ImportIssue> warnings) {
        Map<String, Integer> columns = header(sheet, warnings, "Paliers");
        List<StepDto> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int labelCol = column(columns, "libelle", "label", "visite", "palier");
        int kmCol = column(columns, "km", "kilometrage", "kilometres", "kmcumule");
        int daysCol = column(columns, "jours", "days", "age", "duree");
        int priceCol = column(columns, "prixht", "prix", "priceht", "tarif");
        int refCol = column(columns, "ref", "code", "id");

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String label = trimToNull(text(cell(row, labelCol)));
            String km = trimToNull(text(cell(row, kmCol)));
            String days = trimToNull(text(cell(row, daysCol)));
            String price = trimToNull(text(cell(row, priceCol)));
            if (label == null && km == null && days == null) continue; // ligne vide

            if (label == null) {
                errors.add(new ImportIssue("Paliers", r + 1, null, "Ce palier n'a pas de libellé."));
                continue;
            }
            if (km == null && days == null) {
                errors.add(new ImportIssue("Paliers", r + 1, "libelle",
                        "Le palier « " + label + " » n'a ni kilométrage ni durée : aucun bus ne peut l'atteindre."));
                continue;
            }
            String ref = trimToNull(text(cell(row, refCol)));
            if (ref == null) ref = "P" + (steps.size() + 1);
            if (!seen.add(fold(ref))) {
                errors.add(new ImportIssue("Paliers", r + 1, "ref",
                        "Deux paliers portent la même ref « " + ref + " » : la grille ne saurait plus lesquels cocher."));
                continue;
            }
            steps.add(new StepDto(ref, label, asLong(km, "km", r, "Paliers", errors),
                    asInt(days, "jours", r, "Paliers", errors), asDouble(price), List.of()));
        }
        return steps;
    }

    private List<OperationDto> readOperations(Sheet sheet, List<ImportIssue> errors, List<ImportIssue> warnings) {
        Map<String, Integer> columns = header(sheet, warnings, "Operations");
        List<OperationDto> operations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int organCol = column(columns, "organe", "organ", "systeme", "equipe");
        int articleCol = column(columns, "article", "designation", "travail", "operation");
        int refPieceCol = column(columns, "reference", "ref", "lubrifiant", "piece");
        int quantityCol = column(columns, "quantite", "qte", "quantiteunit", "volume");
        int actionCol = column(columns, "action", "type", "typedaction", "operationtype", "geste");
        int freqCol = column(columns, "frequencekm", "frequence", "intervalskm");
        int priceCol = column(columns, "prixunitaireht", "prixht", "pu", "prixunitaire", "unitpriceht");
        int refCol = column(columns, "ref", "code", "id");

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String organ = trimToNull(text(cell(row, organCol)));
            String article = trimToNull(text(cell(row, articleCol)));
            String action = trimToNull(text(cell(row, actionCol)));
            if (organ == null && article == null && action == null) continue;

            PlanOperationType type = PlanOperationType.fromLabel(action);
            if (type == null) {
                errors.add(new ImportIssue("Operations", r + 1, "action",
                        "Action non reconnue (« " + action + " »). Écris : remplacer, contrôler, graisser, nettoyer ou régler."));
                continue;
            }
            if (organ == null && article == null) {
                errors.add(new ImportIssue("Operations", r + 1, null,
                        "Cette ligne ne dit rien de précis : donne-lui au moins un organe ou une désignation."));
                continue;
            }
            String ref = trimToNull(text(cell(row, refCol)));
            if (ref == null) ref = "O" + (operations.size() + 1);
            if (!seen.add(fold(ref))) {
                errors.add(new ImportIssue("Operations", r + 1, "ref",
                        "Deux opérations portent la même ref « " + ref + " »."));
                continue;
            }
            operations.add(new OperationDto(ref, type.name().toLowerCase(), type.label(), organ, article,
                    trimToNull(text(cell(row, refPieceCol))), trimToNull(text(cell(row, quantityCol))),
                    asInt(trimToNull(text(cell(row, freqCol))), "frequence_km", r, "Operations", errors),
                    asDouble(trimToNull(text(cell(row, priceCol))))));
        }
        return operations;
    }

    /** La grille à croix : chaque case marquée devient un couple (ref palier, ref opération). */
    private List<String[]> readGrid(Sheet sheet, List<StepDto> steps, List<OperationDto> operations,
                                    List<ImportIssue> errors, List<ImportIssue> warnings) {
        List<String[]> cells = new ArrayList<>();
        Row head = sheet.getRow(0);
        if (head == null) {
            warnings.add(new ImportIssue("Grille", 1, null, "Aucune entête lisible : aucune croix importée."));
            return cells;
        }
        Map<String, String> operationsByRef = new HashMap<>();
        for (OperationDto o : operations) operationsByRef.put(fold(o.ref()), o.ref());
        Map<String, String> stepsByRef = new HashMap<>();
        for (StepDto s : steps) stepsByRef.put(fold(s.ref()), s.ref());
        // Le libellé du palier est accepté en entête de colonne : c'est ce que le
        // document Higer écrit réellement, et une ref inventée après coup se trompe
        // de colonne plus souvent qu'un libellé recopié.
        Map<String, String> stepsByLabel = new HashMap<>();
        for (StepDto s : steps) stepsByLabel.putIfAbsent(fold(s.label()), s.ref());

        int opCol = column(header(sheet, warnings, "Grille"), "refoperation", "operation", "ref", "ligne");
        if (opCol < 0) {
            errors.add(new ImportIssue("Grille", 1, null,
                    "Aucune colonne de référence d'opération en tête de la grille : aucune croix ne peut être lue."));
            return cells;
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String opRef = trimToNull(text(cell(row, opCol)));
            if (opRef == null) continue;
            String operation = operationsByRef.get(fold(opRef));
            if (operation == null) {
                errors.add(new ImportIssue("Grille", r + 1, null,
                        "La ref d'opération « " + opRef + " » n'existe pas dans la feuille Operations."));
                continue;
            }
            for (int c = 0; c < head.getLastCellNum(); c++) {
                if (c == opCol) continue;
                String stepHead = trimToNull(text(head.getCell(c)));
                if (stepHead == null) continue;
                String mark = trimToNull(text(row.getCell(c)));
                if (mark == null || isNegativeMark(mark)) continue;
                String step = stepsByRef.get(fold(stepHead));
                if (step == null) step = stepsByLabel.get(fold(stepHead));
                if (step == null) {
                    errors.add(new ImportIssue("Grille", 1, stepHead,
                            "Cette colonne de la grille ne correspond à aucun palier de la feuille Paliers."));
                    continue;
                }
                cells.add(new String[]{step, operation});
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------ outils

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aucun fichier reçu : choisis le classeur dans ton poste.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le classeur dépasse 5 Mo. Un plan de maintenance tient dans un fichier bien plus petit : "
                            + "ne garde que les feuilles Plan, Paliers, Operations et Grille.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier n'a pas pu être lu.", e);
        }
    }

    private static String cause(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static Sheet sheet(Workbook workbook, String foldedName) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (fold(workbook.getSheetName(i)).equals(foldedName)) {
                return workbook.getSheetAt(i);
            }
        }
        return null;
    }

    /** Entête de la première ligne : nom de colonne plié → numéro de colonne. */
    private Map<String, Integer> header(Sheet sheet, List<ImportIssue> warnings, String sheetName) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        Row head = sheet.getRow(0);
        if (head == null) {
            warnings.add(new ImportIssue(sheetName, 1, null, "Entête introuvable : les colonnes ont été cherchées par leur position."));
            return columns;
        }
        for (int c = 0; c < head.getLastCellNum(); c++) {
            String name = fold(text(head.getCell(c)));
            if (!name.isEmpty()) columns.putIfAbsent(name, c);
        }
        return columns;
    }

    /** La première colonne dont le nom plié commence par l'un des synonymes donnés. */
    private static int column(Map<String, Integer> header, String... aliases) {
        for (String alias : aliases) {
            String key = fold(alias);
            for (Map.Entry<String, Integer> entry : header.entrySet()) {
                if (entry.getKey().equals(key)) return entry.getValue();
            }
            for (Map.Entry<String, Integer> entry : header.entrySet()) {
                if (entry.getKey().startsWith(key)) return entry.getValue();
            }
        }
        return -1;
    }

    private static Cell cell(Row row, int index) {
        return index < 0 ? null : row.getCell(index);
    }

    private String text(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static String fold(String value) {
        return value == null ? "" : PlanOperationType.fold(value);
    }

    private static boolean isNegativeMark(String mark) {
        String folded = fold(mark);
        // Un tiret seul ne laisse plus rien une fois plié : c'est bien une absence.
        return folded.isEmpty() || folded.equals("o") || folded.equals("non")
                || folded.equals("n") || folded.equals("0") || folded.equals("ras");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Les compteurs (kilomètres, jours, fréquences) : « 45 000 », « 45.000 » et
     * « 45,000 » valent tous 45000. Un nombre qui ne se lit pas comme un entier
     * (« 10,5 ») est signalé au lieu d'être deviné : ici, une valeur devinée de
     * travers se facture six mois plus tard sur un bon de travail.
     */
    private static String entier(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9.,-]", "");
        if (cleaned.isEmpty()) return null;
        String[] groupes = cleaned.split("[.,]", -1);
        for (int i = 1; i < groupes.length; i++) {
            if (groupes[i].length() != 3 || !groupes[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
        }
        return String.join("", groupes);
    }

    /** L'argent : « 1 250,50 », « 1.250,50 », « 1250.50 » et « 1250 » se lisent tous. */
    private static String argent(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9.,-]", "");
        if (cleaned.isEmpty()) return null;
        int decimal = Math.max(cleaned.lastIndexOf(','), cleaned.lastIndexOf('.'));
        if (decimal < 0) return cleaned;
        String partieEntiere = cleaned.substring(0, decimal).replaceAll("[.,]", "");
        String decimales = cleaned.substring(decimal + 1);
        if (decimales.isEmpty() || !decimales.chars().allMatch(Character::isDigit)) return null;
        return partieEntiere + "." + decimales;
    }

    private static Long asLong(String raw, String what, int row, String sheet, List<ImportIssue> errors) {
        String value = entier(raw);
        if (value == null) {
            if (raw != null && !raw.isBlank()) {
                errors.add(new ImportIssue(sheet, row + 1, what,
                        "« " + raw + " » ne se lit pas comme un nombre pour « " + what
                                + " ». Écris-le en chiffres, sans unité ni virgule."));
            }
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            errors.add(new ImportIssue(sheet, row + 1, what,
                    "« " + raw + " » ne se lit pas comme un nombre pour « " + what + " »."));
            return null;
        }
    }

    private static Integer asInt(String raw, String what, int row, String sheet, List<ImportIssue> errors) {
        Long value = asLong(raw, what, row, sheet, errors);
        return value == null ? null : value.intValue();
    }

    private static Double asDouble(String raw) {
        String value = argent(raw);
        if (value == null) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null; // un prix illisible reste vide : il sera saisi à l'écran
        }
    }

    private static void writeSheet(Sheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<String> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                row.createCell(c).setCellValue(values.get(c));
            }
        }
        if (sheet instanceof org.apache.poi.xssf.usermodel.XSSFSheet xssf) {
            for (int c = 0; c < 2; c++) xssf.setColumnWidth(c, 60 * 256);
        }
    }
}
