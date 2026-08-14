package no.nvdbincline.core.osm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import no.nvdbincline.core.geo.LonLatMultiPolygon;

/**
 * Decides whether an OSM way belongs to a kommune polygon.
 *
 * <p><b>Inclusion rule:</b> a way is included if <em>any</em> of its nodes
 * falls inside the polygon (point-in-polygon). Ways that only touch the
 * boundary from outside, or that merely cross without a node inside, are
 * excluded. Full node lists are kept for included ways so geometry stays intact.
 */
public final class WayPolygonClipper {
    private WayPolygonClipper() {}

    public static boolean anyNodeInside(LonLatMultiPolygon polygon, List<double[]> lonLatNodes) {
        Objects.requireNonNull(polygon, "polygon");
        if (lonLatNodes == null || lonLatNodes.isEmpty()) {
            return false;
        }
        for (double[] ll : lonLatNodes) {
            if (polygon.contains(ll[0], ll[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter ways: each way is {@code List} of {@code [lon,lat]} nodes.
     * Returns indices of ways that pass {@link #anyNodeInside}.
     */
    public static List<Integer> selectWayIndices(
            LonLatMultiPolygon polygon, List<List<double[]>> waysLonLat) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < waysLonLat.size(); i++) {
            if (anyNodeInside(polygon, waysLonLat.get(i))) {
                out.add(i);
            }
        }
        return out;
    }
}
