# josm-elevation (`nvdb_incline`)

JOSM plugin that helps Norwegian OSM mappers suggest `incline=*` tags, snow-chain advisory points, and (with strict tagging rules) sharp-curve / accident-cluster advisories, using data from Statens Vegvesen's NVDB API.

**This plugin never uploads to OpenStreetMap.** Accepted suggestions become ordinary undoable JOSM edits (`ChangePropertyCommand` / `AddCommand`). You only upload if you later use JOSM's own Upload action after review.

## Documentation

| Doc | Contents |
|-----|----------|
| [`docs/build-and-dependencies.md`](docs/build-and-dependencies.md) | JDK/Gradle/JOSM versions, verified build/test/dist/run commands, install paths |
| [`docs/codebase-map.md`](docs/codebase-map.md) | Module map, responsibility → class table, end-to-end incline walkthrough |
| [`docs/debugging.md`](docs/debugging.md) | `runJosm` / `debugJosm`, logging reality, fixture capture, common failures |

## Modules

| Module | Role |
|--------|------|
| `core` | Pure JVM logic: gradient, OSM/NVDB matching, snow-chain heuristics, curvature detection, accident clustering, review accept/reject model. No JOSM dependency. |
| `plugin` | JOSM adapter: menu entry, NVDB HTTP client + cache, review dialog, Commands, validator. |
| `prototype/` | Earlier Python CLI (algorithm reference only; not needed to build or run the plugin). |

## APIs used

The plugin is **read-only**. All remote HTTP from the plugin itself goes to Statens vegvesen's [NVDB API Les](https://nvdbapiles.atlas.vegvesen.no) (`https://nvdbapiles.atlas.vegvesen.no`). OSM ways come from the active JOSM edit layer (already downloaded by JOSM); this plugin does not call the OSM API or Overpass.

| API | Base / path | Used for |
|-----|-------------|----------|
| NVDB Vegnett v4 | `GET /vegnett/api/v4/veglenkesekvenser/segmentert` | 3D segmented road-link geometry for incline estimates |
| NVDB Vegobjekter v4 | `GET /vegobjekter/api/v4/vegobjekter/96` | Skiltplate (warning signs: Farlig sving / Farlig vegkryss) |
| NVDB Vegobjekter v4 | `GET /vegobjekter/api/v4/vegobjekter/570` | Trafikkulykke (accident points for clustering) |

Queries use SRID 5973 and a UTM `kartutsnitt` derived from the layer bbox. Responses are cached under JOSM's cache directory (`nvdb_incline`). Client: `NvdbClient`.

**Not used by the plugin:** OSM map/changeset/write API, OAuth, Overpass.

The optional `prototype/` CLI additionally calls Overpass (`https://overpass-api.de/api/interpreter`) plus NVDB Datakatalog (`/datakatalog/api/v1/vegobjekttyper`) and kommuner (`/omrader/api/v4/kommuner`). `tools/capture_fixtures.py` talks only to JOSM Remote Control on localhost.

## Requirements

- JDK 17+ (build emits Java 17 bytecode; JDK 21 works)
- Network once to download Gradle/JOSM dependencies; afterwards `./gradlew test` can run from the dependency cache

Details: [`docs/build-and-dependencies.md`](docs/build-and-dependencies.md).

## Build (short)

```bash
./gradlew build
./gradlew test
./gradlew :plugin:dist          # → plugin/build/dist/nvdb_incline.jar
./gradlew :plugin:runJosm       # clean temp JOSM with this plugin
```

## Install into your normal JOSM

1. `./gradlew :plugin:dist`
2. Copy `plugin/build/dist/nvdb_incline.jar` into the JOSM plugins directory (Linux current: `~/.local/share/JOSM/plugins/`; see build doc for macOS/Windows/legacy).
3. Fully restart JOSM; enable **nvdb_incline** if needed.
4. **More tools → Suggest inclines from NVDB…**

## Review-before-apply workflow

