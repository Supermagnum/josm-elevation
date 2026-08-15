package no.nvdbincline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.tag.AppliedTags;
import no.nvdbincline.core.tag.ExistingTagPolicy;
import org.junit.jupiter.api.Test;

/**
 * Fresh / update / discrepancy classification for existing incline=* tags, plus
 * review-dialog and Command-applicability guarantees.
 */
class SuggestionEngineExistingTagsTest {

    private static Polyline climb() {
        // 100 m run, 8 m rise → ~8%
        return new Polyline(List.of(new Coord(0, 0, 0), new Coord(100, 0, 8)));
    }

    private static OsmWayGeom way(
            long id, String incline, String sourceIncline) {
        return new OsmWayGeom(
                id,
                climb(),
                "secondary",
                "W" + id,
                null,
                incline,
                sourceIncline,
                null,
                null,
                null,
                null);
    }

    private static NvdbLink link(long id) {
        return new NvdbLink(id, "0-1@" + id, "HOVED", "Enkel bilveg", null, 0, 1, climb());
    }

    @Test
    void threeWayDispositionViaEngine() {
        List<OsmWayGeom> ways =
                List.of(
                        shift(way(1, null, null), 0),
                        shift(way(2, "6%", AppliedTags.INCLINE_SOURCE_VALUE), 1000),
                        shift(way(3, "5%", null), 2000),
                        shift(way(4, "5%", "survey"), 3000));
        List<NvdbLink> links =
                List.of(
                        shiftLink(link(10), 0),
                        shiftLink(link(20), 1000),
                        shiftLink(link(30), 2000),
                        shiftLink(link(40), 3000));

        SuggestionEngine.Output out =
                SuggestionEngine.run(ways, links, new SuggestionEngine.Config());

        WaySuggestion fresh = byWayId(out.suggestions, 1);
        WaySuggestion update = byWayId(out.suggestions, 2);
        WaySuggestion discNoSrc = byWayId(out.suggestions, 3);
        WaySuggestion discSurvey = byWayId(out.suggestions, 4);

        assertEquals(ExistingTagPolicy.InclineDisposition.FRESH, fresh.inclineDisposition());
        assertTrue(fresh.isApplicable());
        assertTrue(fresh.tagsToAdd().containsKey("incline"));

        assertEquals(ExistingTagPolicy.InclineDisposition.UPDATE, update.inclineDisposition());
        assertTrue(update.isUpdate());
        assertTrue(update.isApplicable());
        assertTrue(update.tagsToAdd().containsKey("incline"));

        assertEquals(
                ExistingTagPolicy.InclineDisposition.DISCREPANCY_NOTE,
                discNoSrc.inclineDisposition());
        assertFalse(discNoSrc.isApplicable());
        assertTrue(discNoSrc.tagsToAdd().isEmpty());

        assertEquals(
                ExistingTagPolicy.InclineDisposition.DISCREPANCY_NOTE,
                discSurvey.inclineDisposition());
        assertFalse(discSurvey.isApplicable());
        assertTrue(discSurvey.tagsToAdd().isEmpty());
    }

    @Test
    void reviewDialogDistinguishesAndDiscrepancyNeverCommands() {
        List<OsmWayGeom> ways =
                List.of(
                        shift(way(1, null, null), 0),
                        shift(way(2, "6%", AppliedTags.INCLINE_SOURCE_VALUE), 1000),
                        shift(way(3, "5%", "survey"), 2000));
        List<NvdbLink> links =
                List.of(shiftLink(link(10), 0), shiftLink(link(20), 1000), shiftLink(link(30), 2000));
        SuggestionEngine.Output out =
                SuggestionEngine.run(ways, links, new SuggestionEngine.Config());
        ReviewModel model = ReviewModel.fromEngine(out.suggestions, List.of(), List.of());

        ReviewModel.Row fresh =
                model.rows().stream()
                        .filter(r -> r.osmId == 1)
                        .findFirst()
                        .orElseThrow();
        ReviewModel.Row update =
                model.rows().stream()
                        .filter(r -> r.osmId == 2)
                        .findFirst()
                        .orElseThrow();
        ReviewModel.Row disc =
                model.rows().stream()
                        .filter(r -> r.osmId == 3)
                        .findFirst()
                        .orElseThrow();

        assertEquals(ReviewModel.Kind.WAY_TAGS, fresh.kind);
        assertFalse(fresh.summary.startsWith("Update:"));
        assertTrue(fresh.tags.containsKey("incline"));

        assertEquals(ReviewModel.Kind.WAY_TAGS, update.kind);
        assertTrue(update.summary.startsWith("Update:"));
        assertTrue(update.tags.containsKey("incline"));

        assertEquals(ReviewModel.Kind.DISCREPANCY, disc.kind);
        assertTrue(disc.summary.contains("discrepancy note"));
        assertTrue(disc.tags.isEmpty());

        model.acceptAll();
        assertTrue(
                model.acceptedRows().stream().noneMatch(r -> r.kind == ReviewModel.Kind.DISCREPANCY));
        assertTrue(model.acceptedRows().stream().anyMatch(r -> r.osmId == 1));
        assertTrue(model.acceptedRows().stream().anyMatch(r -> r.osmId == 2));
        assertFalse(model.acceptedRows().stream().anyMatch(r -> r.osmId == 3));
    }

    @Test
    void nonPluginInclineNeverProducesApplicableTags() {
        OsmWayGeom surveyed = shift(way(9, "12%", "survey"), 0);
        NvdbLink nvdb = shiftLink(link(99), 0);
        SuggestionEngine.Output out =
                SuggestionEngine.run(
                        List.of(surveyed), List.of(nvdb), new SuggestionEngine.Config());
        for (WaySuggestion s : out.suggestions) {
            if (s.match().way().id() == 9) {
                assertEquals(
                        ExistingTagPolicy.InclineDisposition.DISCREPANCY_NOTE,
                        s.inclineDisposition());
                assertFalse(s.isApplicable());
                assertEquals(Map.of(), s.tagsToAdd());
            }
        }
        for (WaySuggestion s : out.discrepancies) {
            assertFalse(s.isApplicable());
            assertTrue(s.tagsToAdd().isEmpty());
        }
    }

    private static WaySuggestion byWayId(List<WaySuggestion> list, long id) {
        return list.stream()
                .filter(s -> s.match().way().id() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing way " + id + " in " + list.size()));
    }

    private static OsmWayGeom shift(OsmWayGeom w, double dy) {
        List<Coord> pts =
                w.line().points().stream()
                        .map(c -> new Coord(c.x(), c.y() + dy, c.z()))
                        .toList();
        return new OsmWayGeom(
                w.id(),
                new Polyline(pts),
                w.highway(),
                w.name(),
                w.nvdbId().orElse(null),
                w.existingIncline().orElse(null),
                w.existingSourceIncline().orElse(null),
                w.existingHazard().orElse(null),
                w.existingSourceHazard().orElse(null),
                w.existingChainAdvisory().orElse(null),
                w.speedLimitKph());
    }

    private static NvdbLink shiftLink(NvdbLink link, double dy) {
        List<Coord> pts =
                link.line().points().stream()
                        .map(c -> new Coord(c.x(), c.y() + dy, c.z()))
                        .toList();
        return new NvdbLink(
                link.veglenkesekvensId(),
                link.kortform(),
                link.type(),
                link.typeVeg(),
                link.medium(),
                link.startposisjon(),
                link.sluttposisjon(),
                new Polyline(pts));
    }
}
