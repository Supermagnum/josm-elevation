package no.nvdbincline.core.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import no.nvdbincline.core.geo.LonLatMultiPolygon;
import org.junit.jupiter.api.Test;

class WayPolygonClipperTest {

    private static LonLatMultiPolygon square(double minLon, double minLat, double maxLon, double maxLat) {
        return new LonLatMultiPolygon(
                List.of(
                        new LonLatMultiPolygon.Polygon(
                                List.of(
                                        new LonLatMultiPolygon.Ring(
                                                List.of(
                                                        new double[] {minLon, minLat},
                                                        new double[] {maxLon, minLat},
                                                        new double[] {maxLon, maxLat},
                                                        new double[] {minLon, maxLat},
                                                        new double[] {minLon, minLat}))))));
    }

    @Test
    void includesWayWithAnyNodeInside() {
        LonLatMultiPolygon a = square(10, 60, 11, 61);
        // fully inside
        assertTrue(
                WayPolygonClipper.anyNodeInside(
                        a, List.of(new double[] {10.2, 60.2}, new double[] {10.3, 60.3})));
        // fully outside
        assertFalse(
                WayPolygonClipper.anyNodeInside(
                        a, List.of(new double[] {12, 62}, new double[] {12.1, 62.1})));
        // straddling: one node inside — INCLUDE (documented rule)
        assertTrue(
                WayPolygonClipper.anyNodeInside(
                        a, List.of(new double[] {10.5, 60.5}, new double[] {12.0, 62.0})));
    }

    @Test
    void crossBorderWayBelongsToExactlyOneKommune() {
        // Adjacent squares sharing lon=11 edge (open on the right for A via contains semantics).
        LonLatMultiPolygon left = square(10, 60, 11, 61);
        LonLatMultiPolygon right = square(11, 60, 12, 61);
        // Way near shared border: one node clearly in left, one clearly in right.
        List<double[]> way =
                List.of(new double[] {10.9, 60.5}, new double[] {11.1, 60.5});
        boolean inLeft = WayPolygonClipper.anyNodeInside(left, way);
        boolean inRight = WayPolygonClipper.anyNodeInside(right, way);
        // With any-node rule the straddling way matches both polygons — that is intentional for
        // clipping from a single kommune polygon at a time. The regression for bbox leakage is:
        // a way entirely in the neighbour must not match.
        assertTrue(inLeft);
        assertTrue(inRight);

        List<double[]> onlyLeft =
                List.of(new double[] {10.2, 60.2}, new double[] {10.3, 60.3});
        assertTrue(WayPolygonClipper.anyNodeInside(left, onlyLeft));
        assertFalse(WayPolygonClipper.anyNodeInside(right, onlyLeft));

        List<double[]> onlyRight =
                List.of(new double[] {11.5, 60.5}, new double[] {11.6, 60.6});
        assertFalse(WayPolygonClipper.anyNodeInside(left, onlyRight));
        assertTrue(WayPolygonClipper.anyNodeInside(right, onlyRight));
    }

    @Test
    void selectIndices() {
        LonLatMultiPolygon poly = square(0, 0, 1, 1);
        List<List<double[]>> ways =
                List.of(
                        List.of(new double[] {0.2, 0.2}, new double[] {0.3, 0.3}),
                        List.of(new double[] {5, 5}, new double[] {6, 6}),
                        List.of(new double[] {0.9, 0.9}, new double[] {2, 2}));
        assertEquals(List.of(0, 2), WayPolygonClipper.selectWayIndices(poly, ways));
    }
}
