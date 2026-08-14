package no.nvdbincline.core.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;

/**
 * Bounding-box helpers.
 *
 * <p>For kommune mode the plugin prefers NVDB's {@code kommune=} query parameter
 * (exact administrative filter on the NVDB side). NVDB Områder API v4 no longer
 * exposes a stable per-kommune polygon endpoint, so the OSM half uses a
 * <strong>bbox approximation</strong> computed from the fetched NVDB link
 * geometries (UTM33 → WGS84). That bbox is used only to filter the active
 * JOSM layer (and optionally to suggest a download extent) — not as a
 * substitute for the NVDB kommune filter.
 *
 * <p>The OSM map API rejects bboxes larger than about 0.25 square degrees; use
 * {@link #tilesForOsmApi} to split oversized extents before download.
 */
public final class Bbox {
    /**
     * Prefer smaller tiles than the OSM area hard-limit (0.25): dense Norwegian
     * road networks often hit the 50 000-node cap before the area cap.
     */
    public static final double OSM_API_MAX_AREA_SQ_DEG = 0.04;

    /** Soft cap for the initial grid; adaptive splits may go somewhat beyond. */
    public static final int OSM_API_MAX_TILES = 256;

    private Bbox() {}

    /** minLon, minLat, maxLon, maxLat from NVDB links in UTM33. */
    public static double[] fromNvdbLinksLonLat(List<NvdbLink> links) {
        if (links == null || links.isEmpty()) {
            throw new IllegalArgumentException("no NVDB links to derive bbox from");
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (NvdbLink link : links) {
            Polyline line = link.line();
            if (line == null) {
                continue;
            }
            for (Coord c : line.points()) {
                minX = Math.min(minX, c.x());
                minY = Math.min(minY, c.y());
                maxX = Math.max(maxX, c.x());
                maxY = Math.max(maxY, c.y());
            }
        }
        if (!(minX < maxX) || !(minY < maxY)) {
            throw new IllegalArgumentException("could not derive bbox from NVDB links");
        }
        double[] a = Utm33.utmToLonLat(minX, minY);
        double[] b = Utm33.utmToLonLat(maxX, maxY);
        return new double[] {
            Math.min(a[0], b[0]),
            Math.min(a[1], b[1]),
            Math.max(a[0], b[0]),
            Math.max(a[1], b[1])
        };
    }

    /** Area in square degrees: {@code (maxLon-minLon) * (maxLat-minLat)}. */
    public static double areaSquareDegrees(double minLon, double minLat, double maxLon, double maxLat) {
        return Math.max(0, maxLon - minLon) * Math.max(0, maxLat - minLat);
    }

    public static double areaSquareDegrees(double[] bbox) {
        return areaSquareDegrees(bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    /**
     * Split a WGS84 bbox into tiles each at most {@code maxAreaSqDeg} square degrees
     * so they fit the OSM map API. Returns a single-element list when already small
     * enough. Throws {@link IllegalArgumentException} if more than {@link #OSM_API_MAX_TILES}
     * tiles would be required.
     *
     * @return list of {@code [minLon, minLat, maxLon, maxLat]}
     */
    public static List<double[]> tilesForOsmApi(
            double minLon, double minLat, double maxLon, double maxLat, double maxAreaSqDeg) {
        if (!(minLon < maxLon) || !(minLat < maxLat)) {
            throw new IllegalArgumentException("invalid bbox");
        }
        if (maxAreaSqDeg <= 0) {
            throw new IllegalArgumentException("maxAreaSqDeg must be positive");
        }
        double w = maxLon - minLon;
        double h = maxLat - minLat;
        double area = w * h;
        if (area <= maxAreaSqDeg) {
            return List.of(new double[] {minLon, minLat, maxLon, maxLat});
        }

        double cell = Math.sqrt(maxAreaSqDeg);
        int cols = Math.max(1, (int) Math.ceil(w / cell));
        int rows = Math.max(1, (int) Math.ceil(h / cell));
        while ((w / cols) * (h / rows) > maxAreaSqDeg + 1e-12) {
            if ((w / cols) >= (h / rows)) {
                cols++;
            } else {
                rows++;
            }
        }
        int total = cols * rows;
        if (total > OSM_API_MAX_TILES) {
            throw new IllegalArgumentException(
                    "bbox too large for OSM API tiling ("
                            + String.format(Locale.ROOT, "%.3f", area)
                            + " sq deg → "
                            + total
                            + " tiles; max "
                            + OSM_API_MAX_TILES
                            + ")");
        }

        List<double[]> tiles = new ArrayList<>(total);
        double dLon = w / cols;
        double dLat = h / rows;
        for (int r = 0; r < rows; r++) {
            double tMinLat = minLat + r * dLat;
            double tMaxLat = (r == rows - 1) ? maxLat : minLat + (r + 1) * dLat;
            for (int c = 0; c < cols; c++) {
                double tMinLon = minLon + c * dLon;
                double tMaxLon = (c == cols - 1) ? maxLon : minLon + (c + 1) * dLon;
                tiles.add(new double[] {tMinLon, tMinLat, tMaxLon, tMaxLat});
            }
        }
        return tiles;
    }

    public static List<double[]> tilesForOsmApi(double[] bbox) {
        return tilesForOsmApi(bbox[0], bbox[1], bbox[2], bbox[3], OSM_API_MAX_AREA_SQ_DEG);
    }

    /**
     * Split one bbox into four quadrants (NW, NE, SW, SE) for adaptive retries when
     * the OSM API returns “too many nodes”.
     */
    public static List<double[]> quadrants(double[] bbox) {
        double minLon = bbox[0];
        double minLat = bbox[1];
        double maxLon = bbox[2];
        double maxLat = bbox[3];
        double midLon = (minLon + maxLon) / 2.0;
        double midLat = (minLat + maxLat) / 2.0;
        return List.of(
                new double[] {minLon, midLat, midLon, maxLat},
                new double[] {midLon, midLat, maxLon, maxLat},
                new double[] {minLon, minLat, midLon, midLat},
                new double[] {midLon, minLat, maxLon, midLat});
    }

    /** Keep ways whose any vertex lies inside the WGS84 bbox (inclusive). */
    public static List<OsmWayGeom> filterWaysInBboxLonLat(
            List<OsmWayGeom> ways, double minLon, double minLat, double maxLon, double maxLat) {
        double[] sw = Utm33.lonLatToUtm(minLon, minLat);
        double[] ne = Utm33.lonLatToUtm(maxLon, maxLat);
        double minX = Math.min(sw[0], ne[0]);
        double minY = Math.min(sw[1], ne[1]);
        double maxX = Math.max(sw[0], ne[0]);
        double maxY = Math.max(sw[1], ne[1]);
        return ways.stream()
                .filter(w -> intersects(w.line(), minX, minY, maxX, maxY))
                .toList();
    }

    private static boolean intersects(Polyline line, double minX, double minY, double maxX, double maxY) {
        if (line == null) {
            return false;
        }
        for (Coord c : line.points()) {
            if (c.x() >= minX && c.x() <= maxX && c.y() >= minY && c.y() <= maxY) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code inner} is fully inside {@code outer} (WGS84). */
    public static boolean contains(
            double[] outerMinMax, double minLon, double minLat, double maxLon, double maxLat) {
        return outerMinMax[0] <= minLon
                && outerMinMax[1] <= minLat
                && outerMinMax[2] >= maxLon
                && outerMinMax[3] >= maxLat;
    }
}
