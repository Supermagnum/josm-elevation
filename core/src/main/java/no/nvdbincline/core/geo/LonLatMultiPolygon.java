package no.nvdbincline.core.geo;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Lon/lat (EPSG:4258 / WGS84) multipolygon for kommune clipping.
 *
 * <p>Rings are closed (first point equals last). Outer rings are CCW or CW
 * (winding is not enforced); holes are supported as additional rings in each
 * polygon.
 */
public final class LonLatMultiPolygon {
    private final List<Polygon> polygons;
    private final double minLon;
    private final double minLat;
    private final double maxLon;
    private final double maxLat;

    public LonLatMultiPolygon(List<Polygon> polygons) {
        if (polygons == null || polygons.isEmpty()) {
            throw new IllegalArgumentException("multipolygon needs at least one polygon");
        }
        this.polygons = List.copyOf(polygons);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Polygon p : this.polygons) {
            minX = Math.min(minX, p.minLon);
            minY = Math.min(minY, p.minLat);
            maxX = Math.max(maxX, p.maxLon);
            maxY = Math.max(maxY, p.maxLat);
        }
        this.minLon = minX;
        this.minLat = minY;
        this.maxLon = maxX;
        this.maxLat = maxY;
    }

    public double minLon() {
        return minLon;
    }

    public double minLat() {
        return minLat;
    }

    public double maxLon() {
        return maxLon;
    }

    public double maxLat() {
        return maxLat;
    }

    /** Axis-aligned envelope: {@code [minLon, minLat, maxLon, maxLat]}. */
    public double[] envelope() {
        return new double[] {minLon, minLat, maxLon, maxLat};
    }

    public boolean envelopeContains(double lon, double lat) {
        return lon >= minLon && lon <= maxLon && lat >= minLat && lat <= maxLat;
    }

    /** Point-in-polygon (ray casting), respecting holes. */
    public boolean contains(double lon, double lat) {
        if (!envelopeContains(lon, lat)) {
            return false;
        }
        for (Polygon p : polygons) {
            if (p.contains(lon, lat)) {
                return true;
            }
        }
        return false;
    }

    public List<Polygon> polygons() {
        return polygons;
    }

    /** One polygon: first ring outer, subsequent rings holes. */
    public static final class Polygon {
        private final List<Ring> rings;
        private final double minLon;
        private final double minLat;
        private final double maxLon;
        private final double maxLat;

        public Polygon(List<Ring> rings) {
            if (rings == null || rings.isEmpty()) {
                throw new IllegalArgumentException("polygon needs an outer ring");
            }
            this.rings = List.copyOf(rings);
            Ring outer = this.rings.get(0);
            this.minLon = outer.minLon;
            this.minLat = outer.minLat;
            this.maxLon = outer.maxLon;
            this.maxLat = outer.maxLat;
        }

        boolean contains(double lon, double lat) {
            if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) {
                return false;
            }
            if (!rings.get(0).contains(lon, lat)) {
                return false;
            }
            for (int i = 1; i < rings.size(); i++) {
                if (rings.get(i).contains(lon, lat)) {
                    return false; // in a hole
                }
            }
            return true;
        }
    }

    /** Closed ring of lon/lat vertices. */
    public static final class Ring {
        private final double[] lons;
        private final double[] lats;
        private final double minLon;
        private final double minLat;
        private final double maxLon;
        private final double maxLat;

        public Ring(List<double[]> lonLatPairs) {
            Objects.requireNonNull(lonLatPairs);
            if (lonLatPairs.size() < 4) {
                throw new IllegalArgumentException("ring needs at least 4 positions (closed)");
            }
            int n = lonLatPairs.size();
            this.lons = new double[n];
            this.lats = new double[n];
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                double[] p = lonLatPairs.get(i);
                lons[i] = p[0];
                lats[i] = p[1];
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
            this.minLon = minX;
            this.minLat = minY;
            this.maxLon = maxX;
            this.maxLat = maxY;
        }

        /** Ray-casting even-odd rule. */
        boolean contains(double lon, double lat) {
            boolean inside = false;
            int n = lons.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double yi = lats[i];
                double yj = lats[j];
                if ((yi > lat) != (yj > lat)) {
                    double xi = lons[i];
                    double xj = lons[j];
                    double xIntersect = (xj - xi) * (lat - yi) / (yj - yi + 0.0) + xi;
                    if (lon < xIntersect) {
                        inside = !inside;
                    }
                }
            }
            return inside;
        }

        @Override
        public String toString() {
            return "Ring(n=" + lons.length + ", bbox=" + Arrays.toString(new double[] {minLon, minLat, maxLon, maxLat}) + ")";
        }
    }
}
