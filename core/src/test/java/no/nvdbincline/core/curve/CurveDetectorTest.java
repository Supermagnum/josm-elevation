package no.nvdbincline.core.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.CurveFeature;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import org.junit.jupiter.api.Test;

class CurveDetectorTest {

    private static OsmWayGeom way(long id, List<Coord> pts, Integer speed) {
        return new OsmWayGeom(id, new Polyline(pts), "secondary", "T", null, null, speed);
    }

    private static List<Coord> straight() {
        List<Coord> pts = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            pts.add(new Coord(i * 20.0, 0));
        }
        return pts;
    }

    /** Arc of large radius (~200 m) — gentle, above default threshold. */
    private static List<Coord> gentleArc() {
        List<Coord> pts = new ArrayList<>();
        double r = 200;
        for (int i = 0; i <= 20; i++) {
            double a = Math.toRadians(-20 + i * 2.0);
            pts.add(new Coord(r * Math.sin(a), r * (1 - Math.cos(a))));
        }
        return pts;
    }

    /** Tight hairpin (~25 m radius) — should flag under typical thresholds. */
    private static List<Coord> hairpin() {
        List<Coord> pts = new ArrayList<>();
        double r = 25;
        for (int i = 0; i <= 24; i++) {
            double a = Math.toRadians(i * 7.5); // 180 deg turn
            pts.add(new Coord(r * Math.sin(a), r * (1 - Math.cos(a))));
        }
        // Exit tangent
        Coord last = pts.get(pts.size() - 1);
        for (int i = 1; i <= 5; i++) {
            pts.add(new Coord(last.x() - i * 15, last.y()));
        }
        return pts;
    }

    @Test
    void straightLineNotFlagged() {
        CurveDetector.Settings s = new CurveDetector.Settings();
        List<CurveFeature> f = CurveDetector.detectWay(way(1, straight(), 80), s);
        assertTrue(f.isEmpty());
    }

    @Test
    void gentleCurveNotFlaggedBelowThreshold() {
        CurveDetector.Settings s = new CurveDetector.Settings();
        s.defaultRadiusThresholdM = 75;
        List<CurveFeature> f = CurveDetector.detectWay(way(2, gentleArc(), 80), s);
        assertTrue(f.isEmpty(), "gentle R≈200 should not flag under 75m threshold");
    }

    @Test
    void hairpinIsFlagged() {
        CurveDetector.Settings s = new CurveDetector.Settings();
        s.defaultRadiusThresholdM = 75;
        s.minChordM = 5;
        List<CurveFeature> f = CurveDetector.detectWay(way(3, hairpin(), 80), s);
        assertTrue(f.size() >= 1, "hairpin should produce at least one curve feature");
        assertTrue(f.get(0).radiusM() < 75);
    }

    @Test
    void adjacentSharpSamplesMergedIntoOneFeature() {
        // Dense sampling on a continuous tight arc → many sharp samples, one merged feature.
        List<Coord> pts = new ArrayList<>();
        double r = 30;
        for (int i = 0; i <= 40; i++) {
            double a = Math.toRadians(i * 4.5);
            pts.add(new Coord(r * Math.sin(a), r * (1 - Math.cos(a))));
        }
        CurveDetector.Settings s = new CurveDetector.Settings();
        s.defaultRadiusThresholdM = 75;
        s.minChordM = 4;
        s.mergeGapM = 40;
        s.minArcSpanM = 0;
        s.minMeaningfulRadiusM = 1;
        // null speed → use defaultRadiusThresholdM (not the speed table)
        List<CurveFeature> f = CurveDetector.detectWay(way(4, pts, null), s);
        assertEquals(1, f.size(), "adjacent sharp samples must merge into one feature");
    }

    @Test
    void trackHighwaySkippedByAllowlist() {
        CurveDetector.Settings s = new CurveDetector.Settings();
        s.defaultRadiusThresholdM = 75;
        s.minChordM = 5;
        OsmWayGeom track =
                new OsmWayGeom(9, new Polyline(hairpin()), "track", "T", null, null, 40);
        assertTrue(CurveDetector.detectWay(track, s).isEmpty());
    }
}
