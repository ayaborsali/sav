import json
import re
import sys
import unicodedata
from pathlib import Path


MAX_BYTES = 10_000_000
MAX_PAGES = 20
MAX_TEXT = 100_000
MAX_TASKS = 200
MAX_LABEL = 300
MESSAGES = {
    "invalid_pdf": "PDF malformé ou illisible : exportez un nouveau PDF contenant du texte.",
    "encrypted": "PDF chiffré ou protégé : fournissez un PDF sans protection.",
    "no_text": "Le PDF contient une page sans texte ou scannée. L'OCR n'est pas réalisé : fournissez un PDF avec du texte sélectionnable.",
    "pages": "Le PDF dépasse 20 pages : simplifiez-le avant l'import.",
    "text": "Le PDF dépasse 100 000 caractères de texte : simplifiez-le avant l'import.",
    "task_length": "Une tâche dépasse 300 caractères : simplifiez le PDF ou séparez les tâches avant l'import.",
    "task_count": "Le PDF contient plus de 200 tâches : simplifiez-le avant l'import.",
    "no_tasks": "Aucune tâche identifiable : fournissez un PDF avec une liste de tâches à relire.",
    "size": "PDF trop volumineux : compressez-le pour respecter la limite d'envoi (10 Mo maximum).",
    "config": "La lecture locale du PDF est indisponible. Faites vérifier Python et pypdfium2 par l'administrateur.",
    "internal": "La lecture locale du PDF n'a pas répondu correctement. Réessayez.",
}


class ImportFailure(Exception):
    def __init__(self, kind):
        super().__init__(kind)
        self.kind = kind


def folded(text):
    return "".join(c for c in unicodedata.normalize("NFKD", text.casefold())
                   if not unicodedata.combining(c)).replace("’", "'")


SECTION = re.compile(
    r"^(?:(?:liste|detail|description)\s+(?:des\s+)?)?(?:taches|travaux|operations)"
    r"(?:\s+(?:a\s+(?:faire|effectuer|realiser|executer)|prevu(?:e)?s?|demande(?:e)?s?|"
    r"d'entretien|de maintenance))?\s*:?$"
)
STOP = re.compile(
    r"^(?:signatures?|diagnostic(?:\s*[/&-]\s*observations?)?|observations?|"
    r"pieces?\s+de\s+rechange|groupe\s+d'intervention|ce\s+qui\s+est\s+ecrit\s+sur\s+le\s+papier|"
    r"anomalies(?:\s+constatees.*)?|raison\s+ecrite.*|etat\s+de\s+la\s+cloture|"
    r"remarques?|commentaires?|validation|visa|reception|compte[- ]rendu)"
    r"(?:\s+(?:du|de la|de|des)\s+[^:]+)?\s*(?::.*)?$"
)
META = re.compile(
    r"^(?:(?:n[°ºo]?\s*)?(?:dossier|ordre|fiche|reference)|client|bus|vehicule|"
    r"technicien|date|heure|immatriculation|vin|kilometrage|modele|marque|atelier|"
    r"adresse|telephone|tel|e-?mail|responsable)\s*(?::|n[°º])"
)
TITLE = re.compile(r"^(?:fiche atelier|fiche (?:de )?(?:travail|intervention)|ordre de travail|"
                   r"document de (?:test|travail)|check[- ]?list|feuille de travaux)(?:\b.*)?$")
# L'ordre de travail imprimé par le serveur : « N° | Tâches | Temps alloué | Réalisé | Observations ».
# Son en-tête n'est pas une tâche, le numéro de ligne n'en fait pas partie, et les trois colonnes
# de droite (temps alloué, réalisé, observations) non plus.
ORDER_HEADER = re.compile(r"^n[°º]?\s+taches\s+temps\s+alloue\b.*$")
ORDER_COLUMNS = re.compile(
    r"(?:^|\s+)(?:—|-|\d[\d.,]*\s*(?:h|min|j)?)\s+(?:—|-|\d+,\d{2})\s+(?:—|-|fait|pas fait)$",
    re.IGNORECASE)
ORDER_ROW = re.compile(r"^\d+\s+\S")
PAGE = re.compile(r"^(?:page\s*)?\d+\s*(?:/|sur)\s*\d+$|^page\s+\d+$|^[-–—]\s*\d+\s*[-–—]$")
CHECK = r"(?:\[\s*[xX✓✔]?\s*\]|\(\s*[xX✓✔]?\s*\)|[☐☑☒□■✓✔√])"
MARKER = re.compile(r"^\s*(?:" + CHECK + r"|[•●◦▪‣⁃*\-–—]|(?:\d+(?:\.\d+)*|[a-zA-Z])\s*[.)]|\(\d+\))\s*")
STATUS_ONLY = re.compile(r"^(?:" + CHECK + r"|[xX])$")


def table_header(text):
    words = re.findall(r"\w+", folded(text))
    return bool(words) and all(w in {"fait", "tache", "taches", "operation", "operations", "travaux"}
                               for w in words)


def layout_lines(textpage):
    # Les colonnes peuvent être écrites séparément dans le flux PDF : regrouper par position.
    characters = []
    for i in range(textpage.count_chars()):
        char = textpage.get_text_range(i, 1)
        if not char or char in "\r\n" or char == "\x00":
            continue
        left, bottom, right, top = textpage.get_charbox(i, loose=True)
        if top <= bottom:
            continue
        characters.append((left, (top + bottom) / 2, top - bottom, right, char))
    characters.sort(key=lambda c: (-c[1], c[0]))
    rows = []
    for char in characters:
        if rows and abs(char[1] - rows[-1][0]) <= max(2, char[2] * 0.35):
            rows[-1][1].append(char)
        else:
            rows.append((char[1], [char]))
    origin = min((c[0] for c in characters), default=0)
    lines = []
    for _, row in rows:
        row.sort(key=lambda c: c[0])
        result = " " * max(0, round((row[0][0] - origin) / 4))
        previous_right = None
        for left, _, height, right, char in row:
            if previous_right is not None and left - previous_right > max(2, height * 0.25):
                result += " "
            result += char
            previous_right = right
        lines.append(result.rstrip())
    return lines


