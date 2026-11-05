#!/usr/bin/env python3
"""Genera app/src/main/assets/Portugal.txt: el contorno de Portugal (continente, Madeira y
Azores) para saber sin red ni geocodificador si un punto cae en Portugal y va al IPMA.

Fuente: world-atlas 2.0.2 (Natural Earth 1:10m, dominio público), TopoJSON sin proyectar:
    curl -sSL -o countries-10m.json https://unpkg.com/world-atlas@2.0.2/countries-10m.json
    python3 Tools/build_portugal.py countries-10m.json app/src/main/assets/Portugal.txt

Formato: un anillo por línea, «lon,lat lon,lat …» con cinco decimales. Se mira antes en los
términos municipales de España (Recintos.bin): la frontera de Natural Earth es más gruesa.
"""
import json, sys

t = json.load(open(sys.argv[1]))
sx, sy = t["transform"]["scale"]; tx, ty = t["transform"]["translate"]
arcs = []
for a in t["arcs"]:
    x = y = 0; pts = []
    for dx, dy in a:
        x += dx; y += dy; pts.append((x * sx + tx, y * sy + ty))
    arcs.append(pts)

def ring(idx):
    out = []
    for i in idx:
        pts = arcs[i] if i >= 0 else arcs[~i][::-1]
        out.extend(pts if not out else pts[1:])
    return out

g = next(g for g in t["objects"]["countries"]["geometries"] if g["properties"].get("name") == "Portugal")
polys = [g["arcs"]] if g["type"] == "Polygon" else g["arcs"]
with open(sys.argv[2], "w") as f:
    for p in polys:
        for r in p:
            f.write(" ".join(f"{x:.5f},{y:.5f}" for x, y in ring(r)) + "\n")