1. Download an area in JOSM that contains Norwegian `highway=*` ways (many already carry `nvdb:id` from Elveg).
2. **More tools → Suggest inclines from NVDB…**
3. The plugin reads the active edit layer and fetches, for that bbox (cached under JOSM's cache directory):
   - NVDB segmented road-link geometry (3D) for inclines
   - NVDB Skiltplate (type **96**) for Farlig sving / Farlig vegkryss codes
   - NVDB Trafikkulykke (type **570**) for accident clustering
4. A **review dialog** lists every proposal in sections (inclines, snow chains, curves confirmed by sign, geometry-only curves, accident clusters). Tick only what you accept. Shortcut: “Accept all high-confidence”.
5. **Apply selected** registers undoable edits using only the tags in [Applied OSM tags](#applied-osm-tags-reference) below.
6. Spot-check against imagery, signs, and local knowledge. Existing `incline=*` is never overwritten (shown as discrepancies only). Dubious track/path matches and absurd grades are filtered before they reach the review list. When a split is useful, the review dialog shows a **Split suggested** badge — splitting is done with JOSM's own tools, not via OSM tags.
7. Upload manually from JOSM only after that review. Ctrl+Z undoes plugin edits like any other change.

## Applied OSM tags reference

Source of truth: `AppliedTags`, `SuggestionApplier.sanitizeTags`, `SafetyAnalyzer`, `ReviewModel.chainTags`. Only these keys are written to the data layer on Apply.

### Ways (incline suggestions)

| Tag | Example | When applied | Note |
|-----|---------|--------------|------|
| `incline` | `7%`, `-11%` | Accepted way row with no existing `incline=*` | Signed integer percent relative to **way node order** ([OSM incline](https://wiki.openstreetmap.org/wiki/Key:incline)); produced by `InclineTags.formatIncline` from NVDB 3D geometry. Split cases still get one whole-way average value. |
| `source:incline` | `nvdb_estimate` | Always with a new incline suggestion | OSM **`source:<key>`** prefix convention (“source of this attribute”), not `incline:source`. See [Key:source](https://wiki.openstreetmap.org/wiki/Key:source). |
| `fixme` | `NVDB-estimated incline; verify in field before keeping. Source is NVDB 3D geometry, not a survey.` | With incline suggestion | English machine-estimate flag so unfinished guesses are hard to miss before upload. |
| `note` | `Maskinelt NVDB-estimat, ikke feltverifisert. Kontroller mot skilt/terreng.` | With incline suggestion | Norwegian mapper-facing hint; same intent as `fixme`. |

### Nodes — sign-backed hazard

| Tag | Example | When applied | Note |
|-----|---------|--------------|------|
| `hazard` | `dangerous_junction` or `curve` | **Only** when an NVDB warning skilt matched (`signConfirmed`) | Deliberate safety rule: OSM `hazard=*` requires a posted sign / official marking — never from geometry or accident counts alone. |
| `source:hazard` | `nvdb_sign` | With `hazard=*` | Prefix form; value means the hazard suggestion is backed by NVDB Skiltplate data. |
| `fixme` | e.g. `NVDB-sign-backed hazard=dangerous_junction suggestion; verify posted sign on site.` | With `hazard=*` | Still asks for field check of the physical sign. |
| `note` | Norwegian text citing skiltnummer / context | With `hazard=*` | Human-readable justification. |

### Nodes — snow-chain advisory (implemented)

| Tag | Example | When applied | Note |
|-----|---------|--------------|------|
| `chain_advisory` | `fit` or `remove` | Accepted chain row | **Not** an established Norwegian OSM tagging scheme — review-only hint from grade/tunnel heuristics (`ChainAdvisory`). |
| `source:chain_advisory` | `nvdb_estimate` | With `chain_advisory` | Prefix form; marks machine origin. |
| `note` | `Foreslatt kjettingplass …` / `Foreslatt kjettingavtakingspunkt …` | With `chain_advisory` | Norwegian text from `InclineTags.CHAIN_NOTE_*`. |

### Nodes — unsigned advisories (implemented; never `hazard=*`)

| Tag | Example | When applied | Note |
|-----|---------|--------------|------|
| `safety_advisory` | `sharp_curve` or `accident_cluster` | Geometry-only sharp curve, or accident cluster **without** matching skilt | Explicitly **not** `hazard=*`. Count/period for clusters live in `note`, not separate OSM keys. |
| `note` | Explains advisory + “IKKE hazard=* …” | With `safety_advisory` | Keeps the legal/tagging constraint visible to mappers. |

### Where confidence / estimate data goes

Former bookkeeping tags such as `incline:match_confidence`, `incline:match_method`, `incline:match_hausdorff_m`, `incline:estimated_avg`, `incline:estimated_max_sustained`, `incline:suggested`, `incline:suggested_segments`, and `incline:split_recommended` are **not** written to OSM objects.

They remain available to reviewers as structured `InclineAudit` data on each incline review row and are shown in the review dialog (Method, H(m), Proposed, Raw avg/max, Split columns, plus hover tooltips). There is currently **no** sidecar debug log file for this data — see [`docs/debugging.md`](docs/debugging.md).

Do not reintroduce `incline:source` / `hazard:source`; use `source:incline` / `source:hazard`.

## Manual review sample (`test-files/`)

Real-world Innlandet OSM extract used for end-to-end plugin QA (Friisvegen / Vekkomsvegen area):

| File | Contents |
|------|----------|
| `test-files/test.osm` | Input layer before suggestions |
| `test-files/test-out.osm` | Same area after running the plugin and applying selected suggestions (example output only; not authoritative OSM data) |

Open `test.osm` in JOSM, run **Suggest inclines from NVDB…**, and optionally compare with `test-out.osm`. Automated coverage remains under `tests/fixtures/steep_roads/` and the unit tests.

## OSM `hazard=*` tagging rule

OSM documents `hazard=*` for hazards backed by a **posted traffic sign** (or official declaration), not raw accident counts. This plugin follows that:

| Finding | Tag outcome |
|---------|-------------|
| Sharp curve **and** nearby NVDB Farlig-sving skilt (e.g. 100.1 / 100.2 / 102.x) | May suggest `hazard=curve` (high confidence) |
| Sharp curve, **no** matching sign | Advisory only (`safety_advisory=sharp_curve`) |
| Accident cluster **and** matching Farlig vegkryss / curve skilt | May suggest `hazard=dangerous_junction` or `hazard=curve` |
| Accident cluster, **no** matching sign | Advisory only (`safety_advisory=accident_cluster` + count/period in `note=*`) |

Accident statistics alone never produce `hazard=*`. Construction and apply paths reject unsigned `hazard=*` (see `HazardTagSafetyTest`).

## Validator

Ways with `source:incline=nvdb_estimate` get a validator reminder so unfinished estimates are harder to upload by accident.

## Tests

```bash
./gradlew test
```

- **`core`**: JUnit 5 — gradient, matching, chain heuristics, curvature, sign cross-check, accident clustering, review/`InclineAudit` filtering. No JOSM, no network in the tests themselves.
- **`plugin`**: headless in-memory `DataSet` tests for Command apply/undo; offline NVDB fixture parse; **no-upload** safety grep; **hazard tag-safety** regression; **steep-road fixtures** under `tests/fixtures/steep_roads/`.

## Safety constraints

- No `UploadAction`, no OSM changeset/write API calls, no OAuth
- Working OSM data comes from the active JOSM layer; remote reads are NVDB only (no Overpass in this plugin)
- `hazard=*` only after NVDB sign confirmation; geometry/accident advisories stay `note=*` / `safety_advisory=*`
- Safety regression tests run on every `./gradlew test`

## Prototype (optional)

The Python CLI under `prototype/` was an earlier standalone experiment. See `prototype/README.md` if you want to run those offline tests for comparison.

## Developer tools

`tools/capture_fixtures.py` drives a running JOSM Remote Control session to load/export steep-road OSM fixtures (never uploads). See `tools/README.md` and [`docs/debugging.md`](docs/debugging.md).

## License

Plugin code is intended for use with JOSM (GPL). Treat redistribution of the built plugin accordingly. The `prototype/` Python code remains separately usable as reference tooling.
