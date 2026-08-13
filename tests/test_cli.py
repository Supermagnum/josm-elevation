"""CLI end-to-end tests against fixtures (offline)."""

from __future__ import annotations

from pathlib import Path

from click.testing import CliRunner

from nvdb_incline.cli import cli

FIXTURES = Path(__file__).parent / "fixtures"


def test_cli_steep_flat_success(tmp_path: Path):
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "--bbox",
            "9.69,62.59,9.72,62.61",
            "--fixture-dir",
            str(FIXTURES / "area_steep_flat"),
            "--output-dir",
            str(tmp_path),
            "--run-name",
            "area_steep_flat",
        ],
    )
    assert result.exit_code == 0, result.output
    assert (tmp_path / "area_steep_flat.osm").exists()
    assert (tmp_path / "area_steep_flat_summary.md").exists()
    assert "REVIEW ONLY" in result.output


def test_cli_nomatch_nonzero(tmp_path: Path):
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "--bbox",
            "9.69,62.59,9.80,62.70",
            "--fixture-dir",
            str(FIXTURES / "area_nomatch"),
            "--output-dir",
            str(tmp_path),
            "--run-name",
            "area_nomatch",
        ],
    )
    assert result.exit_code == 2, result.output


def test_cli_requires_area():
    runner = CliRunner()
    result = runner.invoke(cli, [])
    assert result.exit_code != 0