def label_length(label):
    return len(label.encode("utf-16-le")) // 2


def parse_tasks(pages):
    all_lines = [line for page in pages for line in page]
    has_sections = any(SECTION.fullmatch(folded(line.strip())) for line in all_lines)
    has_lists = any(MARKER.match(line) for line in all_lines)
    has_table = any(table_header(line.strip()) and "fait" in folded(line) for line in all_lines)
    tasks = []
    active = not has_sections
    stopped = False
    table = False
    ordre = False
    status_last = False
    pending = None
    pending_indent = 0
    marked = False

    def flush():
        nonlocal pending, marked
        if pending:
            if label_length(pending) > MAX_LABEL:
                raise ImportFailure("task_length")
            tasks.append(pending)
            if len(tasks) > MAX_TASKS:
                raise ImportFailure("task_count")
        pending = None
        marked = False

    for line in all_lines:
        raw = line.rstrip()
        text = raw.strip()
        key = folded(text)
        indent = len(raw) - len(raw.lstrip())
        if not text:
            continue
        if SECTION.fullmatch(key):
            flush()
            active, stopped, table, ordre = True, False, False, False
            continue
        if STOP.fullmatch(key):
            flush()
            active, stopped, table, ordre = False, True, False, False
            continue
        if META.match(key) or TITLE.match(key) or PAGE.fullmatch(key) or re.fullmatch(r"[\W_]+", text):
            continue
        if ORDER_HEADER.match(key):
            flush()
            ordre = True
            continue
        if table_header(text):
            flush()
            if "fait" in key:
                table = True
                status_last = key.endswith("fait")
                if not has_sections and not stopped:
                    active = True
            continue
        if STATUS_ONLY.fullmatch(text):
            # Une case isolée n'est pas une tâche ; le texte de sa ligne est conservé.
            continue
        if stopped or not active:
            continue
        if ordre:
            text = ORDER_COLUMNS.sub("", text).strip()
            if not text:
                continue
        marker = MARKER.match(text)
        if marker:
            flush()
            while marker:
                text = text[marker.end():].strip()
                marker = MARKER.match(text)
            marked = True
        elif ordre and ORDER_ROW.match(text):
            flush()
            text = re.sub(r"^\d+\s+", "", text)
            marked = True
        elif table and re.match(r"^[xX]\s+", text):
            flush()
            text = text[1:].strip()
            marked = True
        elif pending and (marked or indent > pending_indent + 1):
            pending += " " + text
            continue
        else:
            flush()
            if not has_sections and (has_lists or has_table) and not table:
                continue
        if table and status_last:
            text = re.sub(r"\s+(?:" + CHECK + r"|[xX])$", "", text).rstrip()
        if text:
            pending = text
            pending_indent = indent
    flush()
    if not tasks:
        raise ImportFailure("no_tasks")
    return tasks


def read_pdf(path):
    try:
        import pypdfium2 as pdfium
        import pypdfium2.raw as raw
    except ImportError:
        raise ImportFailure("config") from None
    try:
        source = Path(path)
        if source.stat().st_size > MAX_BYTES:
            raise ImportFailure("size")
        with source.open("rb") as stream:
            if stream.read(5) != b"%PDF-":
                raise ImportFailure("invalid_pdf")
        document = pdfium.PdfDocument(source)
    except pdfium.PdfiumError as exc:
        raise ImportFailure("encrypted" if exc.err_code == raw.FPDF_ERR_PASSWORD else "invalid_pdf") from None
    except OSError:
        raise ImportFailure("invalid_pdf") from None
    try:
        if raw.FPDF_GetSecurityHandlerRevision(document.raw) >= 0:
            raise ImportFailure("encrypted")
        count = len(document)
        if count > MAX_PAGES:
            raise ImportFailure("pages")
        if count < 1:
            raise ImportFailure("invalid_pdf")
        pages = []
        total = 0
        for index in range(count):
            page = document[index]
            try:
                textpage = page.get_textpage()
                try:
                    # Contrôle avant allocation du texte complet de la page.
                    if textpage.count_chars() + total > MAX_TEXT:
                        raise ImportFailure("text")
                    text = textpage.get_text_range()
                    total += len(text)
                    if total > MAX_TEXT:
                        raise ImportFailure("text")
                    if not text.strip():
                        raise ImportFailure("no_text")
                    key = folded(text)
                    if re.search(r"\bfait\b", key) and re.search(r"\b(?:taches?|operations?|travaux)\b", key):
                        pages.append(layout_lines(textpage))
                    else:
                        pages.append(text.replace("\r\n", "\n").replace("\r", "\n").split("\n"))
                finally:
                    textpage.close()
            finally:
                page.close()
        return {"ok": True, "tasks": parse_tasks(pages), "pageCount": count}
    except pdfium.PdfiumError:
        raise ImportFailure("invalid_pdf") from None
    finally:
        document.close()


def main(argv):
    try:
        if len(argv) != 2:
            raise ImportFailure("invalid_pdf")
        result = read_pdf(argv[1])
    except ImportFailure as exc:
        result = {"ok": False, "kind": exc.kind, "error": MESSAGES[exc.kind]}
    except Exception:
        result = {"ok": False, "kind": "internal", "error": MESSAGES["internal"]}
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main(sys.argv)
