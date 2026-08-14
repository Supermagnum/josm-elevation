"""Tests for tools/refresh_kommune_boundaries.py (offline fixtures)."""

from __future__ import annotations

import json
import zipfile
from io import BytesIO
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys_path_tools = ROOT / "tools"
import sys

sys.path.insert(0, str(sys_path_tools))

from refresh_kommune_boundaries import extract_kommuner, load_geojson_bytes  # noqa: E402


def test_extract_kommuner_from_minimal_geojson():
    geo = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {"objtype": "Grense"},
                "geometry": {"type": "LineString", "coordinates": [[0, 0], [1, 1]]},
            },
            {
                "type": "Feature",
                "properties": {
                    "objtype": "Kommune",
                    "kommunenummer": "0301",
                    "kommunenavn": "Oslo",
                },
                "geometry": {
                    "type": "MultiPolygon",
                    "coordinates": [
                        [
                            [
                                [10.0, 59.0],
                                [11.0, 59.0],
                                [11.0, 60.0],
                                [10.0, 60.0],
                                [10.0, 59.0],
                            ]
                        ]
                    ],
                },
            },
        ],
    }
    # pad to satisfy size check by duplicating with unique numbers — or lower threshold in test via direct call
    # extract_kommuner requires >=300; for unit test call internal simplify on one and assert parse of load
    raw = json.dumps(geo).encode("utf-8-sig")
    parsed = load_geojson_bytes(raw)
    assert parsed["features"][1]["properties"]["kommunenummer"] == "0301"

    # Build a fake catalog of 300 tiny kommuner to satisfy the guard.
    features = []
    for i in range(300):
        kn = f"{i+1:04d}"
        features.append(
            {
                "type": "Feature",
                "properties": {
                    "objtype": "Kommune",
                    "kommunenummer": kn,
                    "kommunenavn": f"K{kn}",
                },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                        [
                            [10.0, 60.0],
                            [10.01, 60.0],
                            [10.01, 60.01],
                            [10.0, 60.01],
                            [10.0, 60.0],
                        ]
                    ],
                },
            }
        )
    big = {"type": "FeatureCollection", "features": features}
    out = extract_kommuner(big, 0.0005)
    assert len(out) == 300
    assert "0001" in out
    assert out["0001"]["polygons"]


def test_rejects_unexpected_geometry():
    geo = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {
                    "objtype": "Kommune",
                    "kommunenummer": "0301",
                    "kommunenavn": "Oslo",
                },
                "geometry": {"type": "Point", "coordinates": [10, 60]},
            }
        ]
        + [
            {
                "type": "Feature",
                "properties": {
                    "objtype": "Kommune",
                    "kommunenummer": f"{i:04d}",
                    "kommunenavn": "X",
                },
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]],
                },
            }
            for i in range(2, 302)
        ],
    }
    with pytest.raises(ValueError, match="geometry type"):
        extract_kommuner(geo, 0.0002)
