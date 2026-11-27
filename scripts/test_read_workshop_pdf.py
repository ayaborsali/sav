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

    def test_printed_work_order_only_yields_the_tasks(self):
        # L'ordre de travail imprimé par le serveur : trois tableaux, des titres, un pied de page.
        tasks = parse_tasks([[
            "Le papier", "Tâches à cocher MO-1",
            "Travaux à effectuer",
            "N° Tâches Temps alloué Réalisé Observations",
            "1 OP-1 — Vidange moteur 0,9 h 1,25 Fait",
            "2 OP-2 — Dépose boîte de vitesses — — —",
            "3 OP-3 — Contrôle des niveaux et des fuites",
            "Visibles sur le compartiment",
            "Pièces de rechange",
            "N° Désignation Référence Prévue Consommée",
            "Aucune pièce sur cet ordre.",
            "Groupe d'intervention",
            "N° Nom et prénom Fonction",
            "1 Yassine Ben Salem Technicien", "2", "3",
            "Ce qui est écrit sur le papier", "Sachs et roulement pilote volant moteur.",
            "Anomalies constatées sur le bus pendant le travail",
            "Rien n'a été écrit pour cette partie.",
        ]])
        self.assertEqual(tasks, [
            "OP-1 — Vidange moteur",
            "OP-2 — Dépose boîte de vitesses",
            "OP-3 — Contrôle des niveaux et des fuites Visibles sur le compartiment",
        ])

    def test_real_printed_work_order_pdf(self):
        import json
        import subprocess
        import sys
        payload = {
            "kind": "Ordre de travail", "type": "operation", "brand": "Saphir", "brand_lines": ["Sousse"],
            "subtitle": "Ordre de travail", "number": "OR-1", "status_label": "État : À faire",
            "date_label": "Édité le 27/11/2026", "badge": "Papier interne", "boxes": [],
            "tables": [
                {"heading": "Travaux à effectuer",
                 "columns": ["N°", "Tâches", "Temps alloué", "Réalisé", "Observations"],
                 "weights": [0.4, 3.2, 0.9, 0.8, 1.0],
                 "rows": [["1", "OP-1 — Vidange moteur", "0,9 h", "1,25", "Fait"],
                          ["2", "MO-MEC-01 — Main d'œuvre qualification constructeur (6h)", "—", "—", "—"]],
                 "note": "Aucune tâche sur cet ordre."},
                {"heading": "Pièces de rechange",
                 "columns": ["N°", "Désignation", "Référence", "Prévue", "Consommée"],
                 "weights": [0.4, 3.0, 1.2, 0.8, 0.9], "rows": [], "note": "Aucune pièce sur cet ordre."},
                {"heading": "Groupe d'intervention", "columns": ["N°", "Nom et prénom", "Fonction"],
                 "weights": [0.4, 3.0, 1.6],
                 "rows": [["1", "Yassine Ben Salem", "Technicien"], ["2", "", ""], ["3", "", ""]], "note": ""},
            ],
            "texts": [{"heading": "Ce qui est écrit sur le papier", "body": "Sachs et roulement."}],
            "signatures": [{"name": "Yassine Ben Salem", "label": "L'atelier"}],
            "footer": "Saphir — aucun montant ne figure sur ce papier",
        }
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / "ordre.pdf"
            subprocess.run([sys.executable, str(Path(__file__).with_name("render_maintenance_pdf.py")),
                            "--out", str(out)], input=json.dumps(payload).encode("utf-8"), check=True,
                           capture_output=True)
            preview = read_pdf(out)
        self.assertEqual(preview["tasks"], [
            "OP-1 — Vidange moteur",
            "MO-MEC-01 — Main d'œuvre qualification constructeur (6h)",
        ])


if __name__ == "__main__":
    unittest.main()
