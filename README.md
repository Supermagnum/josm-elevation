# nvdb-incline

Review-only CLI that suggests OpenStreetMap `incline=*` tags (and candidate snow-chain fit/remove points) for Norwegian roads by cross-referencing OSM ways with 3D geometry from Statens Vegvesen's NVDB API.

**This tool never uploads to OpenStreetMap.** There is no OAuth flow, no changeset API usage, and no upload flag. Output is a local `.osm` XML file (JOSM-loadable) plus Markdown/CSV/GeoJSON reports for human review. You upload manually from JOSM only after you have verified the suggestions.

## Requirements

- Python 3.11+
- Network access only when fetching live Overpass / NVDB data (tests run fully offline)

## Setup

```bash
python3 -m pip install -e ".[test]"
```

## Usage

Provide exactly one area input:

```bash
# Bounding box (min_lon,min_lat,max_lon,max_lat)
nvdb-incline --bbox 9.70,62.59,9.72,62.61 --output-dir out --run-name demo

# Municipality number (kommune)
nvdb-incline --kommune 5021 --output-dir out --run-name oppdal

# Osmosis .poly file
nvdb-incline --poly area.poly --output-dir out --run-name poly_run

# Bounding box derived from an existing .osm file
nvdb-incline --osm extract.osm --output-dir out --run-name from_osm
```

Offline replay against recorded fixtures (used by tests and local dry-runs):

```bash
nvdb-incline --bbox 9.69,62.59,9.72,62.61 \
  --fixture-dir tests/fixtures/area_steep_flat \
  --output-dir /tmp/nvdb-out \
  --run-name area_steep_flat
```

### Outputs

Per run (`--run-name`):

| File | Purpose |
|------|---------|
| `{name}.osm` | JOSM-loadable suggestions only (modified ways + new advisory nodes). `upload="false"`. |
| `{name}_summary.md` | Counts, confidence breakdown, discrepancies |
| `{name}_suggestions.csv` | Tabular review list |
| `{name}_unmatched.md` | Unmatched OSM ways / NVDB links for triage |
| `{name}_chain_advisory.geojson` | Candidate chain fit/remove points |

Every run prints a reminder to review in JOSM and upload manually.

### Suggested tags (machine-marked)

Ways without an existing `incline=*` may receive:

- `incline=N%` — signed percentage relative to the way's node order (OSM convention)
- `incline:source=nvdb_estimate`
- `incline:match_confidence=high|medium|low`
- `note=*` / `fixme=*` explaining field verification is required

If a way already has `incline=*`, the estimate is only reported as a discrepancy; the existing tag is never overwritten.

Chain advisory nodes use `chain_advisory=fit|remove` plus a Norwegian `note=*` stating this is an unverified NVDB-based proposal. That is intentionally **not** established OSM tagging for Norway.

## Load into JOSM

1. File → Open → select `{name}.osm`
2. Review high-confidence ways first (`incline:match_confidence=high`)
3. Compare with imagery, road signs, and local knowledge
4. Delete suggestions you cannot verify; edit the rest
5. Upload from JOSM yourself (this tool will not do it)

## Testing

All tests are offline. Sockets are blocked via `pytest-socket`:

```bash
make test
```

Equivalent:

```bash
python3 -m pytest --disable-socket --allow-unix-socket
```

The suite includes:

- Unit tests for gradient, rounding, way splitting, chain heuristics, and matching
- A grep-based safety test that forbids OSM write/changeset/OAuth code paths
- Integration + CLI tests against recorded fixtures under `tests/fixtures/`

### Refreshing recorded fixtures

Fixtures under `tests/fixtures/area_*` are small synthetic Overpass/NVDB JSON responses that mirror the real API shapes. To refresh from live APIs for a tiny bbox (rate-limit yourself):

```bash
# Example sketch — run sparingly; prefer caching
python3 - <<'PY'
import json, requests
from pathlib import Path
bbox = "62.59,9.69,62.61,9.72"  # south,west,north,east for Overpass
q = f'[out:json][timeout:60];(way["highway"]({bbox}););(._;>;);out meta;'
r = requests.post("https://overpass-api.de/api/interpreter", data={"data": q},
                  headers={"User-Agent": "nvdb-incline-fixture-refresh/0.1"})
Path("tests/fixtures/live_overpass.json").write_text(r.text)
# NVDB segmented links (UTM33 kartutsnitt). Convert bbox to UTM before use.
print("Saved Overpass fixture; fetch NVDB with kartutsnitt + srid=5973 similarly.")
PY
```

Then point `--fixture-dir` at the directory containing `overpass.json` and `nvdb_segmentert.json` (and optional `datakatalog.json`). Keep fixtures small.

## Design notes

- **Reads only** Overpass and `https://nvdbapiles.atlas.vegvesen.no/`
- Disk cache under `.cache/nvdb-incline/` with per-host rate limiting
- Matching: `nvdb:id` fast path, then Hausdorff / coverage / length / direction scoring
- Gradient: average and max sustained (rolling window, default 50 m); OSM `incline=*` uses the practical max sustained grade
- Way split suggestions when rolling-window spread exceeds `--split-spread-pp` (default 4)

## Non-goals

- No JOSM Java plugin
- No automatic conflict resolution for existing `incline=*`
- No upload, OAuth, changeset creation, or force-upload bypass
