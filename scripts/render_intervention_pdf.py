#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mise en page PDF du bon d'intervention (A4).

Le backend Java lance ce script comme processus externe : il recoit sur stdin le document deja
tout pret (labels, heures, durees, montants deja formates par Java) et ecrit le PDF au chemin
passe en --out. Seul le travail de mise en page vit ici : Java calcule et decide quoi montrer,
Python ne pose que des valeurs sur la page. Le script ne calcule rien et n'invente rien.

Deux versions du MEMME document arrivent deja filtrees par le serveur : la version client ne
contient aucune ligne de paie. Le script ne connait pas ce filtre, il rend ce qu'on lui envoie ;
la confidentialite est donc decidee avant la mise en page, jamais pendant.

Usage :
    python render_intervention_pdf.py --out sortie.pdf   < bon.json

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
    from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
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

MARGIN = 15.0
SIGN_HEIGHT = 17.0
RED = "#b91c1c"
GREY = "#555555"
LIGHT = "#cccccc"
BORDER = "#bbbbbb"
HEAD_BG = "#eef2f7"
BOX_BG = "#fafafa"
BADGE_BG = "#fdf3f3"


def styles():
    """Feuilles de style : un bon d'intervention se remplit a la main, il doit rester aeré."""
    body = ParagraphStyle("body", fontName="Helvetica", fontSize=9.5, leading=12.5,
                          alignment=TA_JUSTIFY, spaceAfter=2)
    return {
        "body": body,
        "brand": ParagraphStyle("brand", parent=body, fontName="Helvetica-Bold", fontSize=14.5,
                                leading=17, textColor=colors.HexColor(RED), spaceAfter=0),
        "plain": ParagraphStyle("plain", parent=body, fontSize=9.5, leading=12.5, spaceAfter=1,
                                alignment=0),
        "number": ParagraphStyle("number", parent=body, fontName="Helvetica-Bold", fontSize=12,
                                 leading=15, alignment=2, spaceAfter=0),
        "right": ParagraphStyle("right", parent=body, fontSize=9, leading=12, alignment=2),
        "badge": ParagraphStyle("badge", parent=body, fontName="Helvetica-Bold", fontSize=9,
                                leading=12, textColor=colors.HexColor(RED), alignment=0,
                                spaceBefore=0, spaceAfter=0),
        "box_head": ParagraphStyle("box_head", parent=body, fontName="Helvetica-Bold", fontSize=9.5,
                                   leading=12, textColor=colors.HexColor(RED), spaceAfter=3,
                                   alignment=0),
        "cell": ParagraphStyle("cell", parent=body, fontSize=8.6, leading=11, spaceAfter=0,
                               alignment=0),
        "cell_right": ParagraphStyle("cell_right", parent=body, fontSize=8.6, leading=11,
                                     spaceAfter=0, alignment=2),
        "key": ParagraphStyle("key", parent=body, fontSize=8.8, leading=11.5, spaceAfter=0,
                              textColor=colors.HexColor(GREY), alignment=0),
        "value": ParagraphStyle("value", parent=body, fontName="Helvetica-Bold", fontSize=8.8,
                                leading=11.5, spaceAfter=0, alignment=0),
        "heading": ParagraphStyle("heading", parent=body, fontName="Helvetica-Bold", fontSize=10,
                                  leading=13, textColor=colors.HexColor(RED), spaceBefore=7,
                                  spaceAfter=2, alignment=0),
        "sign_name": ParagraphStyle("sign_name", parent=body, fontName="Helvetica-Bold", fontSize=9,
                                    leading=12, alignment=TA_CENTER, spaceBefore=2),
        "sign": ParagraphStyle("sign", parent=body, fontSize=8.6, leading=11, alignment=TA_CENTER),
    }


def esc(text):
    """Un nom de client ou un diagnostic peut contenir &, < ou > : on le sur avant le balisage."""
    return ("" if text is None else str(text)).replace("&", "&amp;").replace("<", "&lt;") \
        .replace(">", "&gt;")


def flat(text):
    """Une cellule de tableau ne garde pas les sauts de ligne : la page se mettrait de travers."""
    return " ".join(str("" if text is None else text).split())


def nbsp(text):
    """Un montant ou une heure ne se coupe pas en deux : ses espaces deviennent insécables."""
    return str(text).replace(" ", "\u00a0")


