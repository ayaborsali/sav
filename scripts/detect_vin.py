#!/usr/bin/env python3
"""
Détection du n° de châssis (VIN) d'un bus sur une photo, via l'API Groq (modèle vision).

Usage :
    python detect_vin.py <chemin_image>          # lit le VIN de la photo
    python detect_vin.py --self-test             # vérifie la logique sans réseau

Prérequis :
    - variable d'environnement GROQ_API_KEY (clé créée dans la console Groq, jamais dans le code)
    - variables optionnelles VIN_MODEL (défaut qwen/qwen3.8-27b) et GROQ_BASE_URL
    - aucune bibliothèque à installer : bibliothèque standard Python uniquement.
      numpy / torch / easyocr sont volontairement absents : c'est le modèle qui lit l'image.

Sortie : UNE seule ligne JSON sur stdout, l'enveloppe attendue par Java. Tout le reste
(avertissements, traces) part sur stderr. Code de sortie 0 si l'enveloppe dit ok, 1 sinon.

    {"ok":true,"status":"complete","vin":"LKLR1KSD5NC123456","model":"...","attempts":1}
    {"ok":true,"status":"partial","partial":"LKLR1KSD5N","read_chars":10}
    {"ok":true,"status":"none"}
    {"ok":false,"kind":"config|input|network|upstream|internal","error":"..."}

Partage des rôles avec Java : ce script lit une image et propose des caractères. C'est Java qui
décide si un VIN est recevable, s'il existe déjà en base, et ce qui est enregistré.
"""

import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

API_URL = os.environ.get("GROQ_BASE_URL", "https://api.groq.com/openai/v1/chat/completions")
DEFAULT_MODEL = "qwen/qwen3.8-27b"
MAX_IMAGE_BYTES = 3_500_000  # marge de sécurité sur la taille de la requête
TIMEOUT_SECONDS = 45
MAX_ATTEMPTS = 2  # deux tentatives : ce qui manque est le plus souvent la photo, pas le réseau

VIN_RE = re.compile(r"^[A-HJ-NPR-Z0-9]{17}$")  # ISO 3779 : jamais I, O, Q
MIN_PARTIAL_CHARS = 8
# Ces trois lettres n'existent pas dans un VIN : leur présence est toujours une confusion de lecture.
CONFUSIONS = str.maketrans({"I": "1", "O": "0", "Q": "0"})
# Réponses de refus du modèle : nettoyées, elles ressembleraient à un VIN partiel. On les écarte
# avant tout le reste.
NO_VIN_WORDS = ("ILLISIBLE", "INLISIBLE", "AUCUN VIN", "AUCUN NUM", "PAS DE VIN",
                "JE NE VOIS", "NONE", "NOT FOUND", "INDETECTABLE")

# Messages présentés à l'utilisateur. Le texte brut renvoyé par Groq reste dans les journaux.
HUMAN_MESSAGES = {
    "config": "La lecture du châssis est mal configurée sur le serveur (clé ou modèle IA).",
    "network": "Le service IA est injoignable depuis ce serveur.",
    "upstream": "Le service IA est limité à quelques lectures par minute : réessaie dans un instant.",
    "input": "Cette photo n'a pas pu être lue.",
    "internal": "La lecture du châssis a échoué sur le serveur.",
}

PROMPT = """Cette photo montre le numéro de châssis (VIN) d'un autobus, gravé ou imprimé sur une
plaque ou sur le châssis.

Règles :
- Un VIN fait exactement 17 caractères : chiffres et lettres majuscules, jamais les lettres I, O, Q.
- Recopie uniquement ce que tu vois. N'invente JAMAIS un caractère et ne complète pas à partir de
  ce que tu crois savoir sur les VIN.
- Ignore tout autre texte de la photo (étiquettes, numéro de moteur, immatriculation, etc.).

Réponds par UNE seule ligne, sans aucune explication :
- le VIN sur 17 caractères si tu le lis entièrement ;
- PARTIEL:<les caractères sûrs, dans l'ordre> si tu n'en lis qu'une partie ;
- ILLISIBLE si tu ne vois aucun VIN."""


class GroqError(Exception):
    """Échec de l'appel, avec le type de panne attendu par Java (config / upstream / network)."""

    def __init__(self, kind, message):
        super().__init__(message)
        self.kind = kind


# ---------------------------------------------------------------------------
# Logique pure (exécutable hors réseau, donc testable)
# ---------------------------------------------------------------------------
def detect_media_type(data):
    """Type MIME d'après les premiers octets du fichier."""
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def normalize(text):
    """Majuscules, sans séparateurs, I/O/Q corrigés en 1/0/0."""
    return re.sub(r"[^0-9A-Za-z]", "", text).upper().translate(CONFUSIONS)


