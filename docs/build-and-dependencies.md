# Build and dependencies

Verified against this repo’s Gradle files and by running the commands below on the development machine (JDK 21 installed; project emits **Java 17** bytecode).

## Requirements

| Item | Actual setting | Where configured |
|------|----------------|------------------|
| Bytecode / language level | **Java 17** (`sourceCompatibility` / `targetCompatibility` / `options.release = 17`) | root `build.gradle.kts` |
| Plugin manifest min Java | 17 | `plugin/build.gradle.kts` → `josm.manifest.minJavaVersion` |
| Running JDK | 17+ (21 works; used in CI/dev here) | local toolchain |
| Gradle | **Wrapper 8.10.2** — always use `./gradlew`, not a random system Gradle | `gradle/wrapper/gradle-wrapper.properties` |
| JOSM compile version | **19613** | `plugin/build.gradle.kts` → `josm.josmCompileVersion` |
| JOSM minimum (manifest) | **19067** | `plugin/build.gradle.kts` → `josm.manifest.minJosmVersion` |
| JOSM Gradle plugin | `org.openstreetmap.josm` **0.8.2** | `plugin/build.gradle.kts` |

Modules: `core`, `plugin` (`settings.gradle.kts`). Group/version: `no.nvdbincline` / `0.3.0`.

### Dependencies (Gradle-managed)

- **core**: `osmosis-osm-binary` + `protobuf-java` (PBF schema only; pure JVM). JUnit 5 for tests.
- **plugin**: `implementation(project(":core"))`, `packIntoJar(project(":core"))`,
  `packIntoJar` for Jackson, `osmosis-osm-binary`, and `protobuf-java`. JUnit for tests.
  JOSM itself comes from the JOSM Gradle plugin.

`packIntoJar` matters: JOSM loads the plugin jar in isolation. `:plugin:jar` / `:plugin:dist` depend on `:core:jar` so a clean rebuild does not pack a missing/stale core artifact. `:plugin:dist` (and `:plugin:jar` / `:plugin:build`) also run `copyJarToCompiled`, which writes the same installable jar to repo-root **`compiled/nvdb_incline.jar`**.

### Non-Gradle / system deps

| Use | Dependency | Notes |
|-----|------------|--------|
| Building/running the plugin | JDK 17+ only | **No** osmium / native PBF tools |
| `tools/capture_fixtures.py` | Python 3.11+, `requests` | Optional; `pip install requests` (pytest for `tools/tests`) |
| `tools/refresh_kommune_list.py` | `requests`, `openpyxl` | Bundled kommune names |
| `tools/refresh_kommune_boundaries.py` | `requests` | Bundled Kartverket polygons |
| `prototype/` Python CLI | Separate; see `prototype/README.md` | Not required for the JOSM plugin |

Runtime: NVDB needs network when using the live plugin. **Kommune mode** also needs a
one-time user-triggered Geofabrik Norway `.osm.pbf` download (~1.3 GB) into the JOSM
prefs `nvdb_incline/` directory — never auto-downloaded on launch.

## Commands (verified)

From the repo root:

```bash
# Fresh-ish build + tests
./gradlew build

# Tests only
./gradlew test
# equivalent: make test

# Installable plugin jar (preferred artifact)
./gradlew :plugin:dist
# outputs:
#   plugin/build/dist/nvdb_incline.jar
#   compiled/nvdb_incline.jar   (same jar, copied to repo-root compiled/)

# Also produced:
#   plugin/build/libs/nvdb_incline-<version>.jar
#   (dist packaging is what you should copy into JOSM; compiled/ is the convenience path)

# Clean JOSM with this plugin only (temporary home under plugin/build/.josm/)
./gradlew :plugin:runJosm

# Same with JDWP on port 1731
./gradlew :plugin:debugJosm
```

These were run successfully in-repo: `./gradlew test`, `./gradlew :plugin:dist`, `./gradlew help --task :plugin:debugJosm`, `./gradlew :plugin:tasks --group=josm`.

`runJosm` / `debugJosm` launch a GUI JOSM; they are not asserted headless in CI.

## Install the jar into a normal JOSM

1. `./gradlew :plugin:dist`
2. Copy `compiled/nvdb_incline.jar` (or `plugin/build/dist/nvdb_incline.jar`) to the plugins directory (create it if missing):

| OS | Typical plugins directory |
|----|---------------------------|
| Linux (XDG config — common) | `~/.config/JOSM/plugins/` |
| Linux (XDG data) | `~/.local/share/JOSM/plugins/` |
| Linux (legacy) | `~/.josm/plugins/` |
| macOS | `~/Library/JOSM/plugins/` |
| Windows | `%APPDATA%\JOSM\plugins\` |

Flatpak/Snap/portable builds may differ — check **Help → Show Status Report** in JOSM for the plugins path. If both `~/.config/JOSM/plugins/` and `~/.local/share/JOSM/plugins/` exist, copy into the one Status Report lists (this machine uses `~/.config/JOSM/plugins/`).

3. **Fully restart JOSM** after replacing the jar.
4. Enable **nvdb_incline** under Edit → Preferences → Plugins if needed.
5. Menu: **More tools → Suggest inclines from NVDB…**

## Troubleshooting first-run failures

| Problem | Fix |
|---------|-----|
| Wrong Java / “invalid target release 17” | Install JDK 17+; `java -version`; ensure `JAVA_HOME` points at it |
| Gradle download blocked | Need network once for the wrapper distribution + Maven/JOSM deps |
| JOSM version too old | Manifest requires ≥ **19067**; compile/runJosm uses **19613** |
| Plugin loads but `NoClassDefFoundError` for `no.nvdbincline.core…` | Rebuild `:plugin:dist` so `core` is packed into the jar; restart JOSM |
| Changes don’t appear after copy | Restart JOSM completely; confirm you overwrote the jar in the directory Status Report lists |
| Tests fail opening a display | Plugin tests set `java.awt.headless=true` in `plugin/build.gradle.kts`; don’t unset that for CI |
| `clean` then `:plugin:jar` fails expanding core zip | Ensure `:core:jar` runs first (already `dependsOn(":core:jar")` on `plugin` jar task); use `./gradlew :plugin:dist` |

## See also

- [`codebase-map.md`](codebase-map.md) — where logic lives
- [`debugging.md`](debugging.md) — runJosm, debugger, fixtures, failure modes
- Root [`README.md`](../README.md) — tagging reference and workflow overview
