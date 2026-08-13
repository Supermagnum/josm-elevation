"""CLI for the review-only NVDB incline estimator.

Intentionally read-only toward OpenStreetMap: writes local review files only.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

import click

from nvdb_incline.area import resolve_area
from nvdb_incline.config import (
    DEFAULT_NVDB_BASE,
    DEFAULT_OVERPASS_URL,
    REVIEW_REMINDER,
    Settings,
)
from nvdb_incline.http_util import FixtureHttp, build_sessions
from nvdb_incline.pipeline import run_pipeline


def main(argv: list[str] | None = None) -> int:
    try:
        cli.main(args=argv, standalone_mode=False)
        return 0
    except SystemExit as exc:
        code = exc.code
        if code is None:
            return 0
        return int(code) if not isinstance(code, int) else code
    except click.ClickException as exc:
        click.echo(exc.format_message(), err=True)
        return 2


@click.command(context_settings={"help_option_names": ["-h", "--help"]})
@click.option("--bbox", default=None, help="min_lon,min_lat,max_lon,max_lat")
@click.option("--kommune", default=None, help="Norwegian municipality number")
@click.option("--poly", type=click.Path(exists=True, path_type=Path), default=None)
@click.option("--osm", type=click.Path(exists=True, path_type=Path), default=None)
@click.option("--output-dir", type=click.Path(path_type=Path), default=Path("."))
@click.option("--run-name", default="nvdb_incline")
@click.option("--overpass-url", default=None)
@click.option("--nvdb-base", default=None)
@click.option("--cache-dir", type=click.Path(path_type=Path), default=Path(".cache/nvdb-incline"))
@click.option(
    "--fixture-dir",
    type=click.Path(exists=True, path_type=Path),
    default=None,
    help="Offline mode: load recorded Overpass/NVDB JSON fixtures from this directory",
)
@click.option("--exclude-highway", multiple=True, default=("footway", "path", "steps"))
@click.option("--rolling-window-m", default=50.0, show_default=True)
@click.option("--split-spread-pp", default=4.0, show_default=True)
@click.option("--chain-gradient-pct", default=6.0, show_default=True)
@click.option("--chain-min-distance-m", default=200.0, show_default=True)
@click.option("-v", "--verbose", is_flag=True)
def cli(
    bbox: str | None,
    kommune: str | None,
    poly: Path | None,
    osm: Path | None,
    output_dir: Path,
    run_name: str,
    overpass_url: str | None,
    nvdb_base: str | None,
    cache_dir: Path,
    fixture_dir: Path | None,
    exclude_highway: tuple[str, ...],
    rolling_window_m: float,
    split_spread_pp: float,
    chain_gradient_pct: float,
    chain_min_distance_m: float,
    verbose: bool,
) -> None:
    """Estimate incline=* from NVDB elevations for manual JOSM review.

    Never uploads to OpenStreetMap. Always review output before any manual upload.
    """
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    area = resolve_area(bbox=bbox, kommune=kommune, poly=poly, osm=osm)
    settings = Settings(
        overpass_url=overpass_url or DEFAULT_OVERPASS_URL,
        nvdb_base=nvdb_base or DEFAULT_NVDB_BASE,
        exclude_highways=tuple(exclude_highway)
        or ("footway", "path", "steps"),
        rolling_window_m=rolling_window_m,
        split_spread_pp=split_spread_pp,
        chain_gradient_pct=chain_gradient_pct,
        chain_min_distance_m=chain_min_distance_m,
        cache_dir=str(cache_dir) if fixture_dir is None else None,
        fixture_dir=str(fixture_dir) if fixture_dir else None,
        output_dir=str(output_dir),
        run_name=run_name,
    )

    if fixture_dir is not None:
        fmap = _fixture_map(fixture_dir, run_name)
        overpass_http = FixtureHttp(fmap)
        nvdb_http = FixtureHttp(fmap)
        result = run_pipeline(
            area,
            overpass_http=overpass_http,
            nvdb_http=nvdb_http,
            settings=settings,
            skip_datakatalog="datakatalog" not in fmap,
        )
    else:
        overpass_http, nvdb_http = build_sessions(settings)
        result = run_pipeline(
            area,
            overpass_http=overpass_http,
            nvdb_http=nvdb_http,
            settings=settings,
        )

    click.echo(REVIEW_REMINDER)
    if result.exit_code != 0:
        click.echo(
            "No OSM/NVDB matches in this area. See unmatched report.",
            err=True,
        )
        raise SystemExit(result.exit_code)


def _fixture_map(fixture_dir: Path, run_name: str) -> dict[str, Path]:
    """Map logical cache keys to fixture files.

    Expected names (any subset):
      {run}_overpass.json
      {run}_nvdb_p0.json, {run}_nvdb_p1.json, ...
      {run}_datakatalog.json
      overpass.json / nvdb_segmentert.json as generic fallbacks
    """
    fmap: dict[str, Path] = {}
    for path in sorted(fixture_dir.glob("*.json")):
        stem = path.stem
        fmap[stem] = path
        fmap[f"{run_name}_{stem}"] = path
    # Standard aliases used by the pipeline.
    for key in (
        f"{run_name}_overpass",
        "overpass_ways",
        "overpass",
    ):
        for candidate in (
            fixture_dir / f"{key}.json",
            fixture_dir / "overpass.json",
            fixture_dir / f"{run_name}_overpass.json",
        ):
            if candidate.exists():
                fmap[key] = candidate
                fmap["overpass_ways"] = candidate
                fmap[f"{run_name}_overpass"] = candidate
                break
    for page in range(0, 20):
        key = f"{run_name}_nvdb_p{page}"
        for candidate in (
            fixture_dir / f"{key}.json",
            fixture_dir / f"nvdb_segmentert_p{page}.json",
            fixture_dir / "nvdb_segmentert.json" if page == 0 else None,
        ):
            if candidate and candidate.exists():
                fmap[key] = candidate
                break
    dak = fixture_dir / f"{run_name}_datakatalog.json"
    if dak.exists():
        fmap[f"{run_name}_datakatalog"] = dak
        fmap["datakatalog"] = dak
    elif (fixture_dir / "datakatalog.json").exists():
        fmap[f"{run_name}_datakatalog"] = fixture_dir / "datakatalog.json"
        fmap["datakatalog"] = fixture_dir / "datakatalog.json"
    return fmap


if __name__ == "__main__":
    sys.exit(main())
