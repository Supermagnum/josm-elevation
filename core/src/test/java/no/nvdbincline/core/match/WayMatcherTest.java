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

    @Test
    void longOsmWayMatchesOverlappingShortNvdbSegments() {
        // OSM way ~300 m; NVDB sequence continues far beyond (common for veglenkesekvens).
        List<Coord> osm =
                List.of(new Coord(0, 0), new Coord(100, 0), new Coord(200, 0), new Coord(300, 0));
        List<NvdbLink> links =
                List.of(
                        link(1, List.of(new Coord(0, 1, 10), new Coord(100, 1, 12))),
                        link(1, List.of(new Coord(100, 1, 12), new Coord(200, 1, 14))),
                        link(1, List.of(new Coord(200, 1, 14), new Coord(300, 1, 16))),
                        // Far continuation of the same sequence — must not inflate distance.
                        link(1, List.of(new Coord(5000, 1, 20), new Coord(5200, 1, 22))));
        var result = WayMatcher.match(List.of(way(99, osm, null)), links, new WayMatcher.Settings());
        assertEquals(1, result.matches.size());
        assertTrue(
                result.matches.get(0).confidence() == MatchConfidence.HIGH
                        || result.matches.get(0).confidence() == MatchConfidence.MEDIUM,
                "expected usable confidence, got " + result.matches.get(0).confidence());
        assertEquals(3, result.matches.get(0).links().size());
    }

    @Test
    void spatialIndexIgnoresDistantLinks() {
        List<Coord> osm = List.of(new Coord(0, 0), new Coord(100, 0));
        List<NvdbLink> links = new java.util.ArrayList<>();
        links.add(link(1, List.of(new Coord(0, 1, 5), new Coord(100, 1, 8))));
        for (int i = 0; i < 200; i++) {
            double x = 10_000 + i * 200;
            links.add(link(1000 + i, List.of(new Coord(x, 0, 0), new Coord(x + 50, 0, 1))));
        }
        var result = WayMatcher.match(List.of(way(5, osm, null)), links, new WayMatcher.Settings());
        assertEquals(1, result.matches.size());
        assertEquals(1, result.matches.get(0).links().size());
        assertEquals(1L, result.matches.get(0).links().get(0).veglenkesekvensId());
    }

    @Test
    void progressCancelStopsMatching() {
        List<OsmWayGeom> ways = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            double x = i * 200;
            ways.add(way(i, List.of(new Coord(x, 0), new Coord(x + 100, 0)), null));
        }
        var links = List.of(link(1, List.of(new Coord(0, 1, 0), new Coord(100, 1, 1))));
        org.junit.jupiter.api.Assertions.assertThrows(
                WayMatcher.CancelledException.class,
                () ->
                        WayMatcher.match(
                                ways,
                                links,
                                new WayMatcher.Settings(),
                                (phase, done, total) -> done < 10));
    }
}
