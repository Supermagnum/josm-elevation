# Steep-road fixtures (Innlandet)

Recorded offline fixtures for three known-steep OSM ways near Venabygd /
Ringebu (Innlandet), used by `SteepRoadFixtureIT`. **Do not auto-refresh** —
these are snapshots. Re-capture manually when OSM edits or NVDB revisions
make the recorded gradients stale.

## Target ways

| OSM way | Name | Highway | Role in suite |
|--------:|------|---------|---------------|
| `764390363` | Venabygdsvegen | primary | Steep climb, solid NVDB coverage |
| `757907237` | Friisvegen | secondary | Steep climb; southern tip overhangs NVDB coverage (MEDIUM match) |
| `330233844` | Kilevegen | secondary | Long descent; steepest \|avg\| of the three |

None of these exports carried `nvdb:id` at capture time — matching uses geometry.

## Bbox notes

Reference point from the prompt: **61.54483, 10.12352**.

A single **zoom-14 tile** centred on that point (`10.112534,61.539595,10.134506,61.550064`)
does **not** contain any of the three ways. Files:

| File | Bbox / content |
|------|----------------|
| `osm/area_z14.osm` | Zoom-14 tile at the reference point (none of the three ways fall inside; context only) |
| `osm/area.osm` | Merge of the three `way_*/full` exports (compact CI fixture). Full Overpass `out meta` for the union bbox is ~6MB and was not checked in; regenerate locally if you need all highways in the bbox. |
| `osm/way_*.osm` | Each way via OSM `way/{id}/full` |
| `nvdb/area.json` | NVDB `vegnett/.../segmentert` for the union bbox of the three ways + 250m buffer (EPSG:25833 / srid=5973), 2421 objects |

## Recorded pipeline results (2026-08-14)

Computed by `SteepRoadFixtureDumpTest` against these fixtures (no live network).
Locale-independent values:

| Way | Match | Avg gradient | Max sustained | z start→end (m) | Incline sign along nodes | Chain advisory |
|----:|-------|-------------:|--------------:|-----------------|--------------------------|----------------|
| `764390363` | HIGH (geometry) | **+9.576%** | +12.139% | 301.2 → 409.9 | **positive** (uphill) | FIT |
| `757907237` | MEDIUM (geometry) | **+5.656%** | +11.605% | 384.7 → 510.7 | **positive** (uphill) | FIT (several) |
| `330233844` | HIGH (geometry) | **−6.374%** | −11.364% | 859.4 → 404.8 | **negative** (downhill) | REMOVE |

Tightened test tolerance: recorded average **± 1.5** percentage points.

NVDB API responses at capture did not embed a datakatalog version string in
segmentert `metadata` (only pagination). Treat **capture date 2026-08-14** as
the freshness marker; re-dump after NVDB network revisions.

### Sign / direction (manual check)

Node order defines OSM `incline=*` sign:

- **Venabygdsvegen** / **Friisvegen**: first node is downhill end → positive averages.
- **Kilevegen**: first node is high (≈859 m), last is low (≈405 m) → negative average.

### Local knowledge

- **Venabygdsvegen** (`764390363`) has the highest absolute average gradient of the three (~9.6%).
- **Kilevegen** (`330233844`) has the largest elevation drop (~455 m) and the longest / curviest alignment; chain advisories are REMOVE (descent).
- Friisvegen’s south end sits outside dense NVDB coverage in this snapshot (hence MEDIUM + high p90 distance); incline math still sees a clear climb on the covered majority.

## Refresh procedure

0. Optional: load/export OSM via a running JOSM Remote Control session:
   ```bash
   python tools/capture_fixtures.py \
     --ways 764390363,757907237,330233844 \
     --bbox <left>,<bottom>,<right>,<top>
   ```
   (writes `steep_way_*.osm` / `steep_area.osm`; see `tools/README.md`)
1. Re-download the three `way/{id}/full` OSM files and Overpass `area.osm` for the union bbox
   (or rename/copy the Remote Control exports into `way_*.osm` / `area.osm` if you prefer those names).
2. Re-query NVDB segmentert for the UTM33 kartutsnitt of that bbox; save as `nvdb/area.json`.
3. Run:
   ```bash
   ./gradlew :plugin:test --tests '*.SteepRoadFixtureDumpTest'
   ```
4. Copy printed averages/signs into this README and into `EXPECTED` in `SteepRoadFixtureIT`.
5. Eyeball numbers against terrain/skilt before committing.

## Related matcher fix

These fixtures exposed that **symmetric Hausdorff** against long NVDB
`veglenkesekvens` geometries cannot match real OSM ways that span multiple
sequences or only partially overlap a sequence. `WayMatcher` now uses
overlap + **coverage-fraction** confidence (see core unit test
`longOsmWayMatchesOverlappingShortNvdbSegments`).
