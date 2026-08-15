package no.nvdbincline.core.model;

import java.util.List;
import java.util.Map;
import no.nvdbincline.core.tag.ExistingTagPolicy;

/** Proposed tags for one OSM way (or a discrepancy report if skipReason is set). */
public final class WaySuggestion {
    private final MatchResult match;
    private final List<ElevationSample> profile;
    private final GradientStats stats;
    private final List<SegmentSuggestion> segments;
    private final boolean split;
    private final String skipReason;
    private final Map<String, String> tagsToAdd;
    private final ExistingTagPolicy.InclineDisposition inclineDisposition;

    public WaySuggestion(
            MatchResult match,
            List<ElevationSample> profile,
            GradientStats stats,
            List<SegmentSuggestion> segments,
            boolean split,
            String skipReason,
            Map<String, String> tagsToAdd) {
        this(
                match,
                profile,
                stats,
                segments,
                split,
                skipReason,
                tagsToAdd,
                skipReason == null || skipReason.isBlank()
                        ? ExistingTagPolicy.InclineDisposition.FRESH
                        : ExistingTagPolicy.InclineDisposition.DISCREPANCY_NOTE);
    }

    public WaySuggestion(
            MatchResult match,
            List<ElevationSample> profile,
            GradientStats stats,
            List<SegmentSuggestion> segments,
            boolean split,
            String skipReason,
            Map<String, String> tagsToAdd,
            ExistingTagPolicy.InclineDisposition inclineDisposition) {
        this.match = match;
        this.profile = List.copyOf(profile);
        this.stats = stats;
        this.segments = List.copyOf(segments);
        this.split = split;
        this.skipReason = skipReason;
        this.tagsToAdd = Map.copyOf(tagsToAdd);
        this.inclineDisposition =
                inclineDisposition == null
                        ? ExistingTagPolicy.InclineDisposition.FRESH
                        : inclineDisposition;
    }

    public MatchResult match() {
        return match;
    }

    public List<ElevationSample> profile() {
        return profile;
    }

    public GradientStats stats() {
        return stats;
    }

    public List<SegmentSuggestion> segments() {
        return segments;
    }

    public boolean split() {
        return split;
    }

    public String skipReason() {
        return skipReason;
    }

    public Map<String, String> tagsToAdd() {
        return tagsToAdd;
    }

    public ExistingTagPolicy.InclineDisposition inclineDisposition() {
        return inclineDisposition;
    }

    public boolean isApplicable() {
        return skipReason == null || skipReason.isBlank();
    }

    public boolean isUpdate() {
        return inclineDisposition == ExistingTagPolicy.InclineDisposition.UPDATE;
    }
}
