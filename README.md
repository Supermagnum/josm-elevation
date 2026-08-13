# josm-elevation (`nvdb_incline`)

JOSM plugin that helps Norwegian OSM mappers suggest `incline=*` tags and snow-chain advisory points, using elevation from Statens Vegvesen's NVDB API.

**This plugin never uploads to OpenStreetMap.** Accepted suggestions become ordinary undoable JOSM edits (`ChangePropertyCommand` / `AddCommand`). You only upload if you later use JOSM's own Upload action after review.

## Modules

| Module | Role |
|--------|------|
| `core` | Pure JVM logic: gradient, OSM/NVDB matching, snow-chain heuristics, review accept/reject model. No JOSM dependency. |
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

1. `./gradlew :plugin:dist` (or `:plugin:jar`)
2. Copy `plugin/build/dist/nvdb_incline.jar` into:
   - Linux: `~/.josm/plugins/`
   - macOS: `~/Library/JOSM/plugins/`
   - Windows: `%APPDATA%\JOSM\plugins\`
3. Restart JOSM and enable **nvdb_incline** under Edit → Preferences → Plugins if needed.

## Review-before-apply workflow

1. Download an area in JOSM that contains Norwegian `highway=*` ways (many already carry `nvdb:id` from Elveg).
2. **More tools → Suggest inclines from NVDB…**
3. The plugin reads the active edit layer and fetches NVDB segmented road-link geometry (3D) for that bbox (cached under JOSM's cache directory).
4. A **review dialog** lists every proposal (way tags, chain nodes, discrepancies). Tick only what you accept. Shortcut: “Accept all high-confidence”.
5. **Apply selected** registers undoable edits:
   - Ways: `incline=*`, `incline:source=nvdb_estimate`, `fixme=*`, `note=*`, match-confidence tags
   - New nodes: `chain_advisory=fit|remove` plus a Norwegian `note=*` (mapper hint only; not established Norwegian tagging)
6. Spot-check against imagery, signs, and local knowledge. Existing `incline=*` is never overwritten (shown as discrepancies only).
7. Upload manually from JOSM only after that review. Ctrl+Z undoes plugin edits like any other change.

## Validator

Ways with `incline:source=nvdb_estimate` get a validator reminder so unfinished estimates are harder to upload by accident.

## Tests

```bash
./gradlew test
```

- **`core`**: JUnit 5 — gradient (flat / slope / spike / split), matching (including bad matches), chain heuristics, review filtering. No JOSM, no network in the tests themselves.
- **`plugin`**: headless in-memory `DataSet` tests for Command apply/undo; offline NVDB fixture parse; **safety grep** that fails if `UploadAction`, OSM write URLs, changeset create, or OAuth token handling appear in plugin sources.

## Safety constraints

- No `UploadAction`, no OSM changeset/write API calls, no OAuth
- Working OSM data comes from the active JOSM layer; remote reads are NVDB (and optional Overpass later)
- Safety regression test runs on every `./gradlew test`

## Prototype (optional)

The Python CLI under `prototype/` was an earlier standalone experiment. See `prototype/README.md` if you want to run those offline tests for comparison.

## License

Plugin code is intended for use with JOSM (GPL). Treat redistribution of the built plugin accordingly. The `prototype/` Python code remains separately usable as reference tooling.
