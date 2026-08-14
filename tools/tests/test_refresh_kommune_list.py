"""Tests for tools/refresh_kommune_list.py (synthetic xlsx; no network)."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import refresh_kommune_list as rkl  # noqa: E402


def _write_xlsx(path: Path, headers: list[str], rows: list[tuple]) -> None:
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    ws.title = "Kommuner"
    ws.append(headers)
    for row in rows:
        ws.append(list(row))
    wb.save(path)


def test_parse_headers_order_a(tmp_path: Path) -> None:
    xlsx = tmp_path / "a.xlsx"
    _write_xlsx(
        xlsx,
        ["Kommunenummer", "Kommunenavn"],
        [(301, "Oslo"), (5001, "Trondheim")],
    )
    rows = rkl.parse_kommune_xlsx(xlsx)
    assert rows == [
        {"nummer": 301, "navn": "Oslo"},
        {"nummer": 5001, "navn": "Trondheim"},
    ]


def test_parse_headers_swapped_and_regjeringen_style(tmp_path: Path) -> None:
    xlsx = tmp_path / "b.xlsx"
    _write_xlsx(
        xlsx,
        ["Kommune", "Kommunenummer 1.1.24", "Extra"],
        [("Oslo", 301, "x"), ("Åmli", 4204, "y")],
    )
    rows = rkl.parse_kommune_xlsx(xlsx)
    assert rows[0]["nummer"] == 301
    assert rows[1]["navn"] == "Åmli"


def test_parse_missing_headers_fails_loudly(tmp_path: Path) -> None:
    xlsx = tmp_path / "bad.xlsx"
    _write_xlsx(xlsx, ["ID", "Name"], [(1, "Nope")])
    with pytest.raises(ValueError, match="kommunenummer"):
        rkl.parse_kommune_xlsx(xlsx)


def test_write_catalog_roundtrip(tmp_path: Path) -> None:
    out = tmp_path / "kommuner_2024-01-01.json"
    rkl.write_catalog(
        [{"nummer": 301, "navn": "Oslo"}],
        out,
        source="test",
        effective_date="2024-01-01",
    )
    text = out.read_text(encoding="utf-8")
    assert "2024-01-01" in text
    assert "Oslo" in text
