# Debugging guide

Practical notes for developing `nvdb_incline`. Commands below were checked against the Gradle JOSM plugin tasks in this repo (`plugin/build.gradle.kts`, Gradle wrapper 8.10.2).

## Run a clean JOSM with this plugin

```bash
./gradlew :plugin:runJosm
```

This starts an independent JOSM **v19613** (`josmCompileVersion`) with temporary prefs/cache/userdata under `plugin/build/.josm/` and the freshly built plugin enabled. It does **not** use your normal `~/.local/share/JOSM` install.

Related:

```bash
./gradlew :plugin:cleanJosm          # wipe the temporary JOSM home used by runJosm/debugJosm
./gradlew :plugin:listJosmVersions   # show current 'latest' / 'tested' JOSM versions from the plugin
./gradlew :plugin:localDist          # local update-site under plugin/build/localDist/
```

Manual install into your everyday JOSM is documented in [`build-and-dependencies.md`](build-and-dependencies.md). After `./gradlew :plugin:dist`, the installable jar is at **`compiled/nvdb_incline.jar`** (and also `plugin/build/dist/nvdb_incline.jar`).

## Attach a debugger

Configured debug port: **1731** (`josm { debugPort = 1731 }` in `plugin/build.gradle.kts`).

```bash
./gradlew :plugin:debugJosm
```

Task description: same as `runJosm`, but with JDWP active on port **1731**.

In IntelliJ / Cursor / Eclipse: create a **Remote JVM Debug** configuration pointing at `localhost:1731`, set breakpoints in `core` or `plugin` sources, start `debugJosm`, then attach.

Note: Gradle’s generic `--debug-jvm` flag (port 5005) is a separate Gradle option on the task; for this project prefer the JOSM plugin’s dedicated `debugJosm` / port **1731** setup above.

## Logging (what actually exists)

There is **no** dedicated plugin log file or custom log-level preference yet.

What exists today:

- `org.openstreetmap.josm.tools.Logging` in `SuggestInclinesAction`:
  - `Logging.warn(...)` if NVDB Skiltplate (96) or Trafikkulykke (570) fetch fails (inclines still run)
  - `Logging.error(ex)` on fatal failures shown in the error dialog
- JOSM’s own View → Show status report / console logging applies to those calls
- Offline fixture dump prints to **stdout** via `SteepRoadFixtureDumpTest` (`System.out.println`) when that test is run

**Gap:** there is no plugin-specific verbose flag (e.g. no `nvdb_incline.debug=true`) and no sidecar audit log. Match/estimate detail is meant to be visible in the **review dialog** (`InclineAudit` columns + hover tooltips), not in a file.

## Inspecting review-model / matching data without the Swing dialog

Options that exist in-tree:

1. **Unit / fixture tests (preferred for matching/gradient)**  
   - `./gradlew :core:test` — pure JVM  
   - `./gradlew :plugin:test` — includes `SteepRoadFixtureIT` (offline OSM+NVDB under `tests/fixtures/steep_roads/`)  
   - Dump-style stdout:  
     ```bash
     ./gradlew :plugin:test --tests org.openstreetmap.josm.plugins.nvdbincline.fixtures.SteepRoadFixtureDumpTest
     ```
     Prints matches, stats, applied tags, chain points, curves, and review rows for the recorded steep roads.

2. **In code while debugging** — after `SuggestionEngine.run` / `ReviewModel.fromEngine`, inspect:
   - `WaySuggestion.match()`, `.stats()`, `.segments()`, `.split()`, `.tagsToAdd()`
   - `ReviewModel.Row.inclineAudit` (`matchMethod`, `matchHausdorffM`, estimates, segments, `splitRecommended`)
   - `SuggestionApplier.sanitizeTags(row)` for the exact map that would hit OSM

3. **UI** — run the action in `runJosm` / installed JOSM; hover rows for the full audit tooltip.

There is **no** menu item that dumps the review model to disk.

## Fixture capture (JOSM Remote Control)

Still present: `tools/capture_fixtures.py`. See `tools/README.md`.

Prerequisites: JOSM running with Remote Control enabled (port **8111**), JOSM r19425+ for `/export`.

```bash
python tools/capture_fixtures.py \
  --ways 764390363,757907237,330233844 \
  --bbox 10.05,61.50,10.20,61.58 \
  --host localhost --port 8111
```

Script tests (no live JOSM):

```bash
pip install requests pytest
python -m pytest tools/tests -q
```

Then refresh NVDB JSON for the same bbox into `tests/fixtures/steep_roads/nvdb/` as described in that fixtures README.

Manual end-to-end sample (no Remote Control required): open `test-files/test.osm` in JOSM and run **More tools → Suggest inclines from NVDB…**; compare with `test-files/test-out.osm`.

## Common failure modes

| Symptom | Where to look |
|---------|----------------|
| “Download some OSM road data…” / no highways | Active edit layer empty or no `highway=*`; `LayerAdapter.extractWays` |
| NVDB network errors / empty links | `NvdbClient` (`https://nvdbapiles.atlas.vegvesen.no`); cache under JOSM cache dir `…/nvdb_incline`; User-Agent / `X-Client` headers; bbox → UTM kartutsnitt |
| Signs/accidents missing but inclines work | Non-fatal warn logs in `SuggestInclinesAction`; URL path must be `/vegobjekter/api/v4/vegobjekter/{typeId}` |
| Zero suggestions | Matching failed (`WayMatcher`); no Z on NVDB geometry; quality gate `SuggestionTags.isInclineEligible` filtered everything; or review model empty |
| Only low-confidence / high Hausdorff | Inspect `InclineAudit` in dialog or fixture dump; matching method/notes on `MatchResult` |
| `NoClassDefFoundError` for `core` classes | Plugin jar missing packed core — rebuild with `./gradlew :plugin:dist` (task depends on `:core:jar`, then `copyJarToCompiled`); fully restart JOSM after copying `compiled/nvdb_incline.jar` |
| Stale behavior after rebuild | Replaced jar while JOSM still running; restart fully (`canLoadAtRuntime` is true but packing/classloader quirks still happen) |
| Wrong decimal commas in tags | Applied incline values are integer `%` via `InclineTags`; audit decimals use `Locale.ROOT` in `InclineAudit` / dialog |
| Bookkeeping keys reappear on ways | Must not — `AppliedTags.FORBIDDEN_LEGACY_KEYS` + `SuggestionApplier.sanitizeTags`; add a test if you change apply path |
| `hazard=*` without a sign | Blocked in `SafetyFinding` / `ReviewModel.Row` constructors and `sanitizeTags`; see `HazardTagSafetyTest` |
| Upload somehow | Must not — `NoUploadSafetyTest` greps plugin sources for `UploadAction` |
| Overpass timeouts | **Not applicable** — this plugin does not call Overpass |

## Quick “is my install the new code?” check

After copying `compiled/nvdb_incline.jar` (or `plugin/build/dist/nvdb_incline.jar`) into the plugins directory and restarting:

- Applied tags should use `source:incline` / `source:hazard` (not `incline:source` / `hazard:source`)
- Review dialog should show Method / H(m) / Proposed / Raw avg/max / Split columns
- Applied ways should **not** carry `incline:match_*` or `incline:estimated_*`
