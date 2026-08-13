package no.nvdbincline.core.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.GradientStats;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import no.nvdbincline.core.model.SegmentSuggestion;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.tag.AppliedTags;
import org.junit.jupiter.api.Test;

/**
 * Proves former incline:match_* / incline:estimated_* / split bookkeeping survives
 * in {@link InclineAudit} on the review model, while applied tags stay allowlisted.
 */
class InclineAuditReviewModelTest {

    private static WaySuggestion sug(
            long id,
            MatchConfidence conf,
            String method,
            Double hausdorff,
            double avg,
            double maxSust,
            boolean split) {
        var way =
                new OsmWayGeom(
                        id,
                        new Polyline(List.of(new Coord(0, 0), new Coord(120, 0))),
                        "secondary",
                        "W",
                        null,
                        null);
        var match = new MatchResult(way, List.of(), conf, method, hausdorff, "");
        var stats = new GradientStats(avg, maxSust, avg, maxSust, Math.abs(maxSust - avg), 120);
        List<SegmentSuggestion> segs;
        if (split) {
            segs =
                    List.of(
                            new SegmentSuggestion(
                                    0, 40, 2.0, 2.0, "2%", new Coord(0, 0), new Coord(40, 0)),
                            new SegmentSuggestion(
                                    40, 120, 8.0, 8.0, "8%", new Coord(40, 0), new Coord(120, 0)));
        } else {
            segs =
                    List.of(
                            new SegmentSuggestion(
                                    0,
                                    120,
                                    avg,
                                    maxSust,
                                    no.nvdbincline.core.tag.InclineTags.formatIncline(avg),
                                    new Coord(0, 0),
                                    new Coord(120, 0)));
        }
        Map<String, String> tags =
                AppliedTags.incline(
                        split
                                ? no.nvdbincline.core.tag.InclineTags.formatIncline(avg)
                                : segs.get(0).inclineTag());
        return new WaySuggestion(match, List.of(), stats, segs, split, null, tags);
    }

    @Test
    void lowQualityAndHighQualityMatchesAreDistinguishableInReviewModel() {
        WaySuggestion clean = sug(1, MatchConfidence.HIGH, "geometry", 1.2, 9.6, 12.0, false);
        WaySuggestion shaky = sug(2, MatchConfidence.LOW, "geometry", 28.5, 4.1, 6.0, false);

        ReviewModel model = ReviewModel.fromEngine(List.of(clean, shaky), List.of());
        ReviewModel.Row cleanRow =
                model.rows().stream().filter(r -> r.osmId == 1).findFirst().orElseThrow();
        ReviewModel.Row shakyRow =
                model.rows().stream().filter(r -> r.osmId == 2).findFirst().orElseThrow();

        assertNotNull(cleanRow.inclineAudit);
        assertNotNull(shakyRow.inclineAudit);
        assertEquals(MatchConfidence.HIGH, cleanRow.inclineAudit.matchConfidence());
        assertEquals(MatchConfidence.LOW, shakyRow.inclineAudit.matchConfidence());
        assertEquals(1.2, cleanRow.inclineAudit.matchHausdorffM(), 1e-9);
        assertEquals(28.5, shakyRow.inclineAudit.matchHausdorffM(), 1e-9);
        assertTrue(shakyRow.inclineAudit.matchHausdorffM() > cleanRow.inclineAudit.matchHausdorffM());
        assertEquals("geometry", cleanRow.inclineAudit.matchMethod());
        assertEquals("geometry", shakyRow.inclineAudit.matchMethod());
    }

    @Test
    void allEightAuditFieldsPresentInModelButAbsentFromAppliedTags() {
        WaySuggestion splitSug = sug(7, MatchConfidence.HIGH, "nvdb:id", 0.4, 5.7, 11.0, true);
        ReviewModel model = ReviewModel.fromEngine(List.of(splitSug), List.of());
        ReviewModel.Row row = model.rows().get(0);
        InclineAudit a = row.inclineAudit;
        assertNotNull(a);

        // Eight former tag fields — still present for the reviewer:
        assertEquals("nvdb:id", a.matchMethod()); // match_method
        assertEquals(MatchConfidence.HIGH, a.matchConfidence()); // match_confidence
        assertEquals(0.4, a.matchHausdorffM(), 1e-9); // match_hausdorff_m
        assertEquals(5.7, a.estimatedAvgPct(), 1e-9); // estimated_avg
        assertEquals(11.0, a.estimatedMaxSustainedPct(), 1e-9); // estimated_max_sustained
        assertFalse(a.suggestedIncline().isBlank()); // suggested (rounded proposed)
        assertEquals("2%;8%", a.suggestedSegments()); // suggested_segments
        assertTrue(a.splitRecommended()); // split_recommended

        // Applied tags: allowlisted only — none of the eight bookkeeping keys:
        assertEquals(AppliedTags.WAY_INCLINE_KEYS, row.tags.keySet());
        for (String forbidden : AppliedTags.FORBIDDEN_LEGACY_KEYS) {
            assertFalse(row.tags.containsKey(forbidden), "leaked " + forbidden);
        }
        assertNull(row.tags.get("incline:match_method"));
        assertNull(row.tags.get("incline:estimated_avg"));
        assertNull(row.tags.get("incline:split_recommended"));
        assertTrue(row.splitSuggested);
        assertTrue(a.detailTooltip().contains("suggested_segments=2%;8%"));
    }
}
