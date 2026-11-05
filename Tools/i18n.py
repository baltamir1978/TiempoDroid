#!/usr/bin/env python3
"""Tools/traducciones.tsv (es, en, gl, eu, ca) → app/src/main/assets/i18n/<idioma>.tsv (clave, traducción)."""
import os
src = os.path.join(os.path.dirname(__file__), "traducciones.tsv")
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "i18n")
os.makedirs(out, exist_ok=True)
langs = ["en", "gl", "eu", "ca"]
rows = [l.rstrip("\n").split("\t") for l in open(src, encoding="utf-8") if l.strip() and not l.startswith("#")]
for r in rows:
    assert len(r) == 5, r
for i, lang in enumerate(langs, 1):
    with open(os.path.join(out, lang + ".tsv"), "w", encoding="utf-8") as f:
        for r in rows:
            if r[i] and (r[i] != r[0] or "{}" in r[0]):
                f.write(r[0] + "\t" + r[i] + "\n")
print(len(rows), "claves")
