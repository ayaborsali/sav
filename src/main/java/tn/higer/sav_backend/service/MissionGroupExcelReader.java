package tn.higer.sav_backend.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Lecture locale uniquement : aucun évaluateur de formules ni résolution de liens externes. */
final class MissionGroupExcelReader {
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String XLS = "application/vnd.ms-excel";
    private static final Set<String> HEADERS = Set.of("tache", "taches", "tache a faire", "taches a faire",
            "operation", "operations", "travaux", "description", "libelle");
    private static final Set<String> PREFERRED_SHEETS = Set.of("taches", "travaux", "operations");
    private static final String ADD_COLUMN = "Ajoutez une colonne « Tâche » dans les 20 premières lignes, avec une tâche par ligne.";

    private MissionGroupExcelReader() {}

    /** null pour un autre format ; une extension Excel impose un MIME compatible. */
    static String contentType(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String expected = name.endsWith(".xlsx") ? XLSX : name.endsWith(".xls") ? XLS : null;
        String mime = file.getContentType() == null ? "" : file.getContentType().strip().toLowerCase(Locale.ROOT);
        if (expected == null) {
            if (XLS.equals(mime) || XLSX.equals(mime)) {
                throw bad("Format Excel refusé : choisissez un fichier nommé .xlsx ou .xls.");
            }
            return null;
        }
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime) && !expected.equals(mime)) {
            throw bad("Format Excel refusé : le type MIME ne correspond pas à l'extension .xlsx ou .xls.");
        }
        return expected;
    }

    /** Ne fait jamais confiance à la taille annoncée par MultipartFile. */
    static byte[] readBounded(MultipartFile file, long maxBytes) {
        int limit = (int) Math.min(maxBytes, 10_000_000L);
        if (file.getSize() > limit) {
            throw tooLarge();
        }
        try (InputStream in = file.getInputStream()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw tooLarge();
            }
            if (bytes.length == 0) {
                throw bad("Le fichier reçu est vide : choisissez une fiche contenant du texte.");
            }
            return bytes;
        } catch (IOException e) {
            throw bad("Le fichier n'a pas pu être lu pendant l'envoi. Réessayez.");
        }
    }

    static void validate(byte[] bytes, String type) {
        try (Workbook ignored = open(bytes, type)) {
            // La pièce originale n'a pas à contenir une colonne de tâches.
        } catch (IOException | RuntimeException e) {
            throw safeError(e);
        }
    }

    static List<String> tasks(byte[] bytes, String type) {
        try (Workbook workbook = open(bytes, type)) {
            Sheet sheet = chooseSheet(workbook);
            if (sheet.getLastRowNum() >= 1000) {
                throw bad("La feuille Excel dépasse 1 000 lignes : simplifiez-la avant l'import.");
            }
            DataFormatter formatter = new DataFormatter(Locale.FRENCH);
            int characters = 0;
            int taskColumn = -1;
            List<String> tasks = new ArrayList<>();
            for (int index = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                if (row.getLastCellNum() > 64) {
                    throw bad("La feuille Excel dépasse 64 colonnes : simplifiez-la avant l'import.");
                }
                if (taskColumn < 0) {
                    if (index >= 20) {
                        throw bad(ADD_COLUMN);
                    }
                    for (int column = 0; column < row.getLastCellNum(); column++) {
                        Cell cell = row.getCell(column);
                        // Une formule n'est jamais utilisée pour reconnaître un en-tête.
                        if (cell == null || cell.getCellType() == CellType.FORMULA) {
                            continue;
                        }
                        String text = formatter.formatCellValue(cell);
                        characters = countCharacters(characters, text);
                        if (HEADERS.contains(normalize(text))) {
                            taskColumn = column;
                            break;
                        }
                    }
                    continue;
                }
                Cell cell = row.getCell(taskColumn);
                if (cell == null) {
                    continue;
                }
                if (cell.getCellType() == CellType.FORMULA) {
                    throw bad("Une formule est présente dans la colonne des tâches : remplacez les formules par du texte avant l'import.");
                }
                if (cell.getCellType() == CellType.ERROR) {
                    throw bad("Une cellule de la colonne des tâches contient une erreur Excel : remplacez-la par du texte.");
                }
                String text = formatter.formatCellValue(cell);
                characters = countCharacters(characters, text);
                String label = text.replace('\u00a0', ' ').strip();
                if (label.isBlank()) {
                    continue;
                }
                if (label.length() > 300) {
                    throw bad("Une tâche dépasse 300 caractères : séparez ou simplifiez les tâches avant l'import.");
                }
                if (tasks.size() == 200) {
                    throw bad("Le fichier Excel contient plus de 200 tâches : simplifiez-le avant l'import.");
                }
                tasks.add(label);
            }
            if (taskColumn < 0) {
                throw bad(ADD_COLUMN);
            }
            if (tasks.isEmpty()) {
                throw bad("Aucune tâche trouvée : ajoutez une tâche par ligne sous la colonne « Tâche ».");
            }
            return List.copyOf(tasks);
        } catch (IOException | RuntimeException e) {
            throw safeError(e);
        }
    }

    private static Workbook open(byte[] bytes, String type) throws IOException {
        Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        if ((XLS.equals(type) && workbook instanceof HSSFWorkbook)
                || (XLSX.equals(type) && workbook instanceof XSSFWorkbook)) {
            return workbook;
        }
        workbook.close();
        throw bad("Le contenu Excel ne correspond pas à l'extension .xlsx ou .xls : fournissez le fichier d'origine avec la bonne extension.");
    }

    private static Sheet chooseSheet(Workbook workbook) {
        if (workbook.getNumberOfSheets() > 20) {
            throw bad("Le classeur Excel dépasse 20 feuilles : simplifiez-le avant l'import.");
        }
        Sheet fallback = null;
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) {
                continue;
            }
            Sheet sheet = workbook.getSheetAt(index);
            String name = normalize(sheet.getSheetName());
            if (PREFERRED_SHEETS.contains(name)) {
                return sheet;
            }
            if (fallback == null && !Set.of("notice", "exemple").contains(name)) {
                fallback = sheet;
            }
        }
        if (fallback == null) {
            throw bad("Aucune feuille de tâches visible : affichez une feuille autre que Notice ou Exemple.");
        }
        return fallback;
    }

    private static int countCharacters(int count, String text) {
        if (text.length() > 100_000 - count) {
            throw bad("La lecture Excel dépasse 100 000 caractères : simplifiez le fichier avant l'import.");
        }
        return count + text.length();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static ResponseStatusException safeError(Exception e) {
        return e instanceof ResponseStatusException response ? response
                : bad("Classeur Excel invalide, illisible ou protégé : fournissez un fichier .xlsx ou .xls valide sans protection.");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Fichier trop volumineux : respectez la limite d'envoi (10 Mo maximum).");
    }
}
