package no.nvdbincline.core.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import org.junit.jupiter.api.Test;

class WayMatcherTest {

    private static OsmWayGeom way(long id, List<Coord> coords, String nvdbId) {
        return new OsmWayGeom(id, new Polyline(coords), "secondary", "Test", nvdbId, null);
    }

    private static NvdbLink link(long vid, List<Coord> coords) {
        return new NvdbLink(vid, "0-1@" + vid, "HOVED", "Enkel bilveg", null, 0, 1, new Polyline(coords));
    }

    @Test
    void nvdbIdFastPath() {
        var coords = List.of(new Coord(0, 0), new Coord(100, 0), new Coord(200, 0));
        var result =
                WayMatcher.match(
                        List.of(way(1, coords, "55")),
                        List.of(link(55, List.of(new Coord(0, 0, 10), new Coord(100, 0, 10), new Coord(200, 0, 11)))),
                        new WayMatcher.Settings());
        assertEquals(1, result.matches.size());
        assertEquals("nvdb:id", result.matches.get(0).method());
        assertEquals(MatchConfidence.HIGH, result.matches.get(0).confidence());
    }

    @Test
    void geometryHighOrMedium() {
        var result =
                WayMatcher.match(
                        List.of(way(2, List.of(new Coord(0, 0), new Coord(150, 0)), null)),
                        List.of(link(70, List.of(new Coord(0, 1, 5), new Coord(150, 1, 8)))),
                        new WayMatcher.Settings());
        assertEquals(1, result.matches.size());
        assertTrue(
                result.matches.get(0).confidence() == MatchConfidence.HIGH
                        || result.matches.get(0).confidence() == MatchConfidence.MEDIUM);
    }

    @Test
    void deliberatelyBadMatchRejectedOrLow() {
        var result =
                WayMatcher.match(
                        List.of(way(3, List.of(new Coord(0, 0), new Coord(100, 0)), null)),
                        List.of(link(80, List.of(new Coord(1000, 1000, 0), new Coord(1100, 1000, 0)))),
                        new WayMatcher.Settings());
        assertTrue(result.matches.isEmpty() || result.matches.get(0).confidence() == MatchConfidence.LOW);
        if (result.matches.isEmpty()) {
            assertEquals(1, result.unmatchedOsm.size());
        }
    }

    @Test
    void nearestFallbackMarkedLow() {
        WayMatcher.Settings s = new WayMatcher.Settings();
        s.hausdorffHighM = 15;
        s.hausdorffMediumM = 30;
        s.nearestFallbackM = 50;
        var result =
                WayMatcher.match(
                        List.of(way(4, List.of(new Coord(0, 0), new Coord(100, 0)), null)),
                        List.of(link(90, List.of(new Coord(0, 40, 0), new Coord(100, 40, 0)))),
                        s);
        assertEquals(1, result.matches.size());
        assertEquals(MatchConfidence.LOW, result.matches.get(0).confidence());
        assertEquals("nearest-fallback", result.matches.get(0).method());
    }
}
