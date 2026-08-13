package no.nvdbincline.core.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import no.nvdbincline.core.model.ChainKind;
import no.nvdbincline.core.model.ChainPoint;
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

class ReviewModelTest {

    private static WaySuggestion waySug(long id, MatchConfidence conf, boolean existing) {
        var way =
                new OsmWayGeom(
                        id,
                        new Polyline(List.of(new Coord(0, 0), new Coord(100, 0))),
                        "secondary",
                        "W",
                        null,
                        existing ? "5%" : null);
        var match = new MatchResult(way, List.of(), conf, "geometry", 1.0, "");
        var stats = new GradientStats(10, 10, 10, 10, 0, 100);
        var seg =
                new SegmentSuggestion(
                        0, 100, 10, 10, "10%", new Coord(0, 0), new Coord(100, 0));
        if (existing) {
            return new WaySuggestion(
                    match, List.of(), stats, List.of(seg), false, "existing incline=* not overwritten", Map.of());
        }
        return new WaySuggestion(
                match,
                List.of(),
                stats,
                List.of(seg),
                false,
                null,
                AppliedTags.incline("10%"));
    }

    @Test
    void onlyCheckedRowsAccepted() {
        ReviewModel model =
                ReviewModel.fromEngine(
                        List.of(
                                waySug(1, MatchConfidence.HIGH, false),
                                waySug(2, MatchConfidence.LOW, false),
                                waySug(3, MatchConfidence.HIGH, true)),
                        List.of(new ChainPoint(50, 0, ChainKind.FIT, "test", 1L)));
        assertEquals(4, model.rows().size());
        // High confidence ways default accepted; chain and low not.
        assertEquals(1, model.acceptedRows().size());
        model.acceptAllHighConfidence();
        assertEquals(1, model.acceptedRows().size());
        model.rows().get(1).accepted = true;
        assertEquals(2, model.acceptedRows().size());
        model.rejectAll();
        assertTrue(model.acceptedRows().isEmpty());
        model.acceptAll();
        // discrepancy never accepted
        assertEquals(3, model.acceptedRows().size());
    }
}
