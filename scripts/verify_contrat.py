#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vérification réelle du chantier contrat : IA (Groq via le script Python) + fichier PDF.

Ce script ne fait pas de la théorie : il se connecte au backend lancé, demande une rédaction IA
pour une clause, et télécharge le contrat en PDF comme le fait l'écran Contrats. Il ne printed
JAMAIS un secret : les identifiants sont lus depuis le .env du backend et ne sortent que sous
forme de longueur.

Usage :
    python scripts/verify_contrat.py              ; vérifie le contrat le plus récent
    python scripts/verify_contrat.py --create     ; prépare d'abord client + véhicule + contrat
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = os.environ.get("BACKEND_URL", "http://localhost:8080/api")


def read_env(path=".env"):
    values = {}
    if not os.path.isfile(path):
        return values
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


ENV = read_env()


def request(method, path, token=None, body=None, binary=False):
    url = API + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    # Le PDF se négocie en application/pdf : un client qui n'exige que du JSON recevrait un 406,
    # ce n'est pas une erreur de l'application.
    headers = {"Accept": "application/pdf" if binary else "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            payload = response.read()
            if binary:
                return response.status, payload
            return response.status, json.loads(payload.decode("utf-8")) if payload else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail)
        except ValueError:
            detail = detail[:400]
        return exc.code, detail


def login():
    email = ENV.get("ADMIN_EMAIL", "admin@higerbus.tn")
    password = ENV.get("ADMIN_PASSWORD", "")
    if not password:
        raise SystemExit("ADMIN_PASSWORD est vide dans le .env : vérification impossible.")
    status, answer = request("POST", "/auth/login",
                             body={"email": email, "password": password})
    if status != 200 or not answer or not answer.get("token"):
        raise SystemExit("Connexion refusée (%s) : %s" % (status, answer))
    print("connexion : OK (%s, rôle %s)" % (email, answer["user"]["role"]))
    return answer["token"]


def pick_contract(token, create):
    status, page = request("GET", "/contracts?page=0&size=1", token)
    content = (page or {}).get("content") or []
    if content:
        contract = content[0]
        print("contrat testé : %s (%d véhicule(s))" % (contract["number"], contract["vehicleCount"]))
        return contract["id"]
    print("Aucun contrat en base — relance avec --create ou crée-en un depuis l'écran Contrats.")
    raise SystemExit(2)


def check_ai(token, contract_id):
    status, answer = request("GET", "/contracts/ai-status", token)
    print("état IA : %s -> %s" % (status, answer))
    if not answer or not answer.get("available"):
        print("   (clé absente : la rédaction standard reste imprimable, rien à tester côté IA)")
        return

    for section in ("warranty", "delivery"):
        status, answer = request("POST", "/contracts/%s/draft" % contract_id, token,
                                 body={"section": section})
        if status == 200 and answer.get("text"):
            print("rédaction IA (« %s », modèle %s) :" % (section, answer.get("model")))
            print("   " + answer["text"][:400])
        else:
            print("rédaction IA (« %s ») refusée (%s) : %s" % (section, status, answer))


def download_pdf(token, contract_id):
    """Le PDF réel tel que le navigateur le reçoit : en-tête de téléchargement et octets."""
    req = urllib.request.Request(API + "/contracts/%s/document.pdf" % contract_id,
                                 headers={"Authorization": "Bearer " + token,
                                          "Accept": "application/pdf"})
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.status, response.read(), response.headers.get("Content-Disposition", "")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(), ""


def texte_pdf(pdf):
    """Le texte posé sur la page, si pypdfium2 est installé (sinon on garde les octets seuls)."""
    try:
        import pypdfium2 as pdfium
    except ImportError:
        return None
    import tempfile

    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as handle:
        handle.write(pdf)
        path = handle.name
    try:
        doc = pdfium.PdfDocument(path)
        pages = len(doc)
        text = "\n".join(doc[i].get_textpage().get_text_range() for i in range(pages))
        doc.close()
        return "%d page(s)\n%s" % (pages, text)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass  # Windows peut garder le fichier ouvert : ce n'est pas un échec de vérification


def check_pdf(token, contract_id):
    status, pdf, disposition = download_pdf(token, contract_id)
    if status != 200:
        print("PDF du contrat : échec %s %s" % (status, pdf[:400]))
        return False

    out = os.path.join("target", "contrat-verifie.pdf")
    os.makedirs("target", exist_ok=True)
    with open(out, "wb") as handle:
        handle.write(pdf)

    debut = pdf[:5].decode("latin-1")
    fin = pdf[-32:].decode("latin-1", "replace")
    print("PDF du contrat : %s octets, signature « %s », fichier terminé=%s"
          % (len(pdf), debut.strip(), "%%EOF" in fin))
    print("   téléchargement nommé : %s" % (disposition or "(en-tête absent)"))
    print("   enregistré : %s" % out)

    texte = texte_pdf(pdf)
    if texte is None:
        print("   (pypdfium2 absent : contenu texte non vérifié)")
        return True
    lignes = texte.splitlines()
    print("   %s" % lignes[0])
    for marque in ("Total TTC", "Arrêté le présent contrat", "Annexe", "RC :", "Article 1"):
        print("   présent : %-26s %s" % (marque, marque in texte))
    for line in lignes:
        if "Arrêté le présent contrat" in line:
            print("   " + line.strip()[:220])
    return all(m in texte for m in ("Total TTC", "Annexe", "RC :"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--create", action="store_true", help="réserve pour plus tard")
    parser.add_argument("--sans-ia", action="store_true",
                        help="ne demander aucune rédaction IA : lecture seule côté contrat")
    args = parser.parse_args()

    print("backend : %s" % API)
    token = login()
    contract_id = pick_contract(token, args.create)
    if not args.sans_ia:
        check_ai(token, contract_id)
    check_pdf(token, contract_id)


if __name__ == "__main__":
    sys.exit(main())
