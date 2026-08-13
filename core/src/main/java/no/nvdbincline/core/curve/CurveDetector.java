package no.nvdbincline.core.curve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.CurveFeature;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;

/**
 * Detect sharp curves from polyline geometry via three-point circle radius,
 * then merge adjacent flagged samples into one feature per curve.
 *
 * <p>Defaults are intentionally conservative so mountain areas do not flood
 * the review dialog with dozens of geometry-only advisories.
 */
public final class CurveDetector {
    public static final class Settings {
        /** Default radius threshold (m) when no speed limit is known. */
        public double defaultRadiusThresholdM = 45.0;
        /** Minimum chord length between sample points (m). */
        public double minChordM = 12.0;
        /** Max gap along-way (m) when merging adjacent sharp samples. */
        public double mergeGapM = 90.0;
        /** Drop merged features shorter than this along-way span (m). */
        public double minArcSpanM = 30.0;
        /** Ignore tiny kink radii (often digitizing noise). */
        public double minMeaningfulRadiusM = 8.0;
        /** After per-way merge, keep only one feature within this XY distance (m). */
        public double spatialDedupeM = 60.0;
        /** Only detect on these highway classes (empty = all). */
        public java.util.Set<String> highwayAllow =
                java.util.Set.of(
                        "motorway",
                        "trunk",
                        "primary",
                        "secondary",
                        "tertiary",
                        "unclassified",
                        "residential",
                        "living_street");
    }

    private CurveDetector() {}

    public static double radiusThresholdM(Integer speedLimitKph, Settings settings) {
        if (speedLimitKph == null || speedLimitKph <= 0) {
            return settings.defaultRadiusThresholdM;
        }
        int v = speedLimitKph;
        if (v <= 40) {
            return 25.0;
        }
        if (v <= 50) {
            return 35.0;
        }
        if (v <= 60) {
            return 40.0;
        }
        if (v <= 70) {
            return 45.0;
        }
        if (v <= 80) {
            return 55.0;
        }
        if (v <= 90) {
            return 70.0;
        }
        return 85.0;
    }

    public static List<CurveFeature> detect(List<OsmWayGeom> ways, Settings settings) {
        List<CurveFeature> out = new ArrayList<>();
        for (OsmWayGeom way : ways) {
            out.addAll(detectWay(way, settings));
        }
        return spatialDedupe(out, settings.spatialDedupeM);
    }

    public static List<CurveFeature> detectWay(OsmWayGeom way, Settings settings) {
        if (!settings.highwayAllow.isEmpty()) {
            String hw = way.highway() == null ? "" : way.highway();
            if (!settings.highwayAllow.contains(hw)) {
                return List.of();
            }
        }
        Polyline line = way.line();
        if (line.size() < 3) {
            return List.of();
        }
        double threshold = radiusThresholdM(way.speedLimitKph(), settings);
        List<Sample> sharp = new ArrayList<>();
        double dist = 0;
        for (int i = 1; i < line.size(); i++) {
            dist += line.get(i - 1).distanceXy(line.get(i));
            if (i < 2) {
                continue;
            }
            Coord a = line.get(i - 2);
            Coord b = line.get(i - 1);
            Coord c = line.get(i);
            double ab = a.distanceXy(b);
            double bc = b.distanceXy(c);
            if (ab < settings.minChordM * 0.5 || bc < settings.minChordM * 0.5) {
                continue;
            }
            Double radius = circumradius(a, b, c);
            if (radius == null || radius <= 0 || Double.isInfinite(radius)) {
                continue;
            }
            if (radius < settings.minMeaningfulRadiusM) {
                continue; // digitizing kink / zig-zag
            }
            if (radius < threshold) {
                sharp.add(new Sample(dist, b.x(), b.y(), radius));
            }
        }
        List<CurveFeature> merged =
                merge(way.id(), sharp, way.speedLimitKph(), settings.mergeGapM);
        List<CurveFeature> kept = new ArrayList<>();
        for (CurveFeature f : merged) {
            if (f.endM() - f.startM() >= settings.minArcSpanM) {
                kept.add(f);
            }
        }
        return kept;
    }

    /**
     * Circumradius of triangle ABC. Straight lines → large/infinite radius.
     */
    public static Double circumradius(Coord a, Coord b, Coord c) {
        double ab = a.distanceXy(b);
        double bc = b.distanceXy(c);
        double ca = c.distanceXy(a);
        if (ab < 1e-6 || bc < 1e-6 || ca < 1e-6) {
            return null;
        }
        double cross =
                (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
        double area2 = Math.abs(cross); // 2 * triangle area
        if (area2 < 1e-6) {
            return Double.POSITIVE_INFINITY;
        }
        // R = abc / (4K) with K = area2/2 → abc / (2 * area2)
        return (ab * bc * ca) / (2.0 * area2);
    }

    /** Keep sharpest curve when several features sit within {@code dedupeM}. */
    static List<CurveFeature> spatialDedupe(List<CurveFeature> features, double dedupeM) {
        if (features.size() <= 1 || dedupeM <= 0) {
            return features;
        }
        List<CurveFeature> sorted = new ArrayList<>(features);
        sorted.sort(Comparator.comparingDouble(CurveFeature::radiusM));
        List<CurveFeature> kept = new ArrayList<>();
        boolean[] used = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) {
                continue;
            }
            CurveFeature a = sorted.get(i);
            kept.add(a);
            for (int j = i + 1; j < sorted.size(); j++) {
                if (used[j]) {
                    continue;
                }
                CurveFeature b = sorted.get(j);
                if (Math.hypot(a.x() - b.x(), a.y() - b.y()) <= dedupeM) {
                    used[j] = true;
                }
            }
        }
        return kept;
    }

    private static List<CurveFeature> merge(
            long wayId, List<Sample> sharp, Integer speed, double mergeGapM) {
        if (sharp.isEmpty()) {
            return List.of();
        }
        List<CurveFeature> out = new ArrayList<>();
        int i = 0;
        while (i < sharp.size()) {
            int j = i;
            double minR = sharp.get(i).radius;
            double sumX = 0;
            double sumY = 0;
            int n = 0;
            while (j + 1 < sharp.size()
                    && sharp.get(j + 1).alongM - sharp.get(j).alongM <= mergeGapM) {
                j++;
                minR = Math.min(minR, sharp.get(j).radius);
            }
            for (int k = i; k <= j; k++) {
                sumX += sharp.get(k).x;
                sumY += sharp.get(k).y;
                n++;
            }
            out.add(
                    new CurveFeature(
                            wayId,
                            sumX / n,
                            sumY / n,
                            minR,
                            sharp.get(i).alongM,
                            sharp.get(j).alongM,
                            speed));
            i = j + 1;
        }
        return out;
    }

    private static final class Sample {
        final double alongM;
        final double x;
        final double y;
        final double radius;

        Sample(double alongM, double x, double y, double radius) {
            this.alongM = alongM;
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }
}
