#!/usr/bin/env python3
"""Genera app/src/main/assets/Recintos.bin: los términos municipales para saber en qué municipio
cae un punto (punto en polígono), sin red y sin geocodificador.

Fuente: es-atlas 0.6.0 (IGN, BDLJE, CC BY 4.0), TopoJSON sin proyectar:
    curl -sSL -o municipalities.json https://unpkg.com/es-atlas@0.6.0/es/municipalities.json
    python3 Tools/build_recintos.py municipalities.json app/src/main/assets/Recintos.bin

Formato (big endian, para DataInputStream):
    "REC1", escala x, escala y, traslación x, traslación y (double), n.º de municipios (int)
    por municipio: código INE (int), n.º de anillos (short)
        por anillo: n.º de puntos (int), y los puntos cuantizados (short x, short y)
Lon = x * escala x + traslación x; lat = y * escala y + traslación y.
"""
import json, struct, sys

src, dst = sys.argv[1], sys.argv[2]
t = json.load(open(src))
sx, sy = t["transform"]["scale"]; tx, ty = t["transform"]["translate"]

arcs = []
for a in t["arcs"]:          # deltas → absolutos
    x = y = 0; pts = []
    for dx, dy in a:
        x += dx; y += dy; pts.append((x, y))
    arcs.append(pts)

def ring(idx):
    out = []
    for i in idx:
        pts = arcs[i] if i >= 0 else arcs[~i][::-1]
        out.extend(pts if not out else pts[1:])
    return out

out = bytearray(b"REC1")
geoms = t["objects"]["municipalities"]["geometries"]
out += struct.pack(">ddddi", sx, sy, tx, ty, len(geoms))
for g in geoms:
    polys = [g["arcs"]] if g["type"] == "Polygon" else g["arcs"]
    rings = [ring(r) for p in polys for r in p]
    out += struct.pack(">ih", int(g["id"]), len(rings))
    for r in rings:
        out += struct.pack(">i", len(r))
        for x, y in r: out += struct.pack(">hh", x, y)
open(dst, "wb").write(out)
print(len(geoms), "municipios,", len(out), "bytes")
