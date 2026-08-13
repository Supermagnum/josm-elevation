# josm-elevation (`nvdb_incline`)

JOSM plugin that helps Norwegian OSM mappers suggest `incline=*` tags, snow-chain advisory points, and (with strict tagging rules) sharp-curve / accident-cluster advisories, using data from Statens Vegvesen's NVDB API.

**This plugin never uploads to OpenStreetMap.** Accepted suggestions become ordinary undoable JOSM edits (`ChangePropertyCommand` / `AddCommand`). You only upload if you later use JOSM's own Upload action after review.

## Modules

| Module | Role |
|--------|------|
| `core` | Pure JVM logic: gradient, OSM/NVDB matching, snow-chain heuristics, curvature detection, accident clustering, review accept/reject model. No JOSM dependency. |
| `plugin` | JOSM adapter: menu entry, NVDB HTTP client + cache, review dialog, Commands, validator. |
| `prototype/` | Earlier Python CLI (algorithm reference only; not needed to build or run the plugin). |

## Requirements

- JDK 17+ (build emits Java 17 bytecode; JDK 21 works)
- Network once to download Gradle/JOSM dependencies; afterwards `./gradlew test` can run from the dependency cache

## Build

```bash
./gradlew build
./gradlew test    # or: make test
```

Installable plugin jars:

| Path | Use |
|------|-----|
| `plugin/build/dist/nvdb_incline.jar` | Copy into JOSM's plugins directory |
| `plugin/build/libs/nvdb_incline-0.1.0.jar` | Same content before manifest packaging |
| `plugin/build/localDist/` | Local plugin update-site for development |

Run a clean JOSM with only this plugin loaded:

```bash
./gradlew :plugin:runJosm
```

## Install into your normal JOSM

1. Build the distributable jar:

```bash
./gradlew :plugin:dist
```

2. Copy `plugin/build/dist/nvdb_incline.jar` into JOSM's plugins directory (create the folder if it does not exist):
   - Linux (current installs): `~/.local/share/JOSM/plugins/`
   - Linux (legacy): `~/.josm/plugins/`
   - macOS: `~/Library/JOSM/plugins/`
   - Windows: `%APPDATA%\JOSM\plugins\`

   Flatpak/Snap/portable builds may use another data directory. In JOSM, open **Help → Show Status Report** and look for the plugins path if the jar does not appear after restart.

3. **Fully restart JOSM** after replacing the jar. Reloading while JOSM is already running can leave a stale `core` class set loaded.

4. Enable **nvdb_incline** under Edit → Preferences → Plugins if it is not already checked.

5. Confirm the menu entry: **More tools → Suggest inclines from NVDB…**

## Review-before-apply workflow

1. Download an area in JOSM that contains Norwegian `highway=*` ways (many already carry `nvdb:id` from Elveg).
2. **More tools → Suggest inclines from NVDB…**
3. The plugin reads the active edit layer and fetches, for that bbox (cached under JOSM's cache directory):
   - NVDB segmented road-link geometry (3D) for inclines
   - NVDB Skiltplate (type **96**) for Farlig sving / Farlig vegkryss codes
   - NVDB Trafikkulykke (type **570**) for accident clustering
4. A **review dialog** lists every proposal in sections (inclines, snow chains, curves confirmed by sign, geometry-only curves, accident clusters). Tick only what you accept. Shortcut: “Accept all high-confidence”.
5. **Apply selected** registers undoable edits:
   - Ways: `incline=*`, `incline:source=nvdb_estimate`, `fixme=*`, `note=*`, match-confidence tags
   - New nodes: `chain_advisory=fit|remove` plus a Norwegian `note=*` (mapper hint only)
   - Sign-confirmed: `hazard=curve` / `hazard=dangerous_junction` with `hazard:source=nvdb_sign`
   - Unsigned sharp curves / accident clusters: advisory only (`safety_advisory=*` + `note=*`) — **never** `hazard=*`
6. Spot-check against imagery, signs, and local knowledge. Existing `incline=*` is never overwritten (shown as discrepancies only). Dubious track/path matches and absurd grades are filtered before they reach the review list; split ways use the whole-way average for `incline:suggested`.
7. Upload manually from JOSM only after that review. Ctrl+Z undoes plugin edits like any other change.

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

Ways with `incline:source=nvdb_estimate` get a validator reminder so unfinished estimates are harder to upload by accident.

## Tests

```bash
./gradlew test
```

- **`core`**: JUnit 5 — gradient, matching, chain heuristics, curvature (straight / gentle / hairpin / merge), sign cross-check, accident clustering, review filtering. No JOSM, no network in the tests themselves.
- **`plugin`**: headless in-memory `DataSet` tests for Command apply/undo; offline NVDB fixture parse; **no-upload** safety grep; **hazard tag-safety** regression (unsigned findings cannot emit `hazard=*`); **steep-road fixtures** under `tests/fixtures/steep_roads/` (real Innlandet ways + NVDB snapshot).

## Safety constraints

- No `UploadAction`, no OSM changeset/write API calls, no OAuth
- Working OSM data comes from the active JOSM layer; remote reads are NVDB only
- `hazard=*` only after NVDB sign confirmation; geometry/accident advisories stay `note=*` / `safety_advisory=*`
- Safety regression tests run on every `./gradlew test`

## Prototype (optional)

The Python CLI under `prototype/` was an earlier standalone experiment. See `prototype/README.md` if you want to run those offline tests for comparison.

## Developer tools

`tools/capture_fixtures.py` drives a running JOSM Remote Control session to load/export steep-road OSM fixtures (never uploads). See `tools/README.md`.

## License

Plugin code is intended for use with JOSM (GPL). Treat redistribution of the built plugin accordingly. The `prototype/` Python code remains separately usable as reference tooling.
