"""Write JOSM-loadable .osm XML for suggested tags and advisory nodes.

Only emits modified/new elements. Never uploads.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path
from xml.dom import minidom

from nvdb_incline.config import (
    CHAIN_NOTE_FIT,
    CHAIN_NOTE_REMOVE,
    SUGGESTION_FIXME,
    SUGGESTION_NOTE,
)
from nvdb_incline.geo import utm_to_lonlat
from nvdb_incline.gradient import format_incline
from nvdb_incline.models import ChainPoint, WaySuggestion


def write_osm(
    path: Path,
    suggestions: list[WaySuggestion],
    chain_points: list[ChainPoint],
    *,
    generator: str = "nvdb-incline",
    comment: str = (
        "Review-only NVDB incline estimates; do not upload without field check"
    ),
) -> None:
    osm = ET.Element(
        "osm",
        {
            "version": "0.6",
            "generator": generator,
            "upload": "false",
        },
    )
    # Embedded edit summary for reviewers.
    note = ET.SubElement(osm, "note")
    note.text = comment

    next_neg_id = -1
    for sug in suggestions:
        if sug.skip_reason:
            continue
        way = sug.match.way
        attrs = {
            "id": str(way.id),
            "action": "modify",
            "visible": "true",
        }
        if way.version is not None:
            attrs["version"] = str(way.version)
        el = ET.SubElement(osm, "way", attrs)
        for nid in way.node_ids:
            ET.SubElement(el, "nd", {"ref": str(nid)})
        # Preserve existing tags, then add suggestions (never overwrite incline).
        tags = dict(way.tags)
        tags.update(sug.tags_to_add)
        for k, v in sorted(tags.items()):
            ET.SubElement(el, "tag", {"k": k, "v": v})

        # If split was recommended, emit helper nodes marking segment boundaries.
        if sug.split and len(sug.segments) > 1:
            for i, seg in enumerate(sug.segments):
                lon, lat = utm_to_lonlat(*seg.start_xy)
                node = ET.SubElement(
                    osm,
                    "node",
                    {
                        "id": str(next_neg_id),
                        "lat": f"{lat:.7f}",
                        "lon": f"{lon:.7f}",
                        "action": "modify",
                        "visible": "true",
                    },
                )
                next_neg_id -= 1
                ET.SubElement(
                    node,
                    "tag",
                    {
                        "k": "note",
                        "v": (
                            f"Foreslatt splittepunkt for incline={seg.incline_tag} "
                            f"på way {way.id} (segment {i + 1}/{len(sug.segments)}). "
                            "NVDB-basert; verifiser før manuell splitting i JOSM."
                        ),
                    },
                )

    for cp in chain_points:
        lon, lat = utm_to_lonlat(cp.x, cp.y)
        node = ET.SubElement(
            osm,
            "node",
            {
                "id": str(next_neg_id),
                "lat": f"{lat:.7f}",
                "lon": f"{lon:.7f}",
                "action": "modify",
                "visible": "true",
            },
        )
        next_neg_id -= 1
        note_text = CHAIN_NOTE_FIT if "fit" in cp.kind else CHAIN_NOTE_REMOVE
        if cp.kind == "fit;remove":
            note_text = CHAIN_NOTE_FIT + " / " + CHAIN_NOTE_REMOVE
        ET.SubElement(node, "tag", {"k": "note", "v": note_text})
        ET.SubElement(node, "tag", {"k": "chain_advisory", "v": cp.kind})
        ET.SubElement(node, "tag", {"k": "source:chain_advisory", "v": "nvdb_estimate"})

    path.parent.mkdir(parents=True, exist_ok=True)
    rough = ET.tostring(osm, encoding="utf-8")
    pretty = minidom.parseString(rough).toprettyxml(indent="  ", encoding="utf-8")
    path.write_bytes(pretty)


def build_way_tags(sug: WaySuggestion) -> dict[str, str]:
    """Applied OSM tags for a matched way (bookkeeping stays off-tag)."""
    if not sug.segments:
        return {}
    incline = (
        format_incline(sug.stats.average_pct)
        if sug.split
        else sug.segments[0].incline_tag
    )
    return {
        "incline": incline,
        "source:incline": "nvdb_estimate",
        "note": SUGGESTION_NOTE,
        "fixme": SUGGESTION_FIXME,
    }