def banner(payload, st, width):
    left = [Paragraph(esc(payload.get("brand", "")), st["brand"])]
    if payload.get("subtitle"):
        left.append(Paragraph(esc(payload["subtitle"]), st["plain"]))
    right = [Paragraph(esc(payload.get("number", "")), st["number"])]
    if payload.get("date_label"):
        right.append(Paragraph(esc(payload["date_label"]), st["right"]))
    table = Table([[left, right]], colWidths=[width * 0.60, width * 0.40])
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "BOTTOM"),
        ("LINEBELOW", (0, 0), (-1, -1), 2.0, colors.HexColor(RED)),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 0),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    return table


def badge(payload, st, width):
    """Le filigrane, en une ligne : version interne ou version client. Le texte vient du serveur."""
    text = payload.get("badge")
    if not text:
        return None
    table = Table([[Paragraph(esc(text), st["badge"])]], colWidths=[width])
    table.setStyle(TableStyle([
        ("BOX", (0, 0), (-1, -1), 0.7, colors.HexColor(RED)),
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(BADGE_BG)),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
    ]))
    return table


def key_rows(rows, st, width):
    """Une fiche : libelle a gauche, valeur a droite, deux colonnes par ligne de tableau."""
    data = []
    for pair in rows:
        label = flat(pair[0] if len(pair) > 0 else "")
        value = flat(pair[1] if len(pair) > 1 else "")
        data.append([Paragraph(esc(label), st["key"]), Paragraph(esc(value), st["value"])])
    if not data:
        return None
    table = Table(data, colWidths=[width * 0.36, width * 0.64])
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
        ("RIGHTPADDING", (0, 0), (-1, -1), 2),
        ("TOPPADDING", (0, 0), (-1, -1), 1.5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 1.5),
    ]))
    return table


def boxes(payload, st, width, gap):
    """Les fiches de la sortie, deux par ligne : un bon se relit en diagonale."""
    items = payload.get("boxes") or []
    if not items:
        return []
    cell_width = (width - gap) / 2.0
    out = []
    for index in range(0, len(items), 2):
        cells = []
        for box in items[index:index + 2]:
            blocks = [Paragraph(esc(box.get("heading", "")), st["box_head"])]
            inner = key_rows(box.get("rows") or [], st, cell_width - 14)
            if inner is not None:
                blocks.append(inner)
            cells.append(blocks)
        while len(cells) < 2:
            cells.append([])
        table = Table([cells], colWidths=[cell_width] * 2)
        table.setStyle(TableStyle([
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("BOX", (0, 0), (-1, -1), 0.7, colors.HexColor(LIGHT)),
            ("INNERGRID", (0, 0), (-1, -1), 0.7, colors.HexColor(LIGHT)),
            ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(BOX_BG)),
            ("TOPPADDING", (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ("LEFTPADDING", (0, 0), (-1, -1), 7),
            ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ]))
        out.append(Spacer(1, 2 * mm))
        out.append(KeepTogether([table]))
    return out


def grid_table(data, st, width):
    """Un tableau de donnees : colonnes, lignes, largeurs transmises par le serveur."""
    columns = data.get("columns") or []
    rows = data.get("rows") or []
    if not columns:
        return None
    weights = data.get("weights") or [1.0] * len(columns)
    if len(weights) != len(columns):
        weights = [1.0] * len(columns)
    total = float(sum(weights)) or 1.0
    col_widths = [width * float(w) / total for w in weights]
    header = [Paragraph("<b>%s</b>" % esc(flat(c)), st["cell"]) for c in columns]
    style = [
        ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor(BORDER)),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor(HEAD_BG)),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]
    if rows:
        body = []
        for row in rows:
            body.append([Paragraph(esc(flat(row[i]) if i < len(row) else ""), st["cell"])
                         for i in range(len(columns))])
        table = Table([header] + body, colWidths=col_widths, repeatRows=1)
    else:
        # Aucune ligne : le message vient du serveur, sur toute la largeur.
        note = Paragraph(esc(data.get("note", "")), st["cell"])
        table = Table([header, [note]], colWidths=col_widths, repeatRows=1)
        style.append(("SPAN", (0, 1), (-1, 1)))
    table.setStyle(TableStyle(style))
    return table


