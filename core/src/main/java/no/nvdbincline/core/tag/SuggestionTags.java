package no.nvdbincline.core.tag;

import java.util.LinkedHashMap;
import java.util.Map;
import no.nvdbincline.core.model.WaySuggestion;

/** Build machine-marked tags for a way suggestion. */
public final class SuggestionTags {
    private SuggestionTags() {}

    public static Map<String, String> forWay(WaySuggestion sug) {
        if (sug.segments().isEmpty()) {
            return Map.of();
        }
        var primary = sug.segments().get(0);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("incline:source", InclineTags.SOURCE);
        tags.put("incline:match_confidence", sug.match().confidence().name().toLowerCase());
        tags.put("incline:match_method", sug.match().method());
        tags.put("incline:estimated_avg", String.format("%.1f%%", sug.stats().averagePct()));
        tags.put(
                "incline:estimated_max_sustained",
                String.format("%.1f%%", sug.stats().maxSustainedPct()));
        tags.put("note", InclineTags.NOTE);
        tags.put("fixme", InclineTags.FIXME);
        if (sug.match().hausdorffM() != null) {
            tags.put("incline:match_hausdorff_m", String.format("%.1f", sug.match().hausdorffM()));
        }
        if (sug.split()) {
            tags.put("incline:split_recommended", "yes");
            tags.put("incline:suggested", primary.inclineTag());
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < sug.segments().size(); i++) {
                if (i > 0) {
                    parts.append(';');
                }
                parts.append(sug.segments().get(i).inclineTag());
            }
            tags.put("incline:suggested_segments", parts.toString());
        } else {
            tags.put("incline", primary.inclineTag());
        }
        return tags;
    }
}
