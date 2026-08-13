"""End-to-end review pipeline. Reads Overpass + NVDB; writes local files only."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

from nvdb_incline.chain_advisory import advise_chain_points, cluster_points
from nvdb_incline.config import REVIEW_REMINDER, Settings
from nvdb_incline.geo import elevation_profile
from nvdb_incline.gradient import SplitConfig, gradient_stats, suggest_segments
from nvdb_incline.match import match_ways
from nvdb_incline.models import (
    AreaOfInterest,
    ChainPoint,
    MatchResult,
    NvdbLink,
    OsmWay,
    WaySuggestion,
    WinterObject,
)
from nvdb_incline.nvdb import (
    fetch_kommune_bbox,
    fetch_segmented_links,
    fetch_winter_objects,
    search_winter_object_types,
)
from nvdb_incline.osm_writer import build_way_tags, write_osm
from nvdb_incline.overpass import fetch_osm_ways, parse_overpass_elements
from nvdb_incline.report import (
    write_chain_geojson,
    write_csv_summary,
    write_markdown_summary,
    write_unmatched_report,
)

log = logging.getLogger(__name__)


@dataclass
class PipelineResult:
    suggestions: list[WaySuggestion]
    discrepancies: list[WaySuggestion]
    chain_points: list[ChainPoint]
    matches: list[MatchResult]
    unmatched_osm: list[OsmWay]
    unmatched_nvdb: list[NvdbLink]
    osm_path: Path
    summary_path: Path
    exit_code: int


def run_pipeline(
    area: AreaOfInterest,
    *,
    overpass_http,
    nvdb_http,
    settings: Settings | None = None,
    osm_data: dict | None = None,
    nvdb_links: list[NvdbLink] | None = None,
    winter_objects: list[WinterObject] | None = None,
    skip_datakatalog: bool = False,
) -> PipelineResult:
    settings = settings or Settings()
    out_dir = Path(settings.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    run = settings.run_name

    area = _ensure_bbox(area, nvdb_http, settings)

    if osm_data is not None:
        ways, _nodes = parse_overpass_elements(osm_data)
    else:
        ways, _nodes = fetch_osm_ways(
            overpass_http, area, settings, cache_key=f"{run}_overpass"
        )

    if nvdb_links is None:
        nvdb_links = fetch_segmented_links(
            nvdb_http, area, settings, cache_key_prefix=f"{run}_nvdb"
        )

    if winter_objects is None and not skip_datakatalog:
        winter_objects = _load_winter(nvdb_http, area, settings, run)
    winter_objects = winter_objects or []

    matches, unmatched_osm, unmatched_nvdb = match_ways(ways, nvdb_links, settings)
    suggestions: list[WaySuggestion] = []
    discrepancies: list[WaySuggestion] = []
    all_chain: list[ChainPoint] = []

    split_cfg = SplitConfig(
        window_m=settings.rolling_window_m,
        spread_pp=settings.split_spread_pp,
        min_segment_m=settings.min_segment_m,
    )

    for m in matches:
        profile = elevation_profile(m.way, m.links)
        if len(profile) < 2:
            continue
        stats = gradient_stats(profile, settings.rolling_window_m)
        segments, split = suggest_segments(profile, split_cfg)
        sug = WaySuggestion(
            match=m,
            profile=profile,
            stats=stats,
            segments=segments,
            split=split,
            existing_incline=m.way.existing_incline,
        )
        if m.way.existing_incline:
            sug.skip_reason = "existing incline=* not overwritten"
            discrepancies.append(sug)
            suggestions.append(sug)
        else:
            sug.tags_to_add = build_way_tags(sug)
            suggestions.append(sug)

        all_chain.extend(
            advise_chain_points(
                profile,
                m.way.id,
                m.links,
                winter_objects,
                settings,
            )
        )

    chain_points = cluster_points(all_chain, settings.cluster_distance_m)

    osm_path = out_dir / f"{run}.osm"
    write_osm(osm_path, suggestions, chain_points)

    summary_path = out_dir / f"{run}_summary.md"
    write_markdown_summary(
        summary_path,
        area_label=run,
        matches=matches,
        unmatched_osm=unmatched_osm,
        unmatched_nvdb_count=len(unmatched_nvdb),
        suggestions=suggestions,
        discrepancies=discrepancies,
        chain_points=chain_points,
    )
    write_csv_summary(out_dir / f"{run}_suggestions.csv", suggestions)
    write_unmatched_report(
        out_dir / f"{run}_unmatched.md",
        unmatched_osm,
        [f"{lk.veglenkesekvensid}:{lk.kortform}" for lk in unmatched_nvdb],
    )
    write_chain_geojson(out_dir / f"{run}_chain_advisory.geojson", chain_points)

    print(REVIEW_REMINDER)
    print(f"Wrote {osm_path}")
    print(f"Wrote {summary_path}")

    # Exit 2 if nothing matched at all — useful for CI/CLI.
    exit_code = 0 if matches else 2
    return PipelineResult(
        suggestions=suggestions,
        discrepancies=discrepancies,
        chain_points=chain_points,
        matches=matches,
        unmatched_osm=unmatched_osm,
        unmatched_nvdb=unmatched_nvdb,
        osm_path=osm_path,
        summary_path=summary_path,
        exit_code=exit_code,
    )


def _ensure_bbox(
    area: AreaOfInterest, nvdb_http, settings: Settings
) -> AreaOfInterest:
    if area.kind != "kommune" or area.kommune is None:
        return area
    if area.max_lon > area.min_lon and area.max_lat > area.min_lat:
        return area
    bbox = fetch_kommune_bbox(nvdb_http, area.kommune, settings)
    if bbox is None:
        log.warning(
            "kommune %s bbox unknown; Overpass uses area query, NVDB uses kommune filter",
            area.kommune,
        )
        return area
    return AreaOfInterest(
        kind="kommune",
        min_lon=bbox[0],
        min_lat=bbox[1],
        max_lon=bbox[2],
        max_lat=bbox[3],
        kommune=area.kommune,
    )


def _load_winter(nvdb_http, area, settings, run: str) -> list[WinterObject]:
    try:
        types = search_winter_object_types(
            nvdb_http, settings, cache_key=f"{run}_datakatalog"
        )
    except Exception:
        log.info("datakatalog unavailable; continuing without winter objects")
        return []
    ids = []
    for t in types:
        tid = t.get("id") or t.get("typeId")
        if tid is not None:
            ids.append(int(tid))
    if not ids:
        return []
    return fetch_winter_objects(nvdb_http, area, ids, settings)
