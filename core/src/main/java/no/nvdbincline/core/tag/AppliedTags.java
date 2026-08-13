package no.nvdbincline.core.tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tags that may be written to the OSM data layer when a suggestion is applied.
 *
 * <p>Match quality, raw estimates, and split bookkeeping stay in the review model /
 * dialog only — never as {@code ChangePropertyCommand} keys.
 */
public final class AppliedTags {
    public static final String INCLINE = "incline";
    public static final String HAZARD = "hazard";
    public static final String SOURCE_INCLINE = "source:incline";
    public static final String SOURCE_HAZARD = "source:hazard";
    public static final String SOURCE_CHAIN = "source:chain_advisory";
    public static final String FIXME = "fixme";
    public static final String NOTE = "note";
    public static final String CHAIN_ADVISORY = "chain_advisory";
    public static final String SAFETY_ADVISORY = "safety_advisory";

    public static final String INCLINE_SOURCE_VALUE = InclineTags.SOURCE;
    public static final String HAZARD_SOURCE_VALUE = "nvdb_sign";

    /** Way incline apply set: exactly these four keys. */
    public static final Set<String> WAY_INCLINE_KEYS =
            Set.of(INCLINE, SOURCE_INCLINE, FIXME, NOTE);

    /** Sign-backed hazard node apply set. */
    public static final Set<String> HAZARD_KEYS =
            Set.of(HAZARD, SOURCE_HAZARD, FIXME, NOTE);

    /** Snow-chain advisory node apply set. */
    public static final Set<String> CHAIN_KEYS =
            Set.of(CHAIN_ADVISORY, SOURCE_CHAIN, NOTE);

    /** Geometry/accident advisory (never hazard=*). */
    public static final Set<String> SAFETY_ADVISORY_KEYS =
            Set.of(SAFETY_ADVISORY, NOTE);

    /** Legacy keys that must never be emitted on apply. */
    public static final Set<String> FORBIDDEN_LEGACY_KEYS =
            Set.of(
                    "incline:source",
                    "hazard:source",
                    "incline:match_method",
                    "incline:match_confidence",
                    "incline:match_hausdorff_m",
                    "incline:estimated_avg",
                    "incline:estimated_max_sustained",
                    "incline:suggested",
                    "incline:suggested_segments",
                    "incline:split_recommended",
                    "chain_advisory:source",
                    "chain_advisory:reason",
                    "safety_advisory:source",
                    "safety_advisory:count",
                    "safety_advisory:period");

    private AppliedTags() {}

    public static Map<String, String> incline(String inclineValue) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(INCLINE, inclineValue);
        tags.put(SOURCE_INCLINE, INCLINE_SOURCE_VALUE);
        tags.put(NOTE, InclineTags.NOTE);
        tags.put(FIXME, InclineTags.FIXME);
        return tags;
    }

    public static Map<String, String> hazard(String hazardValue, String note, String fixme) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(HAZARD, hazardValue);
        tags.put(SOURCE_HAZARD, HAZARD_SOURCE_VALUE);
        tags.put(NOTE, note);
        tags.put(FIXME, fixme);
        return tags;
    }

    public static Map<String, String> chain(String advisoryValue, String note) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(CHAIN_ADVISORY, advisoryValue);
        tags.put(SOURCE_CHAIN, INCLINE_SOURCE_VALUE);
        tags.put(NOTE, note);
        return tags;
    }

    public static Map<String, String> safetyAdvisory(String value, String note) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(SAFETY_ADVISORY, value);
        tags.put(NOTE, note);
        return tags;
    }

    /** Keep only keys in {@code allowed}; drop everything else. */
    public static Map<String, String> retain(Map<String, String> tags, Set<String> allowed) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tags == null) {
            return out;
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (allowed.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
