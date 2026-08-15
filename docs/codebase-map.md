# Codebase map

Oriented around “if I need to change X, where do I go.” Paths are relative to the repo root. Verified against the tree as of plugin **0.3.0** (`AppliedTags`, `InclineAudit`, opt-in auto-split via `WaySplitPlanner` / `SplitWayCommand`).

## Architecture (modules + data flow)

```
                    ┌─────────────────────────────────────┐
                    │  plugin (JOSM adapter)              │
                    │  NvdbInclinePlugin                  │
                    │    → SuggestInclinesAction          │
                    │    → AreaSelectionDialog            │
                    │      (+ auto-split checkbox)        │
                    │    → NvdbInclinePreferences /       │
                    │      NvdbInclinePreferenceSetting   │
                    │    → LayerAdapter / NvdbClient      │
                    │    → ReviewDialog                   │
                    │    → SuggestionApplier              │
                    │      (+ WaySplitPlanner when on)    │
                    │    → NvdbEstimateValidator          │
                    └──────────────┬──────────────────────┘
                                   │ calls pure JVM APIs
                    ┌──────────────▼──────────────────────┐
                    │  core (no JOSM dependency)          │
                    │  SuggestionEngine                   │
                    │    WayMatcher → ElevationProfiles   │
                    │    → GradientCalculator             │
                    │    → SuggestionTags / AppliedTags   │
                    │    → ChainAdvisory                  │
                    │  SafetyAnalyzer (+ CurveDetector,   │
                    │    AccidentClusterer)               │
                    │  ReviewModel + InclineAudit         │
                    └─────────────────────────────────────┘

OSM DataSet (active layer **or** kommune: local Geofabrik PBF clipped by Kartverket polygon)
  → LayerAdapter.extractWays
  → NvdbClient (segmentert + vegobjekter 96/570, disk cache; kommune= for kommune mode)
  → SuggestionEngine.run + SafetyAnalyzer.analyze
  → ReviewModel.fromEngine
  → ReviewDialog (accept/reject; audit columns; relation warning if auto-split)
  → on Apply: SuggestionApplier
       · default: ChangePropertyCommand / AddCommand
       · auto-split on + split-recommended: WaySplitPlanner
         (insert nodes → SplitWayCommand → per-piece incline tags)
       · whole Apply wrapped in SequenceCommand (one Ctrl+Z)
  → JOSM edit layer (undoable; never uploaded by this plugin)
```

**Kommune OSM path decision:** extraction uses pure-JVM `osmosis-osm-binary` + protobuf
(`PbfHighwayExtractor`) — no `osmium-tool` / native deps. Inclusion rule: a highway is
kept if **any node** lies inside the Kartverket multipolygon. Missing/stale local extract
fails loudly (no silent OSM-API bbox fallback).
Gradle modules: `core`, `plugin` (`settings.gradle.kts`). `prototype/` is a separate Python reference CLI, not on the plugin classpath. `tools/` and `tests/fixtures/` are developer/QA assets.

## Responsibility → location