def tables(payload, st, width):
    out = []
    for data in payload.get("tables") or []:
        if data.get("heading"):
            out.append(Paragraph(esc(data["heading"]), st["heading"]))
        table = grid_table(data, st, width)
        if table is not None:
            out.append(table)
    return out


def texts(payload, st, width):
    out = []
    for block in payload.get("texts") or []:
        body = str(block.get("body") or "")
        if not body.strip():
            continue
        if block.get("heading"):
            out.append(Paragraph(esc(block["heading"]), st["heading"]))
        # Un diagnostic saisi en plusieurs lignes doit rester lisible tel quel.
        for line in body.split("\n"):
            if line.strip():
                out.append(Paragraph(esc(line.strip()), st["body"]))
    return out


def signature_box(width):
    """Cadre vide : la place de la signature manuscrite et du cachet."""
    table = Table([[""]], colWidths=[width], rowHeights=[SIGN_HEIGHT * mm])
    table.setStyle(TableStyle([("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor("#999999"))]))
    return table


def signatures(payload, st, width, gap):
    parties = (payload.get("signatures") or [])[:3]
    if not parties:
        return []
    cell_width = (width - gap * (len(parties) - 1)) / float(len(parties))
    # Cadre et legende dans la MEME cellule : sinon la legende tombe seule sur la page suivante,
    # et une case sans nom au-dessus d'elle ne se signe pas.
    cells = [[signature_box(cell_width),
              Paragraph(esc(party.get("name", "")), st["sign_name"]),
              Paragraph(esc(party.get("label", "")), st["sign"])] for party in parties]
    table = Table([cells], colWidths=[cell_width] * len(parties))
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
        canvas.setFont("Helvetica", 7.3)
        canvas.setFillColor(colors.HexColor(GREY))
        canvas.drawCentredString(A4[0] / 2.0, base + 4 * mm, footer)
        canvas.restoreState()

    doc = BaseDocTemplate(out_path, pagesize=A4,
                          leftMargin=MARGIN * mm, rightMargin=MARGIN * mm,
                          topMargin=MARGIN * mm, bottomMargin=(MARGIN + 8) * mm,
                          title="Bon d'intervention %s" % payload.get("number", ""),
                          author=payload.get("brand", ""),
                          subject="Depannage sur site")
    width = doc.width
    frame = Frame(doc.leftMargin, doc.bottomMargin, width, doc.height, id="content")
    doc.addPageTemplates([PageTemplate(id="page", frames=[frame], onPage=on_page)])

    gap = 5 * mm
    story = [banner(payload, st, width)]
    mark = badge(payload, st, width)
    if mark is not None:
        story.append(Spacer(1, 3 * mm))
        story.append(mark)
    story.extend(boxes(payload, st, width, gap))
    story.extend(tables(payload, st, width))
    story.extend(texts(payload, st, width))
    story.append(Spacer(1, 6 * mm))
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
    parser = argparse.ArgumentParser(description="Mise en page PDF du bon d'intervention.")
    parser.add_argument("--out", required=True, help="chemin du PDF a ecrire")
    args = parser.parse_args()

    raw = sys.stdin.buffer.read().decode("utf-8", "replace")
    try:
        payload = json.loads(raw)
    except ValueError:
        return fail("input", "Le script n'a pas reussi a lire le bon envoye par le backend.")
    if not isinstance(payload, dict) or not payload.get("number"):
        return fail("input", "Donnees de bon incompletes : le numero de la sortie manque.")

    if not REPORTLAB_OK:
        return fail("config",
                    "reportlab est absent du Python utilise (%s). Lance : "
                    "python -m pip install reportlab, puis relance le backend." % REPORTLAB_ERROR[:120])
    try:
        pages = render(payload, args.out)
    except Exception as exc:  # noqa: BLE001 - la cause doit remonter a l'utilisateur, pas une pile
        return fail("internal", "La mise en page du bon d'intervention a echoue : %s" % str(exc)[:200])

    if not os.path.exists(args.out):
        return fail("internal", "Le script n'a ecrit aucun fichier PDF.")
    return respond({"ok": True, "bytes": os.path.getsize(args.out), "pages": int(pages)})


if __name__ == "__main__":
    sys.exit(main())
