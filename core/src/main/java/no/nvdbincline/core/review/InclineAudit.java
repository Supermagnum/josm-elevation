package no.nvdbincline.core.review;

import java.util.Locale;
import java.util.stream.Collectors;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.tag.AppliedTags;
import no.nvdbincline.core.tag.InclineTags;

/**
 * Review-only audit of a way incline suggestion: former {@code incline:match_*} /
 * {@code incline:estimated_*} / split bookkeeping that must not become OSM tags.
 */
public final class InclineAudit {
    private final String matchMethod;
    private final MatchConfidence matchConfidence;
    private final Double matchHausdorffM;
    private final double estimatedAvgPct;
    private final double estimatedMaxSustainedPct;
    private final String suggestedIncline;
    private final String suggestedSegments;
    private final boolean splitRecommended;

    public InclineAudit(
            String matchMethod,
            MatchConfidence matchConfidence,
            Double matchHausdorffM,
            double estimatedAvgPct,
            double estimatedMaxSustainedPct,
            String suggestedIncline,
            String suggestedSegments,
            boolean splitRecommended) {
        this.matchMethod = matchMethod == null ? "" : matchMethod;
        this.matchConfidence = matchConfidence;
        this.matchHausdorffM = matchHausdorffM;
        this.estimatedAvgPct = estimatedAvgPct;
        this.estimatedMaxSustainedPct = estimatedMaxSustainedPct;
        this.suggestedIncline = suggestedIncline == null ? "" : suggestedIncline;
        this.suggestedSegments = suggestedSegments;
        this.splitRecommended = splitRecommended;
    }

    public static InclineAudit from(WaySuggestion sug) {
        if (sug == null || sug.stats() == null || sug.match() == null) {
            return null;
        }
        String incline =
                sug.tagsToAdd().getOrDefault(
                        AppliedTags.INCLINE,
                        sug.split()
                                ? InclineTags.formatIncline(sug.stats().averagePct())
                                : (sug.segments().isEmpty()
                                        ? ""
                                        : sug.segments().get(0).inclineTag()));
        String segments =
                sug.segments().isEmpty()
                        ? null
                        : sug.segments().stream()
                                .map(s -> s.inclineTag())
                                .collect(Collectors.joining(";"));
        return new InclineAudit(
                sug.match().method(),
                sug.match().confidence(),
                sug.match().hausdorffM(),
                sug.stats().averagePct(),
                sug.stats().maxSustainedPct(),
                incline,
                segments,
                sug.split());
    }

    public String matchMethod() {
        return matchMethod;
    }

    public MatchConfidence matchConfidence() {
        return matchConfidence;
    }

    public Double matchHausdorffM() {
        return matchHausdorffM;
    }

    public double estimatedAvgPct() {
        return estimatedAvgPct;
    }

    public double estimatedMaxSustainedPct() {
        return estimatedMaxSustainedPct;
    }

    public String suggestedIncline() {
        return suggestedIncline;
    }

    public String suggestedSegments() {
        return suggestedSegments;
    }

    public boolean splitRecommended() {
        return splitRecommended;
    }

    /** Compact one-line detail for the summary column. */
    public String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("proposed ")
                .append(suggestedIncline)
                .append(String.format(Locale.ROOT, " (raw avg %.1f%% / max %.1f%%)", estimatedAvgPct, estimatedMaxSustainedPct));
        if (matchHausdorffM != null) {
            sb.append(String.format(Locale.ROOT, "; H=%.1fm", matchHausdorffM));
        }
        sb.append(" via ").append(matchMethod);
        if (splitRecommended && suggestedSegments != null && !suggestedSegments.isBlank()) {
            sb.append("; segments ").append(suggestedSegments);
        }
        return sb.toString();
    }

    /** Tooltip / expandable detail text. */
    public String detailTooltip() {
        StringBuilder sb = new StringBuilder();
        sb.append("confidence=")
                .append(
                        matchConfidence == null
                                ? "?"
                                : matchConfidence.name().toLowerCase(Locale.ROOT));
        sb.append("\nmethod=").append(matchMethod);
        sb.append("\nhausdorff_m=")
                .append(
                        matchHausdorffM == null
                                ? "n/a"
                                : String.format(Locale.ROOT, "%.1f", matchHausdorffM));
        sb.append(String.format(Locale.ROOT, "\nestimated_avg=%.1f%%", estimatedAvgPct));
        sb.append(
                String.format(
                        Locale.ROOT,
                        "\nestimated_max_sustained=%.1f%%",
                        estimatedMaxSustainedPct));
        sb.append("\nsuggested=").append(suggestedIncline);
        sb.append("\nsplit_recommended=").append(splitRecommended ? "yes" : "no");
        sb.append("\nsuggested_segments=")
                .append(suggestedSegments == null ? "" : suggestedSegments);
        return sb.toString();
    }
}
