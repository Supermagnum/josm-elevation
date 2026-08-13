"""Overpass read client. Never writes to OSM."""

from __future__ import annotations

import logging
from typing import Any, Protocol

from nvdb_incline.config import Settings
from nvdb_incline.geo import line_from_lonlat
from nvdb_incline.models import AreaOfInterest, OsmNode, OsmWay

log = logging.getLogger(__name__)


class HttpClient(Protocol):
    def post_text(
        self,
        url: str,
        body: str,
        *,
        headers: dict[str, str] | None = None,
        cache_key: str | None = None,
    ) -> Any: ...


DEFAULT_EXCLUDE = ("footway", "path", "steps")


def build_overpass_query(
    area: AreaOfInterest,
    exclude_highways: tuple[str, ...] = DEFAULT_EXCLUDE,
) -> str:
    exclude_clause = "".join(f'["highway"!="{h}"]' for h in exclude_highways)
    if area.kind == "kommune" and area.kommune is not None:
        # Norwegian municipalities as OSM admin_level=7 with ref=XXXX.
        return f"""
[out:json][timeout:180];
area["admin_level"="7"]["ref"="{area.kommune:04d}"]->.searchArea;
(
  way["highway"]{exclude_clause}(area.searchArea);
);
(._;>;);
out meta;
""".strip()
    if area.polygon_lonlat:
        poly = " ".join(f"{lat} {lon}" for lon, lat in area.polygon_lonlat[:-1])
        return f"""
[out:json][timeout:180];
(
  way["highway"]{exclude_clause}(poly:"{poly}");
);
(._;>;);
out meta;
""".strip()
    south, west, north, east = area.min_lat, area.min_lon, area.max_lat, area.max_lon
    return f"""
[out:json][timeout:180];
(
  way["highway"]{exclude_clause}({south},{west},{north},{east});
);
(._;>;);
out meta;
""".strip()


def fetch_osm_ways(
    http: HttpClient,
    area: AreaOfInterest,
    settings: Settings | None = None,
    *,
    cache_key: str | None = None,
) -> tuple[list[OsmWay], dict[int, OsmNode]]:
    settings = settings or Settings()
    query = build_overpass_query(area, settings.exclude_highways)
    data = http.post_text(
        settings.overpass_url,
        query,
        cache_key=cache_key or "overpass_ways",
    )
    return parse_overpass_elements(data)


def parse_overpass_elements(
    data: dict[str, Any],
) -> tuple[list[OsmWay], dict[int, OsmNode]]:
    elements = data.get("elements") or []
    nodes: dict[int, OsmNode] = {}
    ways: list[OsmWay] = []
    for el in elements:
        if el.get("type") == "node":
            nid = int(el["id"])
            nodes[nid] = OsmNode(
                id=nid,
                lat=float(el["lat"]),
                lon=float(el["lon"]),
                version=el.get("version"),
                tags={str(k): str(v) for k, v in (el.get("tags") or {}).items()},
            )
    for el in elements:
        if el.get("type") != "way":
            continue
        tags = {str(k): str(v) for k, v in (el.get("tags") or {}).items()}
        if "highway" not in tags:
            continue
        node_ids = [int(n) for n in el.get("nodes") or []]
        way_nodes = [nodes[n] for n in node_ids if n in nodes]
        line = None
        if len(way_nodes) >= 2:
            line = line_from_lonlat([(n.lon, n.lat) for n in way_nodes])
        ways.append(
            OsmWay(
                id=int(el["id"]),
                node_ids=node_ids,
                tags=tags,
                version=el.get("version"),
                line_utm=line,
                nodes=way_nodes,
            )
        )
    log.info("Overpass: %d ways, %d nodes", len(ways), len(nodes))
    return ways, nodes
