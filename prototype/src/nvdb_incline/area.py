"""Area-of-interest parsing: bbox, kommune, .poly, and .osm files."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from nvdb_incline.models import AreaOfInterest


def parse_bbox(text: str) -> AreaOfInterest:
    """Accept 'min_lon,min_lat,max_lon,max_lat'."""
    parts = [p.strip() for p in text.replace(";", ",").split(",")]
    if len(parts) != 4:
        raise ValueError("bbox must be min_lon,min_lat,max_lon,max_lat")
    min_lon, min_lat, max_lon, max_lat = (float(p) for p in parts)
    if min_lon >= max_lon or min_lat >= max_lat:
        raise ValueError("bbox max must be greater than min")
    return AreaOfInterest(
        kind="bbox",
        min_lon=min_lon,
        min_lat=min_lat,
        max_lon=max_lon,
        max_lat=max_lat,
    )


def parse_kommune(value: str | int) -> AreaOfInterest:
    """Kommune number only; bbox filled later from NVDB Omrader or left unset.

    For kommune mode we store a sentinel bbox that callers replace after looking
    up the municipality envelope, or use the kommune filter directly on NVDB
    and Overpass area queries.
    """
    num = int(str(value).strip())
    if num <= 0:
        raise ValueError("kommune must be a positive integer")
    return AreaOfInterest(
        kind="kommune",
        min_lon=0.0,
        min_lat=0.0,
        max_lon=0.0,
        max_lat=0.0,
        kommune=num,
    )


def load_poly(path: Path) -> AreaOfInterest:
    """Parse a classic Osmosis .poly file (outer ring only)."""
    text = path.read_text(encoding="utf-8")
    coords: list[tuple[float, float]] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("!") or line.lower() in {"end", "1", "none"}:
            continue
        if re.fullmatch(r"[A-Za-z].*", line) and not re.match(r"[-+]?\d", line):
            continue
        parts = line.split()
        if len(parts) >= 2:
            try:
                lon, lat = float(parts[0]), float(parts[1])
            except ValueError:
                continue
            coords.append((lon, lat))
    if len(coords) < 3:
        raise ValueError(f"not enough coordinates in {path}")
    if coords[0] != coords[-1]:
        coords.append(coords[0])
    lons = [c[0] for c in coords]
    lats = [c[1] for c in coords]
    return AreaOfInterest(
        kind="poly",
        min_lon=min(lons),
        min_lat=min(lats),
        max_lon=max(lons),
        max_lat=max(lats),
        polygon_lonlat=coords,
    )


def load_osm_area(path: Path) -> AreaOfInterest:
    """Derive a bounding box from an .osm file's nodes."""
    tree = ET.parse(path)
    root = tree.getroot()
    lats: list[float] = []
    lons: list[float] = []
    for node in root.findall("node"):
        lat = node.get("lat")
        lon = node.get("lon")
        if lat is None or lon is None:
            continue
        lats.append(float(lat))
        lons.append(float(lon))
    if not lats:
        bounds = root.find("bounds")
        if bounds is not None:
            return AreaOfInterest(
                kind="osm",
                min_lon=float(bounds.get("minlon")),
                min_lat=float(bounds.get("minlat")),
                max_lon=float(bounds.get("maxlon")),
                max_lat=float(bounds.get("maxlat")),
                osm_path=str(path),
            )
        raise ValueError(f"no nodes or bounds in {path}")
    return AreaOfInterest(
        kind="osm",
        min_lon=min(lons),
        min_lat=min(lats),
        max_lon=max(lons),
        max_lat=max(lats),
        osm_path=str(path),
    )


def resolve_area(
    *,
    bbox: str | None = None,
    kommune: str | int | None = None,
    poly: Path | None = None,
    osm: Path | None = None,
) -> AreaOfInterest:
    given = [x for x in (bbox, kommune, poly, osm) if x is not None]
    if len(given) != 1:
        raise ValueError("provide exactly one of --bbox, --kommune, --poly, --osm")
    if bbox is not None:
        return parse_bbox(bbox)
    if kommune is not None:
        return parse_kommune(kommune)
    if poly is not None:
        return load_poly(Path(poly))
    return load_osm_area(Path(osm))
