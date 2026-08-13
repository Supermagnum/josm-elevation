package no.nvdbincline.core.tag;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.model.GradientStats;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.WaySuggestion;

/**
 * Build <em>applied</em> OSM tags for a way suggestion, and apply quality gates so
 * dubious track/service matches do not produce wild incline=* values.
 *
 * <p>Match confidence, raw estimates, Hausdorff distance, and split segment lists
 * stay on {@link WaySuggestion} for the review dialog — they are never written as
 * OSM tags.
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
            return false;
        }
        return true;
    }

    /**
     * Tags to apply to the OSM way: {@code incline}, {@code source:incline},
     * {@code fixme}, {@code note} only.
     *
     * <p>When a split is recommended, {@code incline} is still the whole-way
     * average; the segment breakdown is review-UI data on {@link WaySuggestion}.
     */
    public static Map<String, String> forWay(WaySuggestion sug) {
        if (sug.segments().isEmpty()) {
            return Map.of();
        }
        String incline =
                sug.split()
                        ? InclineTags.formatIncline(sug.stats().averagePct())
                        : sug.segments().get(0).inclineTag();
        return AppliedTags.incline(incline);
    }

    /** Human-readable match/estimate line for the review dialog (not an OSM tag). */
    public static String reviewDetail(WaySuggestion sug) {
        if (sug == null || sug.stats() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format(
                        Locale.ROOT,
                        "avg %.1f%%, max sustained %.1f%%, %s via %s",
                        sug.stats().averagePct(),
                        sug.stats().maxSustainedPct(),
                        sug.match().confidence().name().toLowerCase(Locale.ROOT),
                        sug.match().method()));
        if (sug.match().hausdorffM() != null) {
            sb.append(
                    String.format(
                            Locale.ROOT, ", hausdorff %.1fm", sug.match().hausdorffM()));
        }
        if (sug.split() && !sug.segments().isEmpty()) {
            sb.append("; segments ");
            for (int i = 0; i < sug.segments().size(); i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(sug.segments().get(i).inclineTag());
            }
        }
        return sb.toString();
    }
}
