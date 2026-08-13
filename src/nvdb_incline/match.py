"""OSM-to-NVDB conflation: nvdb:id fast path, then geometry scoring."""

from __future__ import annotations

import logging
from math import hypot

from shapely.geometry import LineString
from shapely.strtree import STRtree

from nvdb_incline.config import Settings
from nvdb_incline.models import MatchResult, NvdbLink, OsmWay

log = logging.getLogger(__name__)


def match_ways(
    ways: list[OsmWay],
    links: list[NvdbLink],
    settings: Settings | None = None,
) -> tuple[list[MatchResult], list[OsmWay], list[NvdbLink]]:
    """Return (matches, unmatched_osm, unmatched_nvdb)."""
    settings = settings or Settings()
    usable = [lk for lk in links if not lk.is_connector and lk.line_utm is not None]
    by_id: dict[str, list[NvdbLink]] = {}
    for lk in usable:
        by_id.setdefault(str(lk.veglenkesekvensid), []).append(lk)

    tree_geoms = [lk.line_utm for lk in usable]
    tree = STRtree(tree_geoms) if tree_geoms else None

    matches: list[MatchResult] = []
    unmatched_osm: list[OsmWay] = []
    used_link_keys: set[str] = set()

    for way in ways:
        if way.line_utm is None or way.line_utm.length < 1.0:
            unmatched_osm.append(way)
            continue
        result = _match_one(way, by_id, usable, tree, settings)
        if result is None:
            unmatched_osm.append(way)
            continue
        matches.append(result)
        for lk in result.links:
            used_link_keys.add(_link_key(lk))

    unmatched_nvdb = [lk for lk in usable if _link_key(lk) not in used_link_keys]
    return matches, unmatched_osm, unmatched_nvdb


def _link_key(lk: NvdbLink) -> str:
    return f"{lk.veglenkesekvensid}:{lk.kortform}"


def _match_one(
    way: OsmWay,
    by_id: dict[str, list[NvdbLink]],
    usable: list[NvdbLink],
    tree: STRtree | None,
    settings: Settings,
) -> MatchResult | None:
    nvdb_id = way.nvdb_id
    if nvdb_id and nvdb_id in by_id:
        links = by_id[nvdb_id]
        hd = _hausdorff(way.line_utm, _concat(links))
        return MatchResult(
            way=way,
            links=links,
            confidence="high",
            method="nvdb:id",
            hausdorff_m=hd,
            notes="fast-path join on nvdb:id",
        )

    if tree is None or not usable:
        return None

    buf = way.line_utm.buffer(settings.nearest_fallback_m)
    idxs = tree.query(buf)
    candidates: list[NvdbLink] = []
    for idx in idxs:
        i = int(idx)
        if 0 <= i < len(usable):
            candidates.append(usable[i])
    if not candidates:
        return None

    scored: list[tuple[float, float, NvdbLink]] = []
    for lk in candidates:
        hd = _hausdorff(way.line_utm, lk.line_utm)
        score = _score(way.line_utm, lk.line_utm, hd, settings)
        scored.append((score, hd, lk))
    scored.sort(key=lambda t: t[0], reverse=True)
    best_score, best_hd, best = scored[0]

    # Prefer concatenating other high-scoring segments from the same sequence.
    same_seq = [lk for _, _, lk in scored if lk.veglenkesekvensid == best.veglenkesekvensid]
    links = same_seq or [best]
    concat = _concat(links)
    concat_hd = _hausdorff(way.line_utm, concat)

    if concat_hd is not None and concat_hd <= settings.hausdorff_high_m and best_score >= 0.55:
        return MatchResult(
            way=way,
            links=links,
            confidence="high",
            method="geometry",
            hausdorff_m=concat_hd,
        )
    if concat_hd is not None and concat_hd <= settings.hausdorff_medium_m and best_score >= 0.35:
        return MatchResult(
            way=way,
            links=links,
            confidence="medium",
            method="geometry",
            hausdorff_m=concat_hd,
        )
    if concat_hd is not None and concat_hd <= settings.nearest_fallback_m:
        return MatchResult(
            way=way,
            links=links,
            confidence="low",
            method="nearest-fallback",
            hausdorff_m=concat_hd,
            notes="no confident overlap; nearest NVDB link within fallback distance",
        )
    return None


def _score(osm: LineString, nvdb: LineString, hausdorff_m: float | None, settings: Settings) -> float:
    if hausdorff_m is None:
        return 0.0
    hd_term = max(0.0, 1.0 - hausdorff_m / max(settings.nearest_fallback_m, 1.0))
    coverage = _coverage(osm, nvdb, settings.hausdorff_medium_m)
    length_ratio = _length_ratio(osm, nvdb)
    direction = _direction_alignment(osm, nvdb)
    # Opposite direction is acceptable for two-way roads.
    dir_term = abs(direction)
    return 0.4 * hd_term + 0.35 * coverage + 0.15 * length_ratio + 0.10 * dir_term


def _coverage(osm: LineString, nvdb: LineString, buffer_m: float) -> float:
    if osm.length <= 0:
        return 0.0
    inter = osm.buffer(buffer_m).intersection(nvdb)
    if inter.is_empty:
        return 0.0
    return min(1.0, float(inter.length) / float(osm.length))


def _length_ratio(a: LineString, b: LineString) -> float:
    la, lb = float(a.length), float(b.length)
    if la <= 0 or lb <= 0:
        return 0.0
    return min(la, lb) / max(la, lb)


def _direction_alignment(a: LineString, b: LineString) -> float:
    va = _end_vector(a)
    vb = _end_vector(b)
    na = hypot(*va)
    nb = hypot(*vb)
    if na < 1e-9 or nb < 1e-9:
        return 0.0
    return (va[0] * vb[0] + va[1] * vb[1]) / (na * nb)


def _end_vector(line: LineString) -> tuple[float, float]:
    coords = list(line.coords)
    return (coords[-1][0] - coords[0][0], coords[-1][1] - coords[0][1])


def _hausdorff(a: LineString, b: LineString) -> float | None:
    try:
        return float(a.hausdorff_distance(b))
    except Exception:
        log.debug("hausdorff failed", exc_info=True)
        return None


def _concat(links: list[NvdbLink]) -> LineString:
    ordered = sorted(links, key=lambda lk: (lk.veglenkesekvensid, lk.startposisjon))
    coords: list[tuple[float, ...]] = []
    for lk in ordered:
        pts = list(lk.line_utm.coords)
        if coords and pts:
            ax, ay = coords[-1][0], coords[-1][1]
            bx, by = pts[0][0], pts[0][1]
            if hypot(ax - bx, ay - by) < 0.05:
                pts = pts[1:]
        coords.extend(pts)
    if len(coords) < 2:
        return ordered[0].line_utm
    return LineString(coords)
