package no.nvdbincline.core.geo;

/**
 * Approximate WGS84 lon/lat &lt;-&gt; ETRS89 / UTM zone 33N (EPSG:25833) conversion.
 * Good enough for matching and display; NVDB geometry is already in UTM33.
 */
public final class Utm33 {
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257222101;
    private static final double E2 = F * (2 - F);
    private static final double K0 = 0.9996;
    private static final double LON0 = Math.toRadians(15.0);
    private static final double E0 = 500000.0;
    private static final double N0 = 0.0;

    private Utm33() {}

    public static double[] lonLatToUtm(double lonDeg, double latDeg) {
        double lon = Math.toRadians(lonDeg);
        double lat = Math.toRadians(latDeg);
        double n = A / Math.sqrt(1 - E2 * Math.sin(lat) * Math.sin(lat));
        double t = Math.tan(lat);
        double c = E2 * Math.cos(lat) * Math.cos(lat) / (1 - E2);
        double a = Math.cos(lat) * (lon - LON0);
        double m =
                A
                        * ((1
                                        - E2 / 4
                                        - 3 * E2 * E2 / 64
                                        - 5 * E2 * E2 * E2 / 256)
                                        * lat
                                - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024)
                                        * Math.sin(2 * lat)
                                + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024)
                                        * Math.sin(4 * lat)
                                - (35 * E2 * E2 * E2 / 3072) * Math.sin(6 * lat));
        double x =
                K0
                                * n
                                * (a
                                        + (1 - t * t + c) * a * a * a / 6
                                        + (5 - 18 * t * t + t * t * t * t + 72 * c - 58)
                                                * a
                                                * a
                                                * a
                                                * a
                                                * a
                                                / 120)
                        + E0;
        double y =
                K0
                                * (m
                                        + n
                                                * t
                                                * (a * a / 2
                                                        + (5 - t * t + 9 * c + 4 * c * c)
                                                                * a
                                                                * a
                                                                * a
                                                                * a
                                                                / 24
                                                        + (61 - 58 * t * t + t * t * t * t + 600 * c - 330)
                                                                * a
                                                                * a
                                                                * a
                                                                * a
                                                                * a
                                                                * a
                                                                / 720))
                        + N0;
        return new double[] {x, y};
    }

    public static double[] utmToLonLat(double easting, double northing) {
        double x = easting - E0;
        double y = northing - N0;
        double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
        double m = y / K0;
        double mu =
                m
                        / (A
                                * (1
                                        - E2 / 4
                                        - 3 * E2 * E2 / 64
                                        - 5 * E2 * E2 * E2 / 256));
        double phi1 =
                mu
                        + (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * Math.sin(2 * mu)
                        + (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * Math.sin(4 * mu)
                        + (151 * e1 * e1 * e1 / 96) * Math.sin(6 * mu);
        double n1 = A / Math.sqrt(1 - E2 * Math.sin(phi1) * Math.sin(phi1));
        double t1 = Math.tan(phi1);
        double c1 = E2 * Math.cos(phi1) * Math.cos(phi1) / (1 - E2);
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * Math.sin(phi1) * Math.sin(phi1), 1.5);
        double d = x / (n1 * K0);
        double lat =
                phi1
                        - (n1 * t1 / r1)
                                * (d * d / 2
                                        - (5 + 3 * t1 * t1 + 10 * c1 - 4 * c1 * c1 - 9)
                                                * d
                                                * d
                                                * d
                                                * d
                                                / 24
                                        + (61 + 90 * t1 * t1 + 298 * c1 + 45 * t1 * t1 * t1 * t1 - 252 - 3 * c1 * c1)
                                                * d
                                                * d
                                                * d
                                                * d
                                                * d
                                                * d
                                                / 720);
        double lon =
                LON0
                        + (d
                                        - (1 + 2 * t1 * t1 + c1) * d * d * d / 6
                                        + (5 - 2 * c1 + 28 * t1 * t1 - 3 * c1 * c1 + 8 + 24 * t1 * t1 * t1 * t1)
                                                * d
                                                * d
                                                * d
                                                * d
                                                * d
                                                / 120)
                                / Math.cos(phi1);
        return new double[] {Math.toDegrees(lon), Math.toDegrees(lat)};
    }
}
