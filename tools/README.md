# tools/ — local developer utilities

These scripts are **not** part of the JOSM plugin and are **not** wired into CI.

## `refresh_kommune_boundaries.py`

Download Kartverket / Geonorge “Administrative enheter kommuner” GeoJSON (EPSG:4258
land-wide zip) and rewrite the bundled resource
`core/src/main/resources/no/nvdbincline/core/kommune/kommune_boundaries_YYYY-MM-DD.json`.

Verified URL (2026-08):
`https://nedlasting.geonorge.no/geonorge/Basisdata/Kommuner/GeoJSON/Basisdata_0000_Norge_4258_Kommuner_GeoJSON.zip`

```bash
pip install requests pytest
python tools/refresh_kommune_boundaries.py
python tools/refresh_kommune_boundaries.py --zip /path/to/local.zip
pytest tools/tests/test_refresh_kommune_boundaries.py
```

## `refresh_kommune_list.py`

Re-download Regjeringen’s official kommune number/name spreadsheet and rewrite the
bundled plugin resource
`core/src/main/resources/no/nvdbincline/core/kommune/kommuner_YYYY-MM-DD.json`.

The plugin **never** fetches that xlsx at runtime — only this script does, on demand.

```bash
pip install requests openpyxl pytest
python tools/refresh_kommune_list.py
# or from a local file:
python tools/refresh_kommune_list.py --xlsx /path/to/file.xlsx --effective-date 2024-01-01
```

Headers are matched by name (`kommunenummer` / `kommunenavn` or Regjeringen’s
`Kommune` column), not by column index.

## `capture_fixtures.py`

Drive a running JOSM (Remote Control on port 8111) to load the steep-road
fixture ways / bbox and pull `.osm` XML back via `GET /export` (JOSM r19425+).

**Never uploads to OpenStreetMap.** Only talks to localhost Remote Control;
load commands make *JOSM* download from the OSM API into local layers, and
`/export` reads that local buffer.

### Prerequisites

1. JOSM running
2. Edit → Preferences → Remote Control → enabled
3. JOSM r19425+ for automated export (older builds: script loads layers and
   prints manual File → Save As instructions)
4. Python 3.11+ with `requests` (`pip install requests`)

### Usage

```bash
python tools/capture_fixtures.py \
  --ways 764390363,757907237,330233844 \
  --bbox 10.05,61.50,10.20,61.58 \
  --host localhost --port 8111
```

`--bbox` is **left,bottom,right,top** (same order as JOSM `/load_and_zoom`).
Copy values from your actual JOSM view around `61.54483, 10.12352` — do not
guess.

Writes (by default under `tests/fixtures/steep_roads/osm/`):

- `steep_way_<ID>.osm` for each way
- `steep_area.osm` for the bbox layer

### After this script

1. Fetch matching NVDB `vegnett/.../segmentert` JSON for the same bbox →
   `tests/fixtures/steep_roads/nvdb/area.json`
2. Run the pipeline dump / fill recorded gradients in
   `tests/fixtures/steep_roads/README.md`

### Tests (no live JOSM)

```bash
pip install requests openpyxl pytest
python -m pytest tools/tests -q
```