def parse_answer(answer):
    """Réponse du modèle -> enveloppe du protocole (complete / partial / none)."""
    # Un modèle de raisonnement peut laisser un bloc d'analyse avant sa réponse.
    text = re.sub(r"<[a-z_]+>.*?</[a-z_]+>", "", answer or "", flags=re.DOTALL | re.IGNORECASE)
    text = text.strip().strip("`").strip()
    upper = text.upper()

    # Le modèle dit ne rien lire : ce texte n'est pas un fragment de VIN, même une fois nettoyé.
    if any(word in upper for word in NO_VIN_WORDS):
        return {"status": "none"}

    if upper.startswith("PARTIEL"):
        partial = normalize(re.split(r"[:\-–—\s]", text, maxsplit=1)[-1])
        if VIN_RE.match(partial):  # le modèle a tout lu malgré le préfixe
            return {"status": "complete", "vin": partial}
        if len(partial) >= MIN_PARTIAL_CHARS:
            return {"status": "partial", "partial": partial, "read_chars": len(partial)}
        return {"status": "none"}

    # Réponse qui n'est pas marquée : on ne retient que les jetons alphanumériques assez longs,
    # sinon une phrase française normalisée ressemblerait à un VIN partiel.
    candidates = [normalize(token) for token in re.findall(r"[A-Za-z0-9]{8,}", text)]
    for candidate in candidates:
        if VIN_RE.match(candidate):
            return {"status": "complete", "vin": candidate}

    # VIN écrit avec des espaces ou des tirets : « LJDF 2E19 7RA1 0394 2 » ne ressort qu'en recollant.
    joined = normalize(text)
    if VIN_RE.match(joined):
        return {"status": "complete", "vin": joined}

    best = max(candidates, key=len) if candidates else ""
    if len(joined) > len(best):
        best = joined
    if MIN_PARTIAL_CHARS <= len(best) < 17:
        return {"status": "partial", "partial": best, "read_chars": len(best)}
    return {"status": "none"}


def build_payload(image_bytes, media_type, model):
    b64 = base64.b64encode(image_bytes).decode("ascii")
    return {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": PROMPT},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:%s;base64,%s" % (media_type, b64)},
                    },
                ],
            }
        ],
        # Pas de mode réflexion : lecture simple, plus rapide, réponse propre.
        "reasoning_effort": "none",
        # Température 0 : lecture déterministe. Pas de presence_penalty, qui pénaliserait
        # les caractères répétés d'un VIN (ex. 11, 00).
        "temperature": 0,
        "max_completion_tokens": 120,
        "stream": False,
    }


# ---------------------------------------------------------------------------
# Appel API
# ---------------------------------------------------------------------------
def kind_for(status):
    """Code HTTP de Groq -> nature de la panne, pour que Java choisisse son propre code."""
    if status in (401, 403):
        return "config"      # clé refusée : à corriger dans le .env
    if status == 404:
        return "config"      # modèle inconnu du compte : à corriger dans VIN_MODEL
    if status in (413, 400):
        return "input"       # requête ou contenu refusé
    if status == 429:
        return "upstream"    # quota de la clé atteint
    if status in (500, 502, 503, 504):
        return "upstream"    # Groq indisponible
    return "upstream"