| Responsibility | Where |
|----------------|--------|
| Plugin entry / menu / validator / prefs registration | `plugin/.../NvdbInclinePlugin.java` |
| Menu action orchestration (end-to-end run) | `plugin/.../action/SuggestInclinesAction.java` |
| Opt-in auto-split preference (default off) | `plugin/.../NvdbInclinePreferences.java` + `dialog/NvdbInclinePreferenceSetting.java` |
| Area selection (layer / bbox / kommune) + auto-split checkbox | `plugin/.../dialog/AreaSelectionDialog.java` + `core/.../area/AreaSelection.java` |
| OSM layer → `OsmWayGeom` + bbox | `plugin/.../io/LayerAdapter.java` |
| NVDB HTTP + JSON parse + cache | `plugin/.../io/NvdbClient.java` (Jackson; `java.net.http.HttpClient`; bbox or `kommune=`) |
| Bundled kommune list | `core/.../kommune/KommuneCatalog.java` + resource `kommuner_2024-01-01.json` |
| Kartverket kommune polygons | `core/.../kommune/KommuneBoundaryCatalog.java` + `kommune_boundaries_2026-01-01.json` |
| Local Geofabrik Norway extract | `plugin/.../io/GeofabrikNorwayExtract.java` + `LocalDataPaths.norwayExtractPbf()` |
| PBF→polygon highway clip (pure JVM) | `core/.../osm/PbfHighwayExtractor.java` + `WayPolygonClipper` (any-node-inside rule) |
| Local kommune completion | `core/.../completion/*` + `plugin/.../io/LocalDataPaths.java` |
| Existing OSM tag policy / coverage scan | `core/.../tag/ExistingTagPolicy.java`, `ExistingTagCoverage.java` |
| Pure suggestion pipeline (match → profile → incline tags → chains) | `core/.../SuggestionEngine.java` |
| OSM↔NVDB way matching | `core/.../match/WayMatcher.java` |
| Elevation profile along matched links | `core/.../geo/ElevationProfiles.java` |
| Rolling gradients, split suggestion | `core/.../gradient/GradientCalculator.java` |
| Incline formatting (`10%`, rounding) | `core/.../tag/InclineTags.java` |
| Quality gates + applied incline tag map | `core/.../tag/SuggestionTags.java` |
| Allowlisted apply keys / builders | `core/.../tag/AppliedTags.java` |
| Snow-chain fit/remove heuristics + clustering | `core/.../chain/ChainAdvisory.java` |
| Sharp-curve geometry | `core/.../curve/CurveDetector.java` |
| Accident clustering | `core/.../safety/AccidentClusterer.java` |
| Sign cross-check → hazard / safety_advisory | `core/.../safety/SafetyAnalyzer.java` |
| Review rows + accept filters | `core/.../review/ReviewModel.java` |
| Match/estimate audit for UI (not OSM tags) | `core/.../review/InclineAudit.java` (includes segment list for auto-split) |
| Review Swing UI | `plugin/.../dialog/ReviewDialog.java` |
| Command building / tag application | `plugin/.../command/SuggestionApplier.java` |
| Optional auto-split geometry + `SplitWayCommand` | `plugin/.../command/WaySplitPlanner.java` |
| Validator for unfinished NVDB inclines | `plugin/.../validator/NvdbEstimateValidator.java` |
| Installable jar packaging + `compiled/` copy | `plugin/build.gradle.kts` (`dist` → `copyJarToCompiled` → `compiled/nvdb_incline.jar`) |
| UTM33 / WKT helpers | `core/.../geo/Utm33.java`, `WktParser.java` |
| Domain records | `core/.../model/*` |
| No-upload source grep | `plugin/.../NoUploadSafetyTest.java` |
| Hazard allowlist / sign-only regression | `plugin/.../HazardTagSafetyTest.java` |
| Apply / undo Command tests | `plugin/.../command/SuggestionApplierTest.java` |
| Auto-split + relation/undo tests | `plugin/.../command/AutoSplitSuggestionTest.java` |
| `SplitWayCommand` feasibility spike | `plugin/.../command/SplitWayCommandFeasibilityTest.java` |
| Steep-road offline fixtures | `tests/fixtures/steep_roads/` + `plugin/.../fixtures/SteepRoadFixtureIT.java` |
| Fixture capture via JOSM Remote Control | `tools/capture_fixtures.py` (+ `tools/README.md`) |
| Manual QA OSM sample | `test-files/test.osm`, `test-files/test-out.osm` (latter produced with auto-split **on**) |

## Where responsibilities are split (don’t oversimplify)

