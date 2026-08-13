"""Data models used across the pipeline."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from shapely.geometry import LineString, Point

Confidence = Literal["high", "medium", "low"]
ChainKind = Literal["fit", "remove", "fit;remove"]


@dataclass
class OsmNode:
    id: int
    lat: float
    lon: float
    version: int | None = None
    tags: dict[str, str] = field(default_factory=dict)


@dataclass
class OsmWay:
    id: int
    node_ids: list[int]
    tags: dict[str, str]
    version: int | None = None
    # LineString in EPSG:25833 (x, y); Z is not stored on OSM nodes.
    line_utm: LineString | None = None
    nodes: list[OsmNode] = field(default_factory=list)

    @property
    def nvdb_id(self) -> str | None:
        for key in ("nvdb:id", "nvdb:veglenkesekvensid"):
            if key in self.tags:
                return self.tags[key]
        return None

    @property
    def highway(self) -> str | None:
        return self.tags.get("highway")

    @property
    def existing_incline(self) -> str | None:
        return self.tags.get("incline")


@dataclass
class NvdbLink:
    veglenkesekvensid: int
    kortform: str
    type: str
    type_veg: str
    medium: str | None
    kommune: int | None
    lengde: float
    wkt: str
    srid: int
    line_utm: LineString  # 3D when Z is present
    startposisjon: float = 0.0
    sluttposisjon: float = 1.0

    @property
    def has_z(self) -> bool:
        return self.line_utm.has_z

    @property
    def is_connector(self) -> bool:
        return (self.type or "").upper() == "KONNEKTERING"

    @property
    def is_tunnel(self) -> bool:
        medium = (self.medium or "").upper()
        return medium in {"T", "TUNNEL"} or "tunnel" in (self.type_veg or "").lower()


@dataclass
class MatchResult:
    way: OsmWay
    links: list[NvdbLink]
    confidence: Confidence
    method: str
    hausdorff_m: float | None = None
    notes: str = ""


@dataclass
class ElevationSample:
    distance_m: float
    elevation_m: float
    x: float
    y: float


@dataclass
class GradientStats:
    average_pct: float
    max_sustained_pct: float
    min_window_pct: float
    max_window_pct: float
    spread_pp: float
    length_m: float


@dataclass
class SegmentSuggestion:
    start_m: float
    end_m: float
    average_pct: float
    max_sustained_pct: float
    incline_tag: str
    start_xy: tuple[float, float]
    end_xy: tuple[float, float]


@dataclass
class WaySuggestion:
    match: MatchResult
    profile: list[ElevationSample]
    stats: GradientStats
    segments: list[SegmentSuggestion]
    split: bool
    skip_reason: str | None = None  # e.g. existing incline discrepancy
    existing_incline: str | None = None
    tags_to_add: dict[str, str] = field(default_factory=dict)


@dataclass
class ChainPoint:
    x: float
    y: float
    kind: ChainKind
    reason: str
    way_id: int | None = None


@dataclass
class WinterObject:
    type_id: int
    type_name: str
    point: Point | None
    line: LineString | None
    name: str | None = None


@dataclass
class AreaOfInterest:
    kind: Literal["bbox", "kommune", "poly", "osm"]
    min_lon: float
    min_lat: float
    max_lon: float
    max_lat: float
    kommune: int | None = None
    polygon_lonlat: list[tuple[float, float]] | None = None
    osm_path: str | None = None

    @property
    def bbox_lonlat(self) -> tuple[float, float, float, float]:
        return (self.min_lon, self.min_lat, self.max_lon, self.max_lat)