def call_groq(payload, api_key):
    """POST sur l'API Groq. Réessaie seulement sur une panne temporaire."""
    body = json.dumps(payload).encode("utf-8")
    last_kind = "network"
    last_detail = "inconnue"
    for attempt in range(1, MAX_ATTEMPTS + 1):
        request = urllib.request.Request(
            API_URL,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + api_key,
                # Un User-Agent explicite évite d'être bloqué par le pare-feu de Groq,
                # qui rejette l'agent Python par défaut.
                "User-Agent": "OpenAI/Python (sav-backend-detection-vin)",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return json.loads(response.read().decode("utf-8")), attempt
        except urllib.error.HTTPError as e:
            last_detail = "HTTP %s %s" % (e.code, e.read().decode("utf-8", errors="replace")[:300])
            last_kind = kind_for(e.code)
            # Réessaie seulement si c'est temporaire (limite de débit, serveur).
            if e.code not in (429, 500, 502, 503, 504):
                raise GroqError(last_kind, last_detail)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_kind, last_detail = "network", "réseau : %s" % (e,)
        if attempt < MAX_ATTEMPTS:
            # Une limite de débit se remet en dizaines de secondes : on attend avant de relancer.
            time.sleep(6 if last_kind == "upstream" else 2 * attempt)
    raise GroqError(last_kind, last_detail)


def emit(envelope):
    """Écrit l'enveloppe sur stdout (une ligne) et rend le code de sortie."""
    json.dump(envelope, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    sys.stdout.flush()
    return 0 if envelope.get("ok") else 1


def process(image_path):
    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        return emit({"ok": False, "kind": "config",
                     "error": "GROQ_API_KEY est absente de l'environnement du backend."})
    if not os.path.isfile(image_path):
        return emit({"ok": False, "kind": "input", "error": "Image introuvable sur le serveur."})

    with open(image_path, "rb") as handle:
        data = handle.read()
    if not data:
        return emit({"ok": False, "kind": "input", "error": "Le fichier d'image est vide."})
    if len(data) > MAX_IMAGE_BYTES:
        return emit({"ok": False, "kind": "input",
                     "error": "Photo trop lourde (%d octets). Reprends-la recadrée sur la plaque."
                              % len(data)})
    media_type = detect_media_type(data)
    if media_type is None:
        return emit({"ok": False, "kind": "input",
                     "error": "Format d'image non supporté : utilise une photo JPEG, un PNG ou un WebP."})

    model = os.environ.get("VIN_MODEL", DEFAULT_MODEL)
    try:
        result, attempts = call_groq(build_payload(data, media_type, model), api_key)
    except GroqError as e:
        # `error` est montré à l'utilisateur, `detail` (le texte brut de Groq, qui peut contenir
        # des identifiants d'organisation) ne va que dans les journaux du serveur.
        return emit({"ok": False, "kind": e.kind,
                     "error": HUMAN_MESSAGES.get(e.kind, "Le service de lecture n'a pas répondu."),
                     "detail": e.args[0]})
    except Exception as e:  # noqa: BLE001 - rien d'autre ne doit sortir sur stdout
        return emit({"ok": False, "kind": "internal",
                     "error": "La lecture du châssis a échoué sur le serveur.",
                     "detail": "%s : %s" % (type(e).__name__, e)})

    try:
        answer = result["choices"][0]["message"].get("content") or ""
    except (KeyError, IndexError, TypeError):
        return emit({"ok": False, "kind": "upstream",
                     "error": "Le modèle n'a pas renvoyé de réponse exploitable.",
                     "detail": json.dumps(result)[:300]})

    envelope = parse_answer(answer)
    envelope["ok"] = True
    envelope["model"] = model
    envelope["attempts"] = attempts
    return emit(envelope)


# ---------------------------------------------------------------------------
# Auto-test hors réseau : la logique de lecture, sans appel et sans image
# ---------------------------------------------------------------------------
def self_test():
    cases = [
        ("LKLR1KSD5NC123456", {"status": "complete", "vin": "LKLR1KSD5NC123456"}),
        ("  lk1r-1ksd5nc123456  ", {"status": "complete", "vin": "LK1R1KSD5NC123456"}),
        ("VIN : LJDF2E197RA103942 sur la plaque", {"status": "complete", "vin": "LJDF2E197RA103942"}),
        ("LJDFO2E197RA10394", {"status": "complete", "vin": "LJDF02E197RA10394"}),
        ("PARTIEL:LJDF2E197RA103", {"status": "partial", "partial": "LJDF2E197RA103", "read_chars": 14}),
        ("PARTIEL : LJDF 2E19 7RA1", {"status": "partial", "partial": "LJDF2E197RA1", "read_chars": 12}),
        ("ILLISIBLE", {"status": "none"}),
        ("Le numéro est illisible sur cette photo.", {"status": "none"}),
        ("", {"status": "none"}),
        ("je ne vois aucun numéro sur cette photo", {"status": "none"}),
        ("PARTIEL:AB1", {"status": "none"}),
        ("LKLR1KSD5NC12345", {"status": "partial", "partial": "LKLR1KSD5NC12345", "read_chars": 16}),
    ]
    failures = []
    for answer, expected in cases:
        got = parse_answer(answer)
        if got != expected:
            failures.append({"entree": answer, "attendu": expected, "obtenu": got})
    if detect_media_type(b"\xff\xd8\xffx") != "image/jpeg":
        failures.append({"entree": "media_type jpeg", "attendu": "image/jpeg"})
    if detect_media_type(b"GIF89a") is not None:
        failures.append({"entree": "media_type gif", "attendu": "None"})
    ok = not failures
    return emit({"ok": ok, "status": "self_test", "checks": len(cases) + 2,
                 "failures": failures,
                 "error": None if ok else "Logique de lecture non conforme (%d cas)." % len(failures)})


def main(argv):
    if "--self-test" in argv:
        return self_test()
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        return emit({"ok": False, "kind": "input",
                     "error": "Aucun chemin d'image fourni : python detect_vin.py <image>."})
    return process(args[0])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
