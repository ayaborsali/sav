#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Rédaction IA d'une clause de contrat de maintenance (Groq, qwen3-32b).

Le backend Java lance ce script comme un processus externe : il reçoit la demande en JSON sur
stdin et renvoie un JSON sur stdout. Seul l'appel au modèle vit ici — la décision reste dans
Java (clé présente, quota par utilisateur, rejet d'une proposition qui chiffrerait, écriture en
base). Les montants du contrat ne passent donc jamais par Python ni par le modèle.

La clé API transite par l'environnement, jamais par la ligne de commande : les arguments d'un
process sont lisibles par tout le compte Windows.

Usage :
    python draft_clause.py                 lit la demande JSON sur stdin, appelle Groq
    python draft_clause.py --clean-only     nettoie un texte brut du modèle, sans appel réseau

Réponse sur stdout :
    {"ok": true, "text": "..."}
    {"ok": false, "kind": "config|network|upstream|empty|internal", "error": "..."}
Sortie : 0 si ok, 1 sinon.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "https://api.groq.com/openai/v1/chat/completions"
DEFAULT_MODEL = "qwen/qwen3.8-27b"

# L'API Groq est derrière Cloudflare, qui renvoie « 403 / error code: 1010 » aux clients sans
# signature de bibliothèque reconnue — dont l'urllib nu de Python. Un User-Agent de client API
# standard passe ; ce n'est pas une question de clé mais d'identifiant du client.
USER_AGENT = "OpenAI/Python (sav-backend-redaction-contrat)"

# Délimiteurs d'un modèle à chaîne de pensée (Qwen3) : reconstruits ici pour que le source
# ne contienne pas ces séquences littérales.
BRACKET_OPEN = "<|"
BRACKET_CLOSE = "|>"

# Fin du bloc de raisonnement parfois laissé dans le champ « content » : le texte utile vient après.
REASONING_ENDS = [
    "</think>",
    BRACKET_OPEN + "think" + BRACKET_CLOSE,
    BRACKET_OPEN + "thinking" + BRACKET_CLOSE,
    BRACKET_OPEN + "im_end" + BRACKET_CLOSE,
    ">>",
]

SYSTEM_PROMPT = (
    "Tu es rédacteur de contrats de maintenance — entretien, diagnostic et réparation de bus et\n"
    "véhicules industriels — pour un importateur tunisien.\n"
    "Le contrat porte sur des prestations d'atelier et de dépannage sur des véhicules que le client\n"
    "confie au prestataire. Le client reste propriétaire : il n'achète rien. N'écris donc jamais\n"
    "« vente », « vendeur », « acheteur », « prix de vente » ni « livraison du véhicule ».\n"
    "Tu écris UNIQUEMENT le texte juridique demandé, en français soigné, neutre et professionnel.\n"
    "Règles absolues :\n"
    "- N'invente AUCUN montant, prix, pourcentage, date, numéro de châssis ou durée chiffrée.\n"
    "- N'utilise aucun chiffre sauf ceux explicitement présents dans les données fournies.\n"
    "- Ne mets pas de titre « Article », pas de guillemets, pas de liste à puces : juste le paragraphe.\n"
    "- Réponds par le texte de la clause, rien d'autre.\n"
)


def clean(raw):
    """Garde le texte de la clause et rien d'autre : bloc de raisonnement coupé, espace normalisée."""
    text = raw or ""
    cut = 0
    for marker in REASONING_ENDS:
        end = text.rfind(marker)
        if end >= 0:
            end += len(marker)
            if end > cut:
                cut = end
    return re.sub(r"\s+", " ", text[cut:]).strip()


def build_prompt(payload):
    return ("Contrat de MAINTENANCE (le client confie ses véhicules, il ne les achète pas).\n"
            "Données du contrat (aucun montant) :\n%s\n\nRédige : %s"
            % (payload["facts"], payload["instruction"]))


def call_groq(payload, api_key, timeout):
    body = json.dumps({
        "model": payload.get("model") or DEFAULT_MODEL,
        "temperature": payload.get("temperature", 0.3),
        "max_tokens": payload.get("max_tokens", 900),
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_prompt(payload)},
        ],
    }).encode("utf-8")

    request = urllib.request.Request(
        payload.get("base_url") or DEFAULT_URL,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def respond(result):
    sys.stdout.buffer.write(json.dumps(result, ensure_ascii=False).encode("utf-8"))
    sys.stdout.buffer.flush()
    return 0 if result.get("ok") else 1


def main():
    parser = argparse.ArgumentParser(description="Rédaction IA d'une clause de contrat (Groq).")
    parser.add_argument("--clean-only", action="store_true",
                        help="relit un texte brut de modèle sur stdin et le nettoie, sans appel réseau")
    parser.add_argument("--timeout", type=int, default=45,
                        help="délai maximum de l'appel au modèle, en secondes")
    args = parser.parse_args()

    raw_input = sys.stdin.buffer.read().decode("utf-8", "replace")

    if args.clean_only:
        return respond({"ok": True, "text": clean(raw_input)})

    api_key = os.environ.get("GROQ_API_KEY", "").strip()
    if not api_key:
        return respond({"ok": False, "kind": "config",
                        "error": "GROQ_API_KEY est absente de l'environnement du script."})

    try:
        payload = json.loads(raw_input)
    except ValueError:
        return respond({"ok": False, "kind": "internal",
                        "error": "Le script n'a pas réussi à lire la demande du backend."})

    if not payload.get("instruction") or not payload.get("facts"):
        return respond({"ok": False, "kind": "internal",
                        "error": "Demande incomplète : instruction ou données du contrat manquantes."})

    try:
        answer = call_groq(payload, api_key, args.timeout)
    except urllib.error.HTTPError as exc:
        # Le corps de l'erreur est tronqué : il peut porter un extrait de la requête.
        detail = exc.read().decode("utf-8", "replace")[:300]
        try:
            detail = json.loads(detail)["error"]["message"]
        except (ValueError, KeyError, TypeError):
            pass
        return respond({"ok": False, "kind": "upstream",
                        "error": "Le service IA a répondu %s (%s)." % (exc.code, detail[:180])})
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        return respond({"ok": False, "kind": "network",
                        "error": "Jointure impossible vers le service IA."}, )
    except ValueError:
        return respond({"ok": False, "kind": "upstream",
                        "error": "La réponse du service IA n'est pas lisible."})

    choices = answer.get("choices") or []
    content = ""
    if choices:
        content = (choices[0].get("message") or {}).get("content") or ""
    text = clean(content)
    if not text:
        return respond({"ok": False, "kind": "empty",
                        "error": "Le modèle n'a produit aucun texte réutilisable."})
    return respond({"ok": True, "text": text})


if __name__ == "__main__":
    sys.exit(main())
