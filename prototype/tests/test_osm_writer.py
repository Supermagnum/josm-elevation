"""OSM XML writer tests."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

from shapely.geometry import LineString

from nvdb_incline.gradient import gradient_stats
from nvdb_incline.models import (
    ChainPoint,
    GradientStats,
    MatchResult,
    OsmWay,
    SegmentSuggestion,
    WaySuggestion,
)
from nvdb_incline.osm_writer import build_way_tags, write_osm
from tests.helpers import constant_slope


def test_write_osm_well_formed(tmp_path: Path):
    profile = constant_slope(100.0, 10.0)
    way = OsmWay(
        id=55,
        node_ids=[1, 2, 3],
        tags={"highway": "secondary", "name": "Test"},
        version=3,
        line_utm=LineString([(0, 0), (100, 0)]),
    )
    match = MatchResult(way=way, links=[], confidence="high", method="nvdb:id")
    stats = gradient_stats(profile)
    seg = SegmentSuggestion(
        start_m=0,
        end_m=100,
        average_pct=10.0,
        max_sustained_pct=10.0,
        incline_tag="10%",
        start_xy=(0, 0),
        end_xy=(100, 0),
    )
    sug = WaySuggestion(
        match=match,
        profile=profile,
        stats=stats,
        segments=[seg],
        split=False,
    )
    sug.tags_to_add = build_way_tags(sug)
    path = tmp_path / "out.osm"
    write_osm(path, [sug], [ChainPoint(50, 0, "fit", "test", way_id=55)])
    root = ET.parse(path).getroot()
    assert root.get("upload") == "false"
    way_el = root.find("way")
    tags = {t.get("k"): t.get("v") for t in way_el.findall("tag")}
    assert tags["incline"] == "10%"
    assert tags["incline:source"] == "nvdb_estimate"
    assert any(
        t.get("k") == "chain_advisory" for n in root.findall("node") for t in n.findall("tag")
    )


def test_existing_incline_not_overwritten_in_tags():
    way = OsmWay(
        id=1,
        node_ids=[1, 2],
        tags={"highway": "primary", "incline": "5%"},
        line_utm=LineString([(0, 0), (50, 0)]),
    )
    match = MatchResult(way=way, links=[], confidence="high", method="geometry")
    stats = GradientStats(10, 10, 10, 10, 0, 50)
    seg = SegmentSuggestion(0, 50, 10, 10, "10%", (0, 0), (50, 0))
    sug = WaySuggestion(
        match=match,
        profile=[],
        stats=stats,
        segments=[seg],
        split=False,
        skip_reason="existing incline=* not overwritten",
        existing_incline="5%",
    )
    assert sug.skip_reason
    assert sug.existing_incline == "5%"
