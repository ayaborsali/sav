#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Lecture de l'horodatage EXIF d'une photo d'intervention.

Le backend Java lance ce script comme un processus externe, comme les autres scripts Python
du projet : le chemin du fichier arrive en argument, la réponse est une enveloppe JSON sur
stdout. Python ne lit que le fichier ; il ne décide rien. La décision (l'heure du serveur,
l'empreinte, le refus d'une image trop volumineuse, l'immuabilité après clôture) reste en Java.

Aucune dépendance : pas de Pillow, pas d'exifread. Les métadonnées EXIF d'un JPEG sont un
bloc TIFF dans le segment APP1, et ce bloc se lit avec le seul standard de Python. Une
bibliothèque de plus à installer sur le poste serait un obstacle au déploiement, alors que
le champ voulu est unique : « DateTimeOriginal ».

Usage :
    python read_exif.py --file chemin/vers/photo.jpg

Réponse sur stdout :
    {"ok": true, "taken_at": "2026-10-19T09:41:22", "camera": "SM-A155F", "orientation": 6}
    {"ok": true, "taken_at": null}                      <- fichier sans EXIF exploitable
    {"ok": false, "kind": "input|internal", "error": "..."}
Sortie : 0 si ok, 1 sinon.

« taken_at » est l'heure de l'appareil photo, pas celle du serveur : elle peut être fausse
(appareil mal réglé) ou absente (image recadrée, capture d'écran, PNG). C'est pourquoi Java
la garde à côté de son propre horodatage et non à la place.
"""

import argparse
import json
import sys

# Balises TIFF utiles.
TAG_DATETIME = 0x0132          # écriture logicielle de l'appareil
TAG_ORIENTATION = 0x0112
TAG_EXIF_IFD = 0x8769          # pointeur vers le sous-ifd « Exif »
TAG_MAKE = 0x010F
TAG_MODEL = 0x0110
TAG_DATETIME_ORIGINAL = 0x9003  # l'heure réelle de la prise de vue
TAG_DATETIME_DIGITIZED = 0x9004

MAX_BYTES = 16 * 1024 * 1024   # l'EXIF est en tête : au-delà, on ne cherche plus


def emit(payload, code=0):
    json.dump(payload, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    return code


def read_u16(data, offset, little):
    return int.from_bytes(data[offset:offset + 2], "little" if little else "big")


def read_u32(data, offset, little):
    return int.from_bytes(data[offset:offset + 4], "little" if little else "big")


def jpeg_app1(raw):
    """Retourne le bloc TIFF/EXIF d'un JPEG, ou None.

    Les segments JPEG sont un marqueur (FFxx) suivi d'une longueur sur 2 octets. On s'arrête
    au premier SOS (Start Of Scan) : l'EXIF est forcément avant les données d'image.
    """
    if len(raw) < 4 or raw[0] != 0xFF or raw[1] != 0xD8:
        return None
    i = 2
    while i + 4 <= len(raw):
        if raw[i] != 0xFF:
            return None
        marker = raw[i + 1]
        if marker == 0xDA or marker == 0xD9:  # début de scan / fin d'image
            return None
        size = int.from_bytes(raw[i + 2:i + 4], "big")
        if size < 2:
            return None
        start, end = i + 2, i + 2 + size
        if marker == 0xE1 and raw[end - 2:end] is not None and raw[start + 2:start + 8] == b"Exif\x00\x00":
            return raw[start + 8:end]
        if end > len(raw):
            return None
        i = end
    return None


def parse_tiff(tiff):
    """Parses IFD0 + sous-ifd Exif ; renvoie un dictionnaire de valeurs simples."""
    if len(tiff) < 8:
        return {}
    order = tiff[0:2]
    if order == b"II":
        little = True
    elif order == b"MM":
        little = False
    else:
        return {}
    if read_u16(tiff, 2, little) != 42:
        return {}
    ifd0 = read_u32(tiff, 4, little)

    out = {}
    for offset in [ifd0]:
        entries = read_ifd(tiff, offset, little)
        for tag, value in entries.items():
            out[tag] = value
        if TAG_EXIF_IFD in entries and isinstance(entries[TAG_EXIF_IFD], int):
            sub = read_ifd(tiff, entries[TAG_EXIF_IFD], little)
            for tag, value in sub.items():
                out[tag] = value
    return out


def read_ifd(tiff, offset, little):
    """Une table IFD : compteur d'entrées puis 12 octets par entrée."""
    entries = {}
    if offset + 2 > len(tiff):
        return entries
    count = read_u16(tiff, offset, little)
    pos = offset + 2
    for _ in range(count):
        if pos + 12 > len(tiff):
            break
        tag = read_u16(tiff, pos, little)
        kind = read_u16(tiff, pos + 2, little)
        num = read_u32(tiff, pos + 4, little)
        size = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}.get(kind, 1)
        total = num * size
        value_at = pos + 8 if total <= 4 else read_u32(tiff, pos + 8, little)
        if kind in (2, 7):  # ASCII / undefined
            chunk = tiff[value_at:value_at + max(0, min(total, 128))]
            text = chunk.split(b"\x00")[0].decode("ascii", "replace").strip()
            if text:
                entries[tag] = text
        elif kind in (3, 4):
            if value_at + (2 if kind == 3 else 4) <= len(tiff):
                entries[tag] = (read_u16(tiff, value_at, little) if kind == 3
                                else read_u32(tiff, value_at, little))
        pos += 12
    return entries


def to_iso(text):
    """« 2026:10:19 09:41:22 » -> « 2026-10-19T09:41:22 » ; None si le texte n'est pas une date."""
    if not text or len(text) < 19:
        return None
    date_part, _, time_part = text[:19].partition(" ")
    date_part = date_part.replace(":", "-")
    time_part = time_part.replace(".", "")
    try:
        year, month, day = (int(x) for x in date_part.split("-"))
        hour, minute, second = (int(x) for x in time_part.split(":"))
    except ValueError:
        return None
    if not (2000 <= year <= 2100 and 1 <= month <= 12 and 1 <= day <= 31):
        return None
    if hour > 23 or minute > 59 or second > 60:
        return None
    return "%04d-%02d-%02dT%02d:%02d:%02d" % (year, month, day, hour, minute, second)


def main():
    parser = argparse.ArgumentParser(description="Lit l'horodatage EXIF d'une photo.")
    parser.add_argument("--file", required=True, help="chemin du fichier image")
    args = parser.parse_args()

    try:
        with open(args.file, "rb") as handle:
            raw = handle.read(MAX_BYTES)
    except OSError as exc:
        return emit({"ok": False, "kind": "input", "error": "Fichier illisible : %s" % exc}, 1)

    tiff = jpeg_app1(raw)
    if tiff is None:
        # PNG, WebP, capture d'écran, image recadrée : pas d'EXIF, ce n'est pas une erreur.
        return emit({"ok": True, "taken_at": None})

    tags = parse_tiff(tiff)
    taken = to_iso(tags.get(TAG_DATETIME_ORIGINAL) or tags.get(TAG_DATETIME_DIGITIZED)
                   or tags.get(TAG_DATETIME, ""))
    camera = " ".join(str(x) for x in (tags.get(TAG_MAKE), tags.get(TAG_MODEL)) if x) or None
    return emit({"ok": True, "taken_at": taken, "camera": camera,
                 "orientation": tags.get(TAG_ORIENTATION)})


if __name__ == "__main__":
    sys.exit(main())
