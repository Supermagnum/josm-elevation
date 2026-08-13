"""Runtime settings. No upload flags exist and none may be added."""

from __future__ import annotations

from dataclasses import dataclass, field


DEFAULT_EXCLUDE_HIGHWAYS = ("footway", "path", "steps")
DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"
DEFAULT_NVDB_BASE = "https://nvdbapiles.atlas.vegvesen.no"

# Identify this client to NVDB. Read-only review tool.
NVDB_X_CLIENT = "nvdb-incline-review"
USER_AGENT = (
    "nvdb-incline-review/0.1 "
    "(local OSM review aid; does not write to OpenStreetMap)"
)

SUGGESTION_NOTE = (
    "Maskinelt NVDB-estimat, ikke feltverifisert. "
    "Kontroller mot skilt/terreng for NVDB-høydeprofillen stemmer."
)
SUGGESTION_FIXME = (
    "NVDB-estimated incline; verify in field before keeping. "
    "Source is NVDB 3D geometry, not a survey."
)
CHAIN_NOTE_FIT = (
    "Foreslatt kjettingplass (NVDB-basert forslag, verifiser i felt). "
    "Ikke et etablert OSM-tagg i Norge; kun til manuell vurdering."
)
CHAIN_NOTE_REMOVE = (
    "Foreslatt kjettingavtakingspunkt (NVDB-basert forslag, verifiser i felt). "
    "Ikke et etablert OSM-tagg i Norge; kun til manuell vurdering."
)

REVIEW_REMINDER = """
REVIEW ONLY. This tool never uploads to OpenStreetMap.
1. Open the .osm file in JOSM.
2. Spot-check suggested incline=* and chain_advisory nodes against imagery, signs, and local knowledge.
3. Delete anything you cannot verify; keep only tags you would stand behind as a mapper.
4. If you upload, do it manually from JOSM after that review.
""".strip()


@dataclass(frozen=True)
class Settings:
    overpass_url: str = DEFAULT_OVERPASS_URL
    nvdb_base: str = DEFAULT_NVDB_BASE
    exclude_highways: tuple[str, ...] = DEFAULT_EXCLUDE_HIGHWAYS
    rolling_window_m: float = 50.0
    split_spread_pp: float = 4.0
    min_segment_m: float = 50.0
    chain_gradient_pct: float = 6.0
    chain_min_distance_m: float = 200.0
    cluster_distance_m: float = 75.0
    hausdorff_high_m: float = 15.0
    hausdorff_medium_m: float = 30.0
    nearest_fallback_m: float = 50.0
    overpass_interval_s: float = 1.0
    nvdb_interval_s: float = 0.25
    cache_dir: str | None = ".cache/nvdb-incline"
    fixture_dir: str | None = None
    output_dir: str = "."
    run_name: str = "nvdb_incline"
    extra_overpass_hosts: tuple[str, ...] = field(default_factory=tuple)
