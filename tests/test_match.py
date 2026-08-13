"""Matching / conflation scoring tests with synthetic geometries."""

from __future__ import annotations

from shapely.geometry import LineString

from nvdb_incline.config import Settings
from nvdb_incline.match import match_ways
from nvdb_incline.models import NvdbLink, OsmWay


def _way(wid: int, coords, tags=None) -> OsmWay:
    return OsmWay(
        id=wid,
        node_ids=list(range(1, len(coords) + 1)),
        tags=tags or {"highway": "secondary"},
        line_utm=LineString([(c[0], c[1]) for c in coords]),
    )


def _link(vid: int, coords, kortform="0-1") -> NvdbLink:
    pts3 = [(c[0], c[1], c[2] if len(c) > 2 else 0.0) for c in coords]
    return NvdbLink(
        veglenkesekvensid=vid,
        kortform=f"{kortform}@{vid}",
        type="HOVED",
        type_veg="Enkel bilveg",
        medium=None,
        kommune=1,
        lengde=LineString(pts3).length,
        wkt="",
        srid=5973,
        line_utm=LineString(pts3),
    )


def test_nvdb_id_fast_path():
    coords = [(0, 0), (100, 0), (200, 0)]
    way = _way(1, coords, {"highway": "primary", "nvdb:id": "55"})
    link = _link(55, [(0, 0, 10), (100, 0, 10), (200, 0, 11)])
    matches, unmatched, _ = match_ways([way], [link], Settings())
    assert len(matches) == 1
    assert matches[0].method == "nvdb:id"
    assert matches[0].confidence == "high"
    assert unmatched == []


def test_geometry_high_confidence_overlap():
    way = _way(2, [(0, 0), (150, 0)])
    link = _link(70, [(0, 1, 5), (150, 1, 8)])  # 1m offset
    matches, unmatched, _ = match_ways([way], [link], Settings())
    assert len(matches) == 1
    assert matches[0].confidence in {"high", "medium"}
    assert unmatched == []


def test_deliberately_bad_match_rejected_or_low():
    way = _way(3, [(0, 0), (100, 0)])
    # Far away link
    link = _link(80, [(1000, 1000, 0), (1100, 1000, 0)])
    matches, unmatched, unmatched_nvdb = match_ways([way], [link], Settings())
    assert way in unmatched or (matches and matches[0].confidence == "low")
    if not matches:
        assert way in unmatched
        assert link in unmatched_nvdb


def test_nearest_fallback_marked_low():
    way = _way(4, [(0, 0), (100, 0)])
    # Within fallback distance but poor overlap (40m away)
    link = _link(90, [(0, 40, 0), (100, 40, 0)])
    settings = Settings(
        hausdorff_high_m=15.0,
        hausdorff_medium_m=30.0,
        nearest_fallback_m=50.0,
    )
    matches, unmatched, _ = match_ways([way], [link], settings)
    assert len(matches) == 1
    assert matches[0].confidence == "low"
    assert matches[0].method == "nearest-fallback"
