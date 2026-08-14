#!/usr/bin/env python3
"""Download Kartverket kommune polygons and refresh the bundled boundaries JSON.

Source (verified 2026-08): Geonorge Atom feed for
\"Administrative enheter kommuner\" GeoJSON EPSG:4258, land-wide zip::

  https://nedlasting.geonorge.no/geonorge/Basisdata/Kommuner/GeoJSON/\\
      Basisdata_0000_Norge_4258_Kommuner_GeoJSON.zip

Metadata UUID: 041f1e6e-bdbc-4091-b48f-8a5990f3cc5b

Only ``objtype=Kommune`` MultiPolygon features are kept. Rings are Douglas-Peucker
simplified so the plugin jar stays small. Re-run after kommune mergers/renumbering.

Requires: Python 3.11+, ``requests``.

Example::

    python tools/refresh_kommune_boundaries.py
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import zipfile
from io import BytesIO
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError:  # pragma: no cover
    print("error: install requests (pip install requests)", file=sys.stderr)
    sys.exit(1)

DEFAULT_URL = (
    "https://nedlasting.geonorge.no/geonorge/Basisdata/Kommuner/GeoJSON/"
    "Basisdata_0000_Norge_4258_Kommuner_GeoJSON.zip"
)
DEFAULT_REFERENCE_DATE = "2026-01-01"
REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = (
    REPO_ROOT
    / "core"
    / "src"
    / "main"
    / "resources"
    / "no"
    / "nvdbincline"
    / "core"
    / "kommune"
    / f"kommune_boundaries_{DEFAULT_REFERENCE_DATE}.json"
)


def _perp_dist(a: list[float], b: list[float], p: list[float]) -> float:
    ax, ay = a
    bx, by = b
    px, py = p
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def douglas_peucker(pts: list[list[float]], eps: float) -> list[list[float]]:
    if len(pts) <= 2:
        return pts
    max_d = -1.0
    idx = 0
    for i in range(1, len(pts) - 1):
        d = _perp_dist(pts[0], pts[-1], pts[i])
        if d > max_d:
            max_d = d
            idx = i
    if max_d > eps:
        left = douglas_peucker(pts[: idx + 1], eps)
        right = douglas_peucker(pts[idx:], eps)
        return left[:-1] + right
    return [pts[0], pts[-1]]


def simplify_ring(coords: list[list[float]], tol_deg: float) -> list[list[float]]:
    if len(coords) < 4:
        return coords
    closed = coords[0] == coords[-1]
    body = coords[:-1] if closed else list(coords)
    simp = douglas_peucker(body, tol_deg)
    if closed and (not simp or simp[0] != simp[-1]):
        simp = simp + [simp[0]]
    if len(simp) < 4:
        return coords
    return [[round(x, 6), round(y, 6)] for x, y in simp]


def load_geojson_bytes(raw: bytes) -> dict[str, Any]:
    text = raw.decode("utf-8-sig")
    return json.loads(text)


def extract_kommuner(geojson: dict[str, Any], tol_deg: float) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for feat in geojson.get("features", []):
        props = feat.get("properties") or {}
        if props.get("objtype") != "Kommune":
            continue
        kn = str(props.get("kommunenummer", "")).zfill(4)
        if not kn.isdigit():
            raise ValueError(f"unexpected kommunenummer: {props.get('kommunenummer')!r}")
        geom = feat.get("geometry") or {}
        gtype = geom.get("type")
        coords = geom.get("coordinates")
        if gtype == "Polygon":
            polys_in = [coords]
        elif gtype == "MultiPolygon":
            polys_in = coords
        else:
            raise ValueError(f"kommune {kn} has geometry type {gtype}")
        polygons: list[list[list[list[float]]]] = []
        for poly in polys_in:
            rings = [simplify_ring(ring, tol_deg) for ring in poly]
            polygons.append(rings)
        out[kn] = {"navn": props.get("kommunenavn") or "", "polygons": polygons}
    if len(out) < 300:
        raise ValueError(f"expected ~357 kommuner, got {len(out)}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", default=DEFAULT_URL, help="Geonorge GeoJSON zip URL")
    ap.add_argument("--zip", type=Path, help="Use a local zip instead of downloading")
    ap.add_argument("--reference-date", default=DEFAULT_REFERENCE_DATE)
    ap.add_argument("--tolerance-deg", type=float, default=0.00015, help="Douglas-Peucker eps")
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()
    out = args.out or (
        REPO_ROOT
        / "core"
        / "src"
        / "main"
        / "resources"
        / "no"
        / "nvdbincline"
        / "core"
        / "kommune"
        / f"kommune_boundaries_{args.reference_date}.json"
    )

    if args.zip:
        zbytes = args.zip.read_bytes()
    else:
        print(f"Downloading {args.url} …", file=sys.stderr)
        r = requests.get(args.url, timeout=120)
        r.raise_for_status()
        zbytes = r.content
        print(f"Downloaded {len(zbytes)} bytes", file=sys.stderr)

    with zipfile.ZipFile(BytesIO(zbytes)) as zf:
        names = [n for n in zf.namelist() if n.lower().endswith((".geojson", ".json"))]
        if not names:
            raise SystemExit("zip has no .geojson")
        geo = load_geojson_bytes(zf.read(names[0]))

    kommuner = extract_kommuner(geo, args.tolerance_deg)
    payload = {
        "source": "Kartverket Administrative enheter kommuner (GeoJSON EPSG:4258)",
        "source_url": args.url if not args.zip else str(args.zip),
        "reference_date": args.reference_date,
        "crs": "EPSG:4258",
        "simplify_tolerance_deg": args.tolerance_deg,
        "note": "Bundled snapshot for offline kommune clipping. CC BY 4.0 Kartverket.",
        "kommuner": kommuner,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    out.write_text(text, encoding="utf-8")
    print(f"Wrote {len(kommuner)} kommuner ({len(text.encode())} bytes) → {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
