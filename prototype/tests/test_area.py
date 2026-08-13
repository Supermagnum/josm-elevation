"""Area input parsing tests."""

from __future__ import annotations

from pathlib import Path

import pytest

from nvdb_incline.area import load_poly, parse_bbox, parse_kommune, resolve_area


def test_parse_bbox():
    a = parse_bbox("9.7,62.5,9.8,62.6")
    assert a.min_lon == 9.7
    assert a.max_lat == 62.6


def test_parse_kommune():
    a = parse_kommune("5021")
    assert a.kind == "kommune"
    assert a.kommune == 5021


def test_load_poly(tmp_path: Path):
    p = tmp_path / "t.poly"
    p.write_text(
        "test\n"
        "1\n"
        "  9.7 62.5\n"
        "  9.8 62.5\n"
        "  9.8 62.6\n"
        "  9.7 62.6\n"
        "  9.7 62.5\n"
        "END\n"
        "END\n",
        encoding="utf-8",
    )
    a = load_poly(p)
    assert a.kind == "poly"
    assert a.polygon_lonlat is not None
    assert len(a.polygon_lonlat) >= 4


def test_resolve_exactly_one():
    with pytest.raises(ValueError):
        resolve_area()
    with pytest.raises(ValueError):
        resolve_area(bbox="1,2,3,4", kommune="1")
