"""CRS helpers, WKT parsing, and elevation-profile extraction."""

from __future__ import annotations

from functools import lru_cache
from math import hypot

from pyproj import Transformer
from shapely.geometry import LineString, Point
from shapely import wkt as shapely_wkt

from nvdb_incline.models import ElevationSample, NvdbLink, OsmWay

# Horizontal working CRS: ETRS89 / UTM zone 33N (metres).
UTM33 = "EPSG:25833"
WGS84 = "EPSG:4326"
UTM33_SRIDS = {25833, 32633, 5973, 6173, 25832}


@lru_cache(maxsize=8)
def _transformer(from_crs: str, to_crs: str) -> Transformer:
    return Transformer.from_crs(from_crs, to_crs, always_xy=True)


def lonlat_to_utm(lon: float, lat: float) -> tuple[float, float]:
    x, y = _transformer(WGS84, UTM33).transform(lon, lat)
    return float(x), float(y)


def utm_to_lonlat(x: float, y: float) -> tuple[float, float]:
    lon, lat = _transformer(UTM33, WGS84).transform(x, y)
    return float(lon), float(lat)


def line_from_lonlat(coords: list[tuple[float, float]]) -> LineString:
    return LineString([lonlat_to_utm(lon, lat) for lon, lat in coords])


def parse_wkt_line(wkt_str: str, srid: int) -> LineString:
    """Parse NVDB WKT and return a (possibly 3D) LineString in UTM33 metres."""
    geom = shapely_wkt.loads(wkt_str)
    if geom.geom_type != "LineString":
        geom = geom.geoms[0] if hasattr(geom, "geoms") else LineString(geom)
    coords = list(geom.coords)
    if srid in UTM33_SRIDS or srid == 0:
        return LineString(coords)
    if srid == 4326:
        out = []
        for c in coords:
            x, y = lonlat_to_utm(c[0], c[1])
            if len(c) >= 3:
                out.append((x, y, c[2]))
            else:
                out.append((x, y))
        return LineString(out)
    # Unknown projected CRS: treat as UTM33 (NVDB default).
    return LineString(coords)


def polyline_length_m(line: LineString) -> float:
    if line.is_empty:
        return 0.0
    return float(line.length)


def interpolate_z_at(line3d: LineString, point: Point) -> float | None:
    """Z at the nearest location on a 3D linestring, interpolated along the line."""
    if line3d.is_empty:
        return None
    # shapely project/interpolate use 2D distance even for 3D geometries.
    d = line3d.project(point)
    loc = line3d.interpolate(d)
    if loc.has_z:
        return float(loc.z)
    # Manual 3D interpolation if shapely dropped Z.
    coords = list(line3d.coords)
    if not coords or len(coords[0]) < 3:
        return None
    if len(coords) == 1:
        return float(coords[0][2])
    remaining = d
    for (x0, y0, z0), (x1, y1, z1) in zip(coords, coords[1:]):
        seg = hypot(x1 - x0, y1 - y0)
        if seg <= 1e-9:
            continue
        if remaining <= seg:
            t = remaining / seg
            return float(z0 + t * (z1 - z0))
        remaining -= seg
    return float(coords[-1][2])


def elevation_profile(way: OsmWay, links: list[NvdbLink]) -> list[ElevationSample]:
    """Sample NVDB Z onto each OSM node, in OSM node order (defines incline sign)."""
    merged = _merge_link_geometry(links)
    if merged is None or way.line_utm is None:
        return []
    samples: list[ElevationSample] = []
    dist = 0.0
    prev: tuple[float, float] | None = None
    coords = list(way.line_utm.coords)
    for c in coords:
        x, y = float(c[0]), float(c[1])
        if prev is not None:
            dist += hypot(x - prev[0], y - prev[1])
        z = interpolate_z_at(merged, Point(x, y))
        if z is None:
            prev = (x, y)
            continue
        samples.append(ElevationSample(distance_m=dist, elevation_m=z, x=x, y=y))
        prev = (x, y)
    return samples


def _merge_link_geometry(links: list[NvdbLink]) -> LineString | None:
    ordered = sorted(links, key=lambda lk: (lk.veglenkesekvensid, lk.startposisjon))
    coords: list[tuple[float, ...]] = []
    for lk in ordered:
        pts = list(lk.line_utm.coords)
        if not pts:
            continue
        if coords and _same_xy(coords[-1], pts[0]):
            pts = pts[1:]
        coords.extend(pts)
    if len(coords) < 2:
        return None
    return LineString(coords)


def _same_xy(a: tuple[float, ...], b: tuple[float, ...], tol: float = 0.05) -> bool:
    return hypot(a[0] - b[0], a[1] - b[1]) < tol


def point_along(line: LineString, distance_m: float) -> tuple[float, float]:
    loc = line.interpolate(min(max(distance_m, 0.0), line.length))
    return float(loc.x), float(loc.y)


def xy_at_profile_distance(
    profile: list[ElevationSample], distance_m: float
) -> tuple[float, float]:
    if not profile:
        raise ValueError("empty profile")
    if distance_m <= profile[0].distance_m:
        return profile[0].x, profile[0].y
    for a, b in zip(profile, profile[1:]):
        if b.distance_m >= distance_m:
            span = b.distance_m - a.distance_m
            t = 0.0 if span <= 1e-9 else (distance_m - a.distance_m) / span
            return a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)
    return profile[-1].x, profile[-1].y
