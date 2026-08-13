package no.nvdbincline.core.gradient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import no.nvdbincline.core.model.ElevationSample;
import no.nvdbincline.core.tag.InclineTags;
import org.junit.jupiter.api.Test;

class GradientCalculatorTest {

    private static List<ElevationSample> profile(double... dzPairs) {
        List<ElevationSample> out = new ArrayList<>();
        for (int i = 0; i + 1 < dzPairs.length; i += 2) {
            double d = dzPairs[i];
            double z = dzPairs[i + 1];
            out.add(new ElevationSample(d, z, d, 0));
        }
        return out;
    }

    private static List<ElevationSample> constantSlope(double lengthM, double gradePct) {
        int n = Math.max(2, (int) (lengthM / 10) + 1);
        List<ElevationSample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double d = i * (lengthM / (n - 1));
            out.add(new ElevationSample(d, d * gradePct / 100.0, d, 0));
        }
        return out;
    }

    @Test
    void flatIsZero() {
        var p = profile(0, 100, 100, 100, 200, 100);
        assertEquals(0.0, GradientCalculator.averagePct(p), 1e-9);
        assertEquals("0%", InclineTags.formatIncline(GradientCalculator.averagePct(p)));
    }

    @Test
    void constantPositive() {
        var p = constantSlope(200, 10);
        assertEquals(10.0, GradientCalculator.averagePct(p), 0.05);
        assertEquals("10%", InclineTags.formatIncline(GradientCalculator.averagePct(p)));
    }

    @Test
    void constantNegativeRespectsDirection() {
        var p = constantSlope(200, -8);
        assertEquals(-8.0, GradientCalculator.averagePct(p), 0.05);
        assertEquals("-8%", InclineTags.formatIncline(GradientCalculator.averagePct(p)));
    }

    @Test
    void spikeShowsInWindowNotAverage() {
        var p = profile(0, 100, 40, 100, 60, 104, 80, 104, 200, 104);
        assertEquals(2.0, GradientCalculator.averagePct(p), 0.2);
        assertTrue(GradientCalculator.maxSustainedPct(p, 20) >= 15.0);
    }

    @Test
    void roundingEdges() {
        assertEquals(0, InclineTags.roundPct(0.0));
        assertEquals(0, InclineTags.roundPct(0.4));
        assertEquals(1, InclineTags.roundPct(0.5));
        assertEquals(-1, InclineTags.roundPct(-0.5));
        assertEquals(20, InclineTags.roundPct(20.4));
        assertEquals(21, InclineTags.roundPct(20.6));
        assertEquals("-3%", InclineTags.formatIncline(-3.2));
        assertEquals("25%", InclineTags.formatIncline(25.0));
    }

    @Test
    void splitWhenSpreadExceedsThreshold() {
        List<ElevationSample> p = new ArrayList<>();
        for (int d = 0; d <= 300; d += 10) {
            double z = 100.0 + (d < 150 ? 0.0 : (d - 150) * 0.10);
            p.add(new ElevationSample(d, z, d, 0));
        }
        GradientCalculator.SplitConfig cfg = new GradientCalculator.SplitConfig();
        cfg.windowM = 50;
        cfg.spreadPp = 4;
        cfg.minSegmentM = 40;
        cfg.mergePp = 2;
        var result = GradientCalculator.suggestSegments(p, cfg);
        assertTrue(result.split);
        assertTrue(result.segments.size() >= 2);
        assertTrue(
                Math.abs(result.segments.get(result.segments.size() - 1).averagePct())
                        > Math.abs(result.segments.get(0).averagePct()) + 2.0);
    }

    @Test
    void noSplitForUniformSlope() {
        var p = constantSlope(300, 7);
        var result = GradientCalculator.suggestSegments(p, new GradientCalculator.SplitConfig());
        assertFalse(result.split);
        assertEquals(1, result.segments.size());
        assertEquals("7%", result.segments.get(0).inclineTag());
    }
}