- **Matching**: geometry/Hausdorff and `nvdb:id` logic live in `WayMatcher`; confidence enum is `MatchConfidence`; the review dialog does not re-match — it only displays `MatchResult` via `InclineAudit`.
- **Incline tags**: value rounding in `InclineTags`; eligibility + `forWay()` in `SuggestionTags`; hard allowlist / builders in `AppliedTags`; final strip in `SuggestionApplier.sanitizeTags`. Changing what hits OSM requires touching at least the last two. Human/surveyed `incline=*` (other/no `source:incline`) is never overwritten — only a discrepancy note. Prior `source:incline=nvdb_estimate` may appear as an **UPDATE** and can be overwritten on apply.
- **Safety / hazard**: geometry in `CurveDetector`, clustering in `AccidentClusterer`, policy + tag maps in `SafetyAnalyzer`, construction guards on `SafetyFinding` / `ReviewModel.Row`, apply-time strip in `SuggestionApplier`, plus `HazardTagSafetyTest`.
- **Chains**: detection/clustering in `ChainAdvisory`; applied keys built in `ReviewModel.chainTags` via `AppliedTags.chain`; reason text stays in the row summary, not as an OSM tag.
- **Audit vs tags**: former `incline:match_*` / `incline:estimated_*` / split bookkeeping is computed on `WaySuggestion` / `MatchResult` / `GradientStats`, copied into `InclineAudit` (including ordered `segments()` for optional auto-split), rendered by `ReviewDialog` — never passed through `ChangePropertyCommand`.
- **Auto-split**: gradient variance still only *recommends* a split in core (`GradientCalculator` / `WaySuggestion.split()`). Actually changing way structure is opt-in plugin behaviour (`NvdbInclinePreferences`, off by default): `WaySplitPlanner` inserts nodes as needed, runs JOSM `SplitWayCommand` (`SPLIT_ANYWAY` for missing relation members in headless apply), tags each piece, and nests under the Apply `SequenceCommand`. Hazard / curve / chain rows are never auto-split.

There is **no Overpass client** in this plugin. Network I/O is NVDB only (`NvdbClient`). OSM geometry comes from the active JOSM layer, or (kommune mode) a local Geofabrik Norway extract clipped by Kartverket polygons.

## Worked example: adding a new suggestion type (using incline as the template)

Trace of the existing incline feature — copy this pattern for something new:

1. **Extract OSM input** — `LayerAdapter.extractWays(DataSet)` builds `List<OsmWayGeom>`.
2. **Fetch external context** — `SuggestInclinesAction` calls `NvdbClient.fetchSegmentedLinks(...)`.
3. **Core compute** — `SuggestionEngine.run(ways, links, config)`:
   - `WayMatcher.match` → `MatchResult`
   - `ElevationProfiles.build` → profile
   - `GradientCalculator.stats` / `suggestSegments`
   - `SuggestionTags.isInclineEligible` / `SuggestionTags.forWay` → allowlisted `tagsToAdd`
   - `ChainAdvisory.detect` + `cluster` (parallel product type)
4. **Review model** — `ReviewModel.fromEngine(...)` builds `Row`s with `Kind.WAY_TAGS`, `tags` = applied map only, `inclineAudit` = `InclineAudit.from(sug)`.
5. **UI** — `ReviewDialog.show` lists rows; user toggles `accepted`. Split column shows `Split suggested` / `Auto-split` / `Auto-split (relation)` depending on prefs and membership.
6. **Apply** — `SuggestionApplier.applyAccepted(..., autoSplit)` → `sanitizeTags` → normally `ChangePropertyCommand` on the way. If auto-split is on and the row is split-recommended with ≥2 audit segments, `WaySplitPlanner` runs instead (nodes + `SplitWayCommand` + per-piece tags). Surveyed (non-plugin) `incline=*` never reaches apply as an overwrite.
7. **Safety net** — `NvdbEstimateValidator` reminds about `source:incline=nvdb_estimate`; tests assert allowlisted keys (`SuggestionApplierTest`, `InclineAuditReviewModelTest`) and auto-split behaviour (`AutoSplitSuggestionTest`, `SplitWayCommandFeasibilityTest`).

For a new type you typically add: a core analyzer (or extend `SuggestionEngine`), a `ReviewModel.Kind` / `Section`, applied keys in `AppliedTags`, UI column/section labels in `ReviewDialog`, apply branch in `SuggestionApplier` if not already covered by way/node kinds, and regression tests that assert both **present in the review model** and **absent from forbidden OSM keys**.
