#!/usr/bin/env python3
"""
Converte il database https://github.com/type-null/PTCG-database in un unico file
compatto (TSV compresso) da includere negli assets dell'app.

Uso (da eseguire UNA volta sul PC, non serve internet dopo aver scaricato il database):

    git clone --depth 1 https://github.com/type-null/PTCG-database.git
    python3 tools/build_card_catalog.py --db PTCG-database --langs it en

Output: app/src/main/assets/catalog.tsv.gz

Colonne (separate da TAB, una carta per riga, prima riga = intestazione):
    lang  set_code  set_name  number  set_total  name  rarity  artist  card_type  img
"""
import argparse
import gzip
import json
import os
import sys

COLUMNS = ["lang", "set_code", "set_name", "number", "set_total",
           "name", "rarity", "artist", "card_type", "img"]


def clean(value) -> str:
    if value is None:
        return ""
    return " ".join(str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ").split())


def to_int(value) -> int:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", required=True, help="cartella del repository PTCG-database")
    parser.add_argument("--langs", nargs="+", default=["it", "en"], help="lingue da includere (default: it en)")
    parser.add_argument("--out", default=os.path.join("app", "src", "main", "assets", "catalog.tsv.gz"))
    args = parser.parse_args()

    rows = []
    seen = set()
    skipped = 0
    for lang in args.langs:
        root = os.path.join(args.db, f"data_{lang}")
        if not os.path.isdir(root):
            print(f"Cartella non trovata: {root}", file=sys.stderr)
            return 1
        for dirpath, _dirs, files in os.walk(root):
            for filename in sorted(files):
                if not filename.endswith(".json"):
                    continue
                try:
                    with open(os.path.join(dirpath, filename), encoding="utf-8") as fh:
                        card = json.load(fh)
                except (OSError, ValueError):
                    skipped += 1
                    continue

                name = clean(card.get("name"))
                number = clean(card.get("number"))
                set_code = clean(card.get("set_code") or card.get("set_name"))
                if not name or not number or not set_code:
                    skipped += 1
                    continue

                key = (lang, set_code, number)
                if key in seen:
                    skipped += 1
                    continue
                seen.add(key)

                authors = card.get("author") or []
                artist = ", ".join(authors) if isinstance(authors, list) else clean(authors)
                rows.append([
                    lang,
                    set_code,
                    clean(card.get("set_full_name") or card.get("set_name")),
                    number,
                    str(to_int(card.get("set_total"))),
                    name,
                    clean(card.get("rarity")),
                    clean(artist),
                    clean(card.get("card_type")),
                    clean(card.get("img")),
                ])

    rows.sort(key=lambda r: (r[0], r[1], r[3]))
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with gzip.open(args.out, "wt", encoding="utf-8", newline="\n", compresslevel=9) as out:
        out.write("\t".join(COLUMNS) + "\n")
        for row in rows:
            out.write("\t".join(row) + "\n")

    size_kb = os.path.getsize(args.out) / 1024
    print(f"Carte scritte: {len(rows)} (saltate: {skipped}) -> {args.out} ({size_kb:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
