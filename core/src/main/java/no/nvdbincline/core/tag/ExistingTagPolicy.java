package no.nvdbincline.core.tag;

import java.util.Locale;
import java.util.Optional;
import no.nvdbincline.core.model.OsmWayGeom;

/**
 * Classifies existing OSM incline tags and decides fresh / update / discrepancy
 * handling. Human-sourced inclines are never proposed for overwrite.
 */
public final class ExistingTagPolicy {
    /** Minimum absolute percentage-point difference to treat as a meaningful change. */
    public static final int MEANINGFUL_DELTA_PP = 1;

    public enum InclineOrigin {
        NONE,
        /** {@code incline=*} with {@code source:incline=nvdb_estimate}. */
        PLUGIN_NVDB_ESTIMATE,
        /** {@code incline=*} with other/no source — treat as surveyed/authoritative. */
        OTHER
    }

    public enum InclineDisposition {
        /** No existing incline — normal suggestion. */
        FRESH,
        /** Prior plugin estimate; propose a new value if it differs meaningfully. */
        UPDATE,
        /** Human/other incline — informational discrepancy only (never apply). */
        DISCREPANCY_NOTE,
        /** Existing plugin estimate matches proposed — no review row. */
        UNCHANGED
    }

    private ExistingTagPolicy() {}

    public static InclineOrigin classifyIncline(OsmWayGeom way) {
        return classifyIncline(
                way.existingIncline().orElse(null), way.existingSourceIncline().orElse(null));
    }

    public static InclineOrigin classifyIncline(String incline, String sourceIncline) {
        if (incline == null || incline.isBlank()) {
            return InclineOrigin.NONE;
        }
        if (AppliedTags.INCLINE_SOURCE_VALUE.equals(sourceIncline)) {
            return InclineOrigin.PLUGIN_NVDB_ESTIMATE;
        }
        return InclineOrigin.OTHER;
    }

    public static InclineDisposition decideIncline(
            InclineOrigin origin, String existingIncline, String proposedIncline) {
        return switch (origin) {
            case NONE -> InclineDisposition.FRESH;
            case PLUGIN_NVDB_ESTIMATE -> {
                if (meaningfulInclineDiff(existingIncline, proposedIncline)) {
                    yield InclineDisposition.UPDATE;
                }
                yield InclineDisposition.UNCHANGED;
            }
            case OTHER -> {
                if (meaningfulInclineDiff(existingIncline, proposedIncline)) {
                    yield InclineDisposition.DISCREPANCY_NOTE;
                }
                yield InclineDisposition.UNCHANGED;
            }
        };
    }

    public static boolean meaningfulInclineDiff(String existing, String proposed) {
        Optional<Integer> a = parseInclinePercent(existing);
        Optional<Integer> b = parseInclinePercent(proposed);
        if (a.isEmpty() || b.isEmpty()) {
            if (existing == null || proposed == null) {
                return existing != null || proposed != null;
            }
            return !normalize(existing).equals(normalize(proposed));
        }
        return Math.abs(a.get() - b.get()) >= MEANINGFUL_DELTA_PP;
    }

    public static Optional<Integer> parseInclinePercent(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("%", "").trim();
        try {
            return Optional.of(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static InclineOrigin classifyHazard(String hazard, String sourceHazard) {
        if (hazard == null || hazard.isBlank()) {
            return InclineOrigin.NONE;
        }
        if (AppliedTags.HAZARD_SOURCE_VALUE.equals(sourceHazard)) {
            return InclineOrigin.PLUGIN_NVDB_ESTIMATE;
        }
        return InclineOrigin.OTHER;
    }

    public static InclineOrigin classifyHazard(OsmWayGeom way) {
        return classifyHazard(
                way.existingHazard().orElse(null), way.existingSourceHazard().orElse(null));
    }

    /**
     * Same three-way disposition as incline, comparing hazard values as strings
     * (curve vs dangerous_junction, etc.).
     */
    public static InclineDisposition decideHazard(
            InclineOrigin origin, String existingHazard, String proposedHazard) {
        return switch (origin) {
            case NONE -> InclineDisposition.FRESH;
            case PLUGIN_NVDB_ESTIMATE -> {
                if (meaningfulHazardDiff(existingHazard, proposedHazard)) {
                    yield InclineDisposition.UPDATE;
                }
                yield InclineDisposition.UNCHANGED;
            }
            case OTHER -> {
                if (meaningfulHazardDiff(existingHazard, proposedHazard)) {
                    yield InclineDisposition.DISCREPANCY_NOTE;
                }
                yield InclineDisposition.UNCHANGED;
            }
        };
    }

    public static boolean meaningfulHazardDiff(String existing, String proposed) {
        if (existing == null || existing.isBlank()) {
            return proposed != null && !proposed.isBlank();
        }
        if (proposed == null || proposed.isBlank()) {
            return true;
        }
        return !normalize(existing).equals(normalize(proposed));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
