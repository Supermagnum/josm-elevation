package no.nvdbincline.core.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.GradientStats;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import no.nvdbincline.core.model.SegmentSuggestion;
import no.nvdbincline.core.model.WaySuggestion;
import org.junit.jupiter.api.Test;

class SuggestionTagsTest {

    private static OsmWayGeom way(String highway, double lengthM) {
        return new OsmWayGeom(
                1,
                new Polyline(List.of(new Coord(0, 0), new Coord(lengthM, 0))),
                highway,
                "T",
                null,
                null);
    }

    private static MatchResult match(
            OsmWayGeom w, MatchConfidence conf, Double hausdorff) {
        return new MatchResult(w, List.of(), conf, "test", hausdorff, "");
    }

    private static GradientStats stats(double avg, double maxSust) {
        return new GradientStats(avg, maxSust, avg, maxSust, Math.abs(maxSust - avg), 100);
    }

    @Test
    void rejectsAbsurdTrackGrade() {
        MatchResult m = match(way("track", 120), MatchConfidence.HIGH, 5.0);
        assertFalse(SuggestionTags.isInclineEligible(m, stats(44.0, 50.0)));
    }

    @Test
    void rejectsLowConfidenceTrack() {
        MatchResult m = match(way("track", 120), MatchConfidence.MEDIUM, 5.0);
        assertFalse(SuggestionTags.isInclineEligible(m, stats(8.0, 10.0)));
    }

    @Test
    void acceptsSensibleSecondary() {
        MatchResult m = match(way("secondary", 200), MatchConfidence.HIGH, 8.0);
        assertTrue(SuggestionTags.isInclineEligible(m, stats(9.6, 12.0)));
    }

    @Test
    void splitSuggestedUsesWholeWayAverage() {
        MatchResult m = match(way("tertiary", 200), MatchConfidence.HIGH, 4.0);
        GradientStats st = stats(5.7, 11.0);
        WaySuggestion sug =
                new WaySuggestion(
                        m,
                        List.of(),
                        st,
                        List.of(
                                new SegmentSuggestion(
                                        0, 50, 2.0, 2.0, "2%", new Coord(0, 0), new Coord(50, 0)),
                                new SegmentSuggestion(
                                        50,
                                        200,
                                        8.0,
                                        8.0,
                                        "8%",
                                        new Coord(50, 0),
                                        new Coord(200, 0))),
                        true,
                        null,
                        Map.of());
        Map<String, String> tags = SuggestionTags.forWay(sug);
        assertEquals(InclineTags.formatIncline(5.7), tags.get("incline:suggested"));
        assertEquals("2%;8%", tags.get("incline:suggested_segments"));
        assertTrue(tags.get("incline:estimated_avg").contains("."));
        assertFalse(tags.get("incline:estimated_avg").contains(","));
    }

    @Test
    void estimatedAvgUsesRootLocale() {
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("nb-NO"));
            MatchResult m = match(way("primary", 200), MatchConfidence.HIGH, 3.0);
            WaySuggestion sug =
                    new WaySuggestion(
                            m,
                            List.of(),
                            stats(9.6, 12.0),
                            List.of(
                                    new SegmentSuggestion(
                                            0,
                                            200,
                                            9.6,
                                            12.0,
                                            "10%",
                                            new Coord(0, 0),
                                            new Coord(200, 0))),
                            false,
                            null,
                            Map.of());
            String avg = SuggestionTags.forWay(sug).get("incline:estimated_avg");
            assertEquals("9.6%", avg);
        } finally {
            Locale.setDefault(prev);
        }
    }
}
