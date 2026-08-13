"""Human-readable Markdown / CSV / GeoJSON summary reports."""

from __future__ import annotations

import csv
import json
from collections import Counter
from pathlib import Path

from nvdb_incline.geo import utm_to_lonlat
from nvdb_incline.models import ChainPoint, MatchResult, OsmWay, WaySuggestion


def write_markdown_summary(
    path: Path,
    *,
    area_label: str,
    matches: list[MatchResult],
    unmatched_osm: list[OsmWay],
    unmatched_nvdb_count: int,
    suggestions: list[WaySuggestion],
    discrepancies: list[WaySuggestion],
    chain_points: list[ChainPoint],
) -> None:
    conf = Counter(m.confidence for m in matches)
    tagged = [s for s in suggestions if not s.skip_reason]
    lines = [
        f"# NVDB incline review summary — {area_label}",
        "",
        "This run is **review-only**. Nothing was uploaded to OpenStreetMap.",
        "",
        "## Counts",
        "",
        f"- OSM ways matched: **{len(matches)}**",
        f"- OSM ways unmatched: **{len(unmatched_osm)}**",
        f"- NVDB links unmatched: **{unmatched_nvdb_count}**",
        f"- Ways with suggested incline tags: **{len(tagged)}**",
        f"- Ways with existing incline discrepancy (not overwritten): **{len(discrepancies)}**",
        f"- Chain-advisory candidate nodes: **{len(chain_points)}**",
        "",
        "## Match confidence",
        "",
        f"- high: {conf.get('high', 0)}",
        f"- medium: {conf.get('medium', 0)}",
        f"- low: {conf.get('low', 0)}",
        "",
        "## Discrepancies (existing incline=* kept)",
        "",
    ]
    if not discrepancies:
        lines.append("_None._")
    else:
        for d in discrepancies:
            lines.append(
                f"- way {d.match.way.id}: existing `{d.existing_incline}` vs "
                f"suggested `{d.segments[0].incline_tag if d.segments else '?'}` "
                f"(confidence={d.match.confidence})"
            )
    lines.extend(["", "## Unmatched OSM ways (sample)", ""])
    if not unmatched_osm:
        lines.append("_None._")
    else:
        for w in unmatched_osm[:50]:
            name = w.tags.get("name", "")
            hw = w.tags.get("highway", "")
            lines.append(f"- way {w.id} highway={hw} {name}".rstrip())
        if len(unmatched_osm) > 50:
            lines.append(f"- ... and {len(unmatched_osm) - 50} more")
    lines.extend(
        [
            "",
            "## Next steps",
            "",
            "1. Open the companion `.osm` file in JOSM.",
            "2. Spot-check high-confidence tags first.",
            "3. Delete or edit anything that does not match signs / terrain.",
            "4. Upload manually from JOSM only after review.",
            "",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def write_csv_summary(path: Path, suggestions: list[WaySuggestion]) -> None:
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(
            fh,
            fieldnames=[
                "way_id",
                "highway",
                "name",
                "confidence",
                "method",
                "hausdorff_m",
                "avg_pct",
                "max_sustained_pct",
                "suggested_incline",
                "existing_incline",
                "split",
                "skip_reason",
            ],
        )
        writer.writeheader()
        for s in suggestions:
            writer.writerow(
                {
                    "way_id": s.match.way.id,
                    "highway": s.match.way.highway or "",
                    "name": s.match.way.tags.get("name", ""),
                    "confidence": s.match.confidence,
                    "method": s.match.method,
                    "hausdorff_m": (
                        f"{s.match.hausdorff_m:.2f}"
                        if s.match.hausdorff_m is not None
                        else ""
                    ),
                    "avg_pct": f"{s.stats.average_pct:.2f}",
                    "max_sustained_pct": f"{s.stats.max_sustained_pct:.2f}",
                    "suggested_incline": (
                        s.segments[0].incline_tag if s.segments else ""
                    ),
                    "existing_incline": s.existing_incline or "",
                    "split": "yes" if s.split else "no",
                    "skip_reason": s.skip_reason or "",
                }
            )


def write_unmatched_report(
    path: Path, unmatched_osm: list[OsmWay], unmatched_nvdb_ids: list[str]
) -> None:
    lines = ["# Unmatched for manual triage", "", "## OSM ways", ""]
    for w in unmatched_osm:
        lines.append(
            f"- {w.id}\t{w.tags.get('highway', '')}\t{w.tags.get('name', '')}"
        )
    lines.extend(["", "## NVDB link keys", ""])
    for k in unmatched_nvdb_ids[:500]:
        lines.append(f"- {k}")
    if len(unmatched_nvdb_ids) > 500:
        lines.append(f"- ... and {len(unmatched_nvdb_ids) - 500} more")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_chain_geojson(path: Path, points: list[ChainPoint]) -> None:
    features = []
    for p in points:
        lon, lat = utm_to_lonlat(p.x, p.y)
        features.append(
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
                "properties": {
                    "chain_advisory": p.kind,
                    "reason": p.reason,
                    "way_id": p.way_id,
                },
            }
        )
    path.write_text(
        json.dumps({"type": "FeatureCollection", "features": features}, indent=2),
        encoding="utf-8",
    )
