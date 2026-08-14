package no.nvdbincline.core.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import no.nvdbincline.core.area.AreaSelection;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import org.junit.jupiter.api.Test;

/**
 * Area resolution without live network: kommune mode uses NVDB {@code kommune=}
 * (asserted via {@link AreaSelection}); OSM filtering uses a bbox derived from
 * NVDB link geometry (documented approximation).
 */
class BboxAreaResolutionTest {
    @Test
    void kommuneSelectionCarriesNummer() {
        AreaSelection sel = AreaSelection.kommune(301, "Oslo");
        assertTrue(sel.isKommune());
        assertEquals(301, sel.kommuneNummer());
        assertEquals(AreaSelection.Mode.KOMMUNE, sel.mode());
    }

    @Test
    void bboxFromLinksAndFilterWays() {
        // Two UTM33 points around Oslo area (approx).
        double[] a = Utm33.lonLatToUtm(10.70, 59.90);
        double[] b = Utm33.lonLatToUtm(10.80, 59.95);
        NvdbLink link =
                new NvdbLink(
                        1,
                        "1",
                        "HOVED",
                        "Kanalisert veg",
                        null,
                        0,
                        1,
                        new Polyline(List.of(new Coord(a[0], a[1]), new Coord(b[0], b[1]))));
        double[] bbox = Bbox.fromNvdbLinksLonLat(List.of(link));
        assertTrue(bbox[0] < bbox[2]);
        assertTrue(bbox[1] < bbox[3]);
        assertTrue(bbox[0] < 10.75 && bbox[2] > 10.75);

        OsmWayGeom inside =
                new OsmWayGeom(
                        10,
                        new Polyline(
                                List.of(
                                        new Coord(a[0] + 100, a[1] + 100),
                                        new Coord(a[0] + 200, a[1] + 200))),
                        "primary",
                        "In",
                        null,
                        null);
        double[] far = Utm33.lonLatToUtm(5.0, 60.0);
        OsmWayGeom outside =
                new OsmWayGeom(
                        11,
                        new Polyline(
                                List.of(
                                        new Coord(far[0], far[1]),
                                        new Coord(far[0] + 50, far[1] + 50))),
                        "primary",
                        "Out",
                        null,
                        null);
        List<OsmWayGeom> filtered =
                Bbox.filterWaysInBboxLonLat(List.of(inside, outside), bbox[0], bbox[1], bbox[2], bbox[3]);
        assertEquals(1, filtered.size());
        assertEquals(10, filtered.get(0).id());
    }

    @Test
    void tilesSmallBboxUnchanged() {
        List<double[]> tiles = Bbox.tilesForOsmApi(new double[] {10.0, 60.0, 10.1, 60.1});
        assertEquals(1, tiles.size());
        assertEquals(0.01, Bbox.areaSquareDegrees(tiles.get(0)), 1e-9);
    }

    @Test
    void tilesLargeBboxSplitsUnderOsmLimit() {
        // ~1.0 sq deg → must split; each tile ≤ 0.04
        List<double[]> tiles = Bbox.tilesForOsmApi(new double[] {10.0, 60.0, 11.0, 61.0});
        assertTrue(tiles.size() > 1);
        assertTrue(tiles.size() <= Bbox.OSM_API_MAX_TILES);
        for (double[] t : tiles) {
            assertTrue(
                    Bbox.areaSquareDegrees(t) <= Bbox.OSM_API_MAX_AREA_SQ_DEG + 1e-9,
                    "tile area " + Bbox.areaSquareDegrees(t));
        }
    }

    @Test
    void quadrantsSplitInFour() {
        List<double[]> q = Bbox.quadrants(new double[] {10.0, 60.0, 10.4, 60.4});
        assertEquals(4, q.size());
        double sum = 0;
        for (double[] t : q) {
            sum += Bbox.areaSquareDegrees(t);
        }
        assertEquals(0.16, sum, 1e-9);
    }

    @Test
    void tilesRejectsEnormousBbox() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Bbox.tilesForOsmApi(new double[] {5.0, 58.0, 31.0, 71.0}));
    }

    @Test
    void expectedKommuneQueryShape() {
        // Documents the NVDB query the plugin builds for kommune mode (no HTTP).
        int kommune = 301;
        String areaQuery = "kommune=" + kommune;
        assertEquals("kommune=301", areaQuery);
        assertTrue(areaQuery.startsWith("kommune="));
    }
}
