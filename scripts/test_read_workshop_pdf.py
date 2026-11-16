import io
import tempfile
import unittest
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import SimpleDocTemplate, Paragraph, Table, TableStyle
from reportlab.pdfgen import canvas

from read_workshop_pdf import ImportFailure, parse_tasks, read_pdf


class WorkshopPdfTest(unittest.TestCase):
    def read(self, content):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fiche.pdf"
            path.write_bytes(content)
            return read_pdf(path)

    def test_numbered_tasks_and_wrapped_lines(self):
        tasks = parse_tasks([["Client : Exemple", "Travaux à réaliser",
                              "1. Remplacer le filtre", "   après vérification de sa référence",
                              "2. Contrôler le freinage", "Observations", "Ne pas importer cette note"]])
        self.assertEqual(tasks, ["Remplacer le filtre après vérification de sa référence", "Contrôler le freinage"])

    def test_bullets_and_plain_tasks(self):
        self.assertEqual(parse_tasks([["Opérations", "• Graissage des cardans", "• Essai des feux"]]),
                         ["Graissage des cardans", "Essai des feux"])
        self.assertEqual(parse_tasks([["Tâches à faire", "Contrôler le radiateur", "Vérifier les durites"]]),
                         ["Contrôler le radiateur", "Vérifier les durites"])

    def test_rejects_truncation_and_excessive_count(self):
        for lines, kind in [(["Tâches", "x" * 301], "task_length"),
                            (["Tâches"] + [f"{i}. Tâche" for i in range(201)], "task_count")]:
            with self.assertRaises(ImportFailure) as error:
                parse_tasks([lines])
            self.assertEqual(error.exception.kind, kind)

    def test_real_table_with_status_column_and_wrapped_text(self):
        output = io.BytesIO()
        styles = getSampleStyleSheet()
        table = Table([
            ["Fait", "Tâche"],
            ["[ ]", Paragraph("Contrôler le refroidissement et toutes les durites visibles du compartiment moteur", styles["Normal"])],
            ["[x]", Paragraph("Essayer les éclairages", styles["Normal"])],
        ], colWidths=[35, 220])
        table.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP")]))
        SimpleDocTemplate(output).build([Paragraph("Tâches à faire", styles["Heading2"]), table])
        preview = self.read(output.getvalue())
        self.assertEqual(preview["tasks"], [
            "Contrôler le refroidissement et toutes les durites visibles du compartiment moteur",
            "Essayer les éclairages",
        ])
        self.assertEqual(preview["pageCount"], 1)

    def test_checkbox_last_column(self):
        output = io.BytesIO()
        SimpleDocTemplate(output).build([Table([
            ["Tâche", "Fait"], ["Contrôler les feux", "[ ]"], ["Remplacer le filtre", "[x]"],
        ], colWidths=[220, 35])])
        self.assertEqual(self.read(output.getvalue())["tasks"], ["Contrôler les feux", "Remplacer le filtre"])

    def test_no_text_malformed_and_too_many_pages(self):
        output = io.BytesIO()
        document = canvas.Canvas(output, pagesize=A4)
        document.showPage()
        document.save()
        for content, kind in [(output.getvalue(), "no_text"), (b"%PDF-corrupted", "invalid_pdf")]:
            with self.assertRaises(ImportFailure) as error:
                self.read(content)
            self.assertEqual(error.exception.kind, kind)
        output = io.BytesIO()
        document = canvas.Canvas(output)
        for _ in range(21):
            document.drawString(40, 700, "1. Verifier le bus")
            document.showPage()
        document.save()
        with self.assertRaises(ImportFailure) as error:
            self.read(output.getvalue())
        self.assertEqual(error.exception.kind, "pages")

    def test_headers_are_not_tasks(self):
        with self.assertRaises(ImportFailure) as error:
            parse_tasks([["FICHE ATELIER", "Client : Exemple", "Date : demain", "Signature du technicien : ..."]])
        self.assertEqual(error.exception.kind, "no_tasks")


if __name__ == "__main__":
    unittest.main()
