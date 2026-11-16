#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mise en page PDF du contrat de maintenance (A4).

Le backend Java lance ce script comme processus externe : il recoit sur stdin le contrat deja
tout pret (textes, dates, montants deja formates par Java) et ecrit le PDF au chemin passe en
--out. Seul le travail de mise en page vit ici : Java calcule, Python pose sur la page. Le
script ne calcule rien et n'invente rien.

Usage :
    python render_contract_pdf.py --out sortie.pdf   < contrat.json

Reponse sur stdout :
    {"ok": true, "bytes": 12345, "pages": 2}
    {"ok": false, "kind": "config|input|internal", "error": "..."}
Sortie : 0 si ok, 1 sinon.

Dependance : reportlab (python -m pip install reportlab). Si elle manque, kind="config" et le
backend affiche la commande a lancer ; l'application demarre normalement sans elle.
"""

import argparse
import json
import os
import sys

try:
    from reportlab.lib import colors
    from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_RIGHT
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import (BaseDocTemplate, Frame, KeepTogether, PageTemplate, Paragraph,
                                    Spacer, Table, TableStyle)
    REPORTLAB_OK = True
    REPORTLAB_ERROR = ""
except Exception as exc:  # noqa: BLE001 - import casse ou version incoherente : meme traitement
    REPORTLAB_OK = False
    REPORTLAB_ERROR = str(exc)

MARGIN = 16.0
SIGN_HEIGHT = 18.0
RED = "#b91c1c"
GREY = "#666666"
LIGHT = "#cccccc"
BORDER = "#bbbbbb"
HEAD_BG = "#f3f4f6"


def styles():
    """Feuilles de style du document : le contrat se lit, il ne se relit pas deux fois."""
    body = ParagraphStyle("body", fontName="Helvetica", fontSize=9.8, leading=13.0,
                          alignment=TA_JUSTIFY, spaceAfter=2)
    return {
        "body": body,
        "brand": ParagraphStyle("brand", parent=body, fontName="Helvetica-Bold", fontSize=15,
                                leading=18, textColor=colors.HexColor(RED), spaceAfter=0),
        "plain": ParagraphStyle("plain", parent=body, fontSize=9.5, leading=13, spaceAfter=1,
                                alignment=0),
        "number": ParagraphStyle("number", parent=body, fontSize=9.5, leading=13,
                                 alignment=TA_RIGHT, spaceAfter=0),
        "article": ParagraphStyle("article", parent=body, fontName="Helvetica-Bold", fontSize=10.5,
                                  leading=13, textColor=colors.HexColor(RED),
                                  spaceBefore=6, spaceAfter=2, alignment=0),
        "head": ParagraphStyle("head", parent=body, fontSize=9, leading=12, spaceAfter=2, alignment=0),
        "cell": ParagraphStyle("cell", parent=body, fontSize=8.5, leading=11, spaceAfter=0, alignment=0),
        "price": ParagraphStyle("price", parent=body, fontSize=8.5, leading=11, spaceAfter=0,
                                alignment=TA_RIGHT),
        "total": ParagraphStyle("total", parent=body, fontSize=10, leading=13, alignment=TA_RIGHT),
        "words": ParagraphStyle("words", parent=body, fontName="Helvetica-Oblique", fontSize=9.5,
                                leading=13, spaceBefore=5, alignment=0),
        "sign_name": ParagraphStyle("sign_name", parent=body, fontName="Helvetica-Bold", fontSize=9.5,
                                    leading=12, alignment=TA_CENTER, spaceBefore=2),
        "sign": ParagraphStyle("sign", parent=body, fontSize=9, leading=12, alignment=TA_CENTER),
    }


def esc(text):
    """Un texte de contrat peut contenir &, < ou > : il est sur avant le balisage interne."""
    return ("" if text is None else str(text)).replace("&", "&amp;").replace("<", "&lt;") \
        .replace(">", "&gt;")


def nbsp(text):
    """Un montant ne se coupe pas en deux : ses espaces deviennent insécables."""
    return str(text).replace(" ", "\u00a0")


def banner(payload, st, width):
    left = [Paragraph(esc(payload.get("brand", "")), st["brand"])]
    if payload.get("subtitle"):
        left.append(Paragraph(esc(payload["subtitle"]), st["plain"]))
    right = [Paragraph("<b>%s</b>" % esc(payload.get("number", "")), st["number"]),
             Paragraph(esc(payload.get("date_label", "")), st["number"])]
    table = Table([[left, right]], colWidths=[width * 0.62, width * 0.38])
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "BOTTOM"),
        ("LINEBELOW", (0, 0), (-1, -1), 2.2, colors.HexColor(RED)),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return table


def parties(payload, st, width, gap):
    boxes = []
    for party in payload.get("parties") or []:
        blocks = [Paragraph("<b>%s</b>" % esc(party.get("heading", "")), st["head"])]
        blocks += [Paragraph(esc(text), st["plain"]) for text in party.get("lines") or []]
        boxes.append(blocks)
    if not boxes:
        return None
    table = Table([boxes], colWidths=[(width - gap) / 2.0] * 2)
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor(LIGHT)),
        ("INNERGRID", (0, 0), (-1, -1), 0.8, colors.HexColor(LIGHT)),
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#fafafa")),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
    ]))
    return table


def column_weights(columns):
    """Largeurs relatives : VIN, modèle et prix sont les colonnes qui débordent."""
    weights = []
    for name in columns:
        low = str(name).lower()
        if "vin" in low or "chassis" in low or "châssis" in low:
            weights.append(2.5)
        elif "modèle" in low or "modele" in low:
            weights.append(2.0)
        elif "prix" in low:
            weights.append(1.9)
        elif "garantie" in low or "circ" in low:
            weights.append(1.5)
        elif "année" in low or "annee" in low:
            weights.append(1.0)
        elif low.startswith("réf") or low.startswith("ref"):
            weights.append(0.75)
        else:
            weights.append(1.4)
    return weights


def annex(payload, st, width):
    data = payload.get("annex") or {}
    out = []
    if data.get("heading"):
        out.append(Paragraph(esc(data["heading"]), st["article"]))
    columns = data.get("columns") or []
    rows = data.get("rows") or []
    if not columns:
        return out

    weights = column_weights(columns)
    total = float(sum(weights))
    col_widths = [width * w / total for w in weights]
    header = [Paragraph("<b>%s</b>" % esc(c), st["cell"]) for c in columns]

    if rows:
        body_rows = []
        for row in rows:
            cells = []
            for index in range(len(columns)):
                value = row[index] if index < len(row) else ""
                amount = str(value).strip().endswith("DT")
                cells.append(Paragraph(esc(nbsp(value)) if amount else esc(value),
                                       st["price"] if amount else st["cell"]))
            body_rows.append(cells)
        table = Table([header] + body_rows, colWidths=col_widths, repeatRows=1)
    else:
        # Aucun bus rattache : le message vient du serveur, sur toute la largeur du tableau.
        note = Paragraph(esc(data.get("note", "")), st["cell"])
        table = Table([[header], [note]], colWidths=col_widths, repeatRows=1)
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor(BORDER)),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor(HEAD_BG)),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ] + ([] if rows else [("SPAN", (0, 1), (-1, 1))])))
    out.append(table)
    return out


def totals(payload, st, width):
    data = payload.get("totals") or {}
    out = []
    rows = data.get("rows") or []
    if rows:
        table_rows = []
        extra = []
        for index, pair in enumerate(rows):
            label = str(pair[0])
            value = str(pair[1])
            ttc = "ttc" in label.lower()
            table_rows.append([
                Paragraph(("<b>%s</b>" % esc(label)) if ttc else esc(label), st["cell"]),
                Paragraph(("<b>%s</b>" % esc(nbsp(value))) if ttc else esc(nbsp(value)), st["total"]),
            ])
            if ttc:
                extra.append(("LINEABOVE", (0, index), (-1, index), 1.1, colors.black))
        table = Table(table_rows, colWidths=[width * 0.45, width * 0.35], hAlign="RIGHT")
        table.setStyle(TableStyle([
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 0),
            ("RIGHTPADDING", (0, 0), (-1, -1), 0),
            ("TOPPADDING", (0, 0), (-1, -1), 3),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ] + extra))
        out.append(Spacer(1, 6))
        out.append(table)
    if data.get("words"):
        out.append(Paragraph("<i>%s</i>" % esc(data["words"]), st["words"]))
    return out


def signature_box(width):
    """Cadre vide : la place du cachet et de la signature manuscrite."""
    table = Table([[""]], colWidths=[width], rowHeights=[SIGN_HEIGHT * mm])
    table.setStyle(TableStyle([("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor("#999999"))]))
    return table


def signatures(payload, st, width, gap):
    parties_list = (payload.get("signatures") or [])[:2]
    if not parties_list:
        return []
    cell_width = (width - gap) / 2.0
    # Cadre et légende dans la MÊME cellule : sinon le tableau se coupe et la légende
    # tombe seule sur la page suivante, ce qui ne se signe pas.
    cells = [[signature_box(cell_width),
              Paragraph(esc(party.get("name", "")), st["sign_name"]),
              Paragraph(esc(party.get("label", "")), st["sign"])] for party in parties_list]
    while len(cells) < 2:
        cells.append([])
    table = Table([cells], colWidths=[cell_width] * 2)
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("TOPPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
    ]))
    return [KeepTogether([table])]


def render(payload, out_path):
    st = styles()
    footer = payload.get("footer") or ""

    def on_page(canvas, document):
        if not footer:
            return
        canvas.saveState()
        base = MARGIN * mm - 4
        canvas.setStrokeColor(colors.HexColor(LIGHT))
        canvas.setLineWidth(0.6)
        canvas.line(MARGIN * mm, base + 9 * mm, A4[0] - MARGIN * mm, base + 9 * mm)
        canvas.setFont("Helvetica", 7.5)
        canvas.setFillColor(colors.HexColor(GREY))
        canvas.drawCentredString(A4[0] / 2.0, base + 4 * mm, footer)
        canvas.restoreState()

    doc = BaseDocTemplate(out_path, pagesize=A4,
                          leftMargin=MARGIN * mm, rightMargin=MARGIN * mm,
                          topMargin=MARGIN * mm, bottomMargin=(MARGIN + 8) * mm,
                          title="Contrat %s" % payload.get("number", ""),
                          author=payload.get("brand", ""),
                          subject="Contrat de maintenance de vehicules")
    width = doc.width
    frame = Frame(doc.leftMargin, doc.bottomMargin, width, doc.height, id="content")
    doc.addPageTemplates([PageTemplate(id="page", frames=[frame], onPage=on_page)])

    gap = 6 * mm
    story = [banner(payload, st, width), Spacer(1, 4 * mm)]
    parties_table = parties(payload, st, width, gap)
    if parties_table is not None:
        story.append(parties_table)
    for clause in payload.get("clauses") or []:
        story.append(Paragraph(esc(clause.get("title", "")), st["article"]))
        story.append(Paragraph(esc(clause.get("body", "")), st["body"]))
    story.extend(annex(payload, st, width))
    totals_block = totals(payload, st, width)
    if totals_block:
        # Le montant en toutes lettres ne se sépare jamais du total qu'il épelle.
        story.append(KeepTogether(totals_block))
    story.append(Spacer(1, 3 * mm))
    story.extend(signatures(payload, st, width, gap))
    doc.build(story)
    return doc.page


def respond(result):
    sys.stdout.buffer.write(json.dumps(result, ensure_ascii=False).encode("utf-8"))
    sys.stdout.buffer.flush()
    return 0 if result.get("ok") else 1


def fail(kind, message):
    return respond({"ok": False, "kind": kind, "error": message})


def main():
    parser = argparse.ArgumentParser(description="Mise en page PDF du contrat de maintenance.")
    parser.add_argument("--out", required=True, help="chemin du PDF a ecrire")
    args = parser.parse_args()

    raw = sys.stdin.buffer.read().decode("utf-8", "replace")
    try:
        payload = json.loads(raw)
    except ValueError:
        return fail("input", "Le script n'a pas reussi a lire le contrat envoye par le backend.")
    if not isinstance(payload, dict) or not payload.get("number"):
        return fail("input", "Donnees de contrat incompletes : le numero manque.")

    if not REPORTLAB_OK:
        return fail("config",
                    "reportlab est absent du Python utilise (%s). Lance : "
                    "python -m pip install reportlab, puis relance le backend." % REPORTLAB_ERROR[:120])
    try:
        pages = render(payload, args.out)
    except Exception as exc:  # noqa: BLE001 - la cause doit remonter a l'utilisateur, pas une pile
        return fail("internal", "La mise en page du PDF a echoue : %s" % str(exc)[:200])

    if not os.path.exists(args.out):
        return fail("internal", "Le script n'a ecrit aucun fichier PDF.")
    return respond({"ok": True, "bytes": os.path.getsize(args.out), "pages": int(pages)})


if __name__ == "__main__":
    sys.exit(main())
