package no.nvdbincline.core.tag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.model.GradientStats;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.WaySuggestion;

/**
 * Build machine-marked tags for a way suggestion, and apply quality gates so
 * dubious track/service matches do not produce wild incline=* values.
 */
public final class SuggestionTags {
    private static final Set<String> STRICT_HIGHWAYS =
            Set.of("track", "path", "bridleway", "cycleway", "footway");

    private SuggestionTags() {}

    /**
     * @return false if this match should not become an incline suggestion
     */
    public static boolean isInclineEligible(MatchResult match, GradientStats stats) {
        if (match == null || stats == null) {
            return false;
        }
        String hw = match.way().highway() == null ? "" : match.way().highway();
        double absAvg = Math.abs(stats.averagePct());
        double absMax = Math.abs(stats.maxSustainedPct());
        Double hd = match.hausdorffM();

        // Absolute absurdities — almost always bad Z conflation.
        if (absAvg > 22.0 || absMax > 30.0) {
            return false;
        }
        if (STRICT_HIGHWAYS.contains(hw)) {
            if (match.confidence() != MatchConfidence.HIGH) {
                return false;
            }
            if (absAvg > 15.0 || absMax > 20.0) {
                return false;
            }
            if (hd != null && hd > 12.0) {
                return false;
            }
            if (match.way().line().lengthM() < 40.0) {
                return false;
            }
        }
        if ("service".equals(hw)) {
            if (match.confidence() == MatchConfidence.LOW) {
                return false;
            }
            if (absMax > 25.0 && (hd != null && hd > 15.0)) {
                return false;
            }
        }
        if (match.confidence() == MatchConfidence.LOW && absAvg < 3.0) {
            return false; // noise, not worth a review row
        }
        return true;
    }

    public static Map<String, String> forWay(WaySuggestion sug) {
        if (sug.segments().isEmpty()) {
            return Map.of();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("incline:source", InclineTags.SOURCE);
        tags.put("incline:match_confidence", sug.match().confidence().name().toLowerCase(Locale.ROOT));
        tags.put("incline:match_method", sug.match().method());
        tags.put(
                "incline:estimated_avg",
                String.format(Locale.ROOT, "%.1f%%", sug.stats().averagePct()));
        tags.put(
                "incline:estimated_max_sustained",
                String.format(Locale.ROOT, "%.1f%%", sug.stats().maxSustainedPct()));
        tags.put("note", InclineTags.NOTE);
        tags.put("fixme", InclineTags.FIXME);
        if (sug.match().hausdorffM() != null) {
            tags.put(
                    "incline:match_hausdorff_m",
                    String.format(Locale.ROOT, "%.1f", sug.match().hausdorffM()));
        }
        if (sug.split()) {
            tags.put("incline:split_recommended", "yes");
            // Headline uses whole-way average (first segment is often a flat overhang).
            tags.put("incline:suggested", InclineTags.formatIncline(sug.stats().averagePct()));
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < sug.segments().size(); i++) {
                if (i > 0) {
                    parts.append(';');
                }
                parts.append(sug.segments().get(i).inclineTag());
            }
            tags.put("incline:suggested_segments", parts.toString());
        } else {
            tags.put("incline", sug.segments().get(0).inclineTag());
        }
        return tags;
    }
}
