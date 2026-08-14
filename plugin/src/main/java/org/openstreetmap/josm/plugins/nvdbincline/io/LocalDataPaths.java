package org.openstreetmap.josm.plugins.nvdbincline.io;

import java.nio.file.Path;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Paths for local-only plugin data (completion tracking, Geofabrik Norway extract).
 *
 * <p>Uses the JOSM preferences directory (not OSM / not uploaded). Falls back to
 * {@code java.io.tmpdir} when JOSM dirs are unavailable (unit tests).
 */
public final class LocalDataPaths {
    private LocalDataPaths() {}

    public static Path completionFile() {
        return dataDir().resolve("kommune_completion.json");
    }

    /** Local Geofabrik Norway PBF used by kommune mode (never auto-downloaded). */
    public static Path norwayExtractPbf() {
        return dataDir().resolve("norway-latest.osm.pbf");
    }

    public static Path norwayExtractMeta() {
        return dataDir().resolve("norway-extract-meta.json");
    }

    public static Path dataDir() {
        try {
            String pref = Config.getDirs().getPreferencesDirectory(true).getAbsolutePath();
            return Path.of(pref, "nvdb_incline");
        } catch (Exception e) {
            return Path.of(System.getProperty("java.io.tmpdir"), "nvdb_incline_data");
        }
    }
}
