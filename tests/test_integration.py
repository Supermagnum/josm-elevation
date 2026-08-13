"""Integration tests against recorded fixtures (fully offline)."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

from nvdb_incline.config import Settings
from nvdb_incline.http_util import FixtureHttp
from nvdb_incline.models import AreaOfInterest
from nvdb_incline.pipeline import run_pipeline

FIXTURES = Path(__file__).parent / "fixtures"


def _area() -> AreaOfInterest:
    return AreaOfInterest(
        kind="bbox",
        min_lon=9.69,
        min_lat=62.59,
        max_lon=9.72,
        max_lat=62.61,
    )


def _http(folder: Path, run_name: str) -> FixtureHttp:
    fmap = {
        f"{run_name}_overpass": folder / "overpass.json",
        "overpass_ways": folder / "overpass.json",
        f"{run_name}_nvdb_p0": folder / "nvdb_segmentert.json",
    }
    dak = folder / "datakatalog.json"
    if dak.exists():
        fmap[f"{run_name}_datakatalog"] = dak
        fmap["datakatalog"] = dak
    return FixtureHttp(fmap)


def test_steep_and_flat_pipeline(tmp_path: Path):
    folder = FIXTURES / "area_steep_flat"
    run = "area_steep_flat"
    settings = Settings(
        output_dir=str(tmp_path),
        run_name=run,
        cache_dir=None,
        fixture_dir=None,
    )
    http = _http(folder, run)
    result = run_pipeline(
        _area(),
        overpass_http=http,
        nvdb_http=http,
        settings=settings,
        skip_datakatalog=True,
    )
    assert result.exit_code == 0
    assert result.osm_path.exists()
    assert result.summary_path.exists()

    tree = ET.parse(result.osm_path)
    root = tree.getroot()
    assert root.tag == "osm"
    assert root.get("upload") == "false"

    ways = {int(w.get("id")): w for w in root.findall("way")}
    assert 1001 in ways  # flat
    assert 1002 in ways  # steep

    def tags(way_el):
        return {t.get("k"): t.get("v") for t in way_el.findall("tag")}

    flat_tags = tags(ways[1001])
    steep_tags = tags(ways[1002])
    assert flat_tags.get("incline") == "0%"
    assert flat_tags.get("incline:source") == "nvdb_estimate"
    assert steep_tags.get("incline") in {"10%", "9%", "11%"}
    assert steep_tags.get("incline:source") == "nvdb_estimate"
    assert "fixme" in steep_tags


def test_pass_area_emits_chain_points(tmp_path: Path):
    folder = FIXTURES / "area_pass"
    run = "area_pass"
    settings = Settings(output_dir=str(tmp_path), run_name=run, cache_dir=None)
    http = _http(folder, run)
    result = run_pipeline(
        AreaOfInterest("bbox", 9.69, 62.60, 9.72, 62.61),
        overpass_http=http,
        nvdb_http=http,
        settings=settings,
        skip_datakatalog=True,
    )
    assert result.exit_code == 0
    assert len(result.chain_points) >= 1
    tree = ET.parse(result.osm_path)
    nodes = tree.getroot().findall("node")
    advisory = [
        n
        for n in nodes
        if any(t.get("k") == "chain_advisory" for t in n.findall("tag"))
    ]
    assert advisory


def test_nomatch_exit_code(tmp_path: Path):
    folder = FIXTURES / "area_nomatch"
    run = "area_nomatch"
    settings = Settings(output_dir=str(tmp_path), run_name=run, cache_dir=None)
    http = _http(folder, run)
    result = run_pipeline(
        AreaOfInterest("bbox", 9.69, 62.59, 9.80, 62.70),
        overpass_http=http,
        nvdb_http=http,
        settings=settings,
        skip_datakatalog=True,
    )
    assert result.exit_code == 2
    assert result.matches == []
