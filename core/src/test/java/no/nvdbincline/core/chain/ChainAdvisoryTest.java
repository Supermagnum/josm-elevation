package no.nvdbincline.core.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.nvdbincline.core.model.ChainKind;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.ElevationSample;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.Polyline;
import org.junit.jupiter.api.Test;

class ChainAdvisoryTest {

    private static List<ElevationSample> flat(double length) {
        return List.of(
                new ElevationSample(0, 100, 0, 0),
                new ElevationSample(length / 2, 100, length / 2, 0),
                new ElevationSample(length, 100, length, 0));
    }

    private static List<ElevationSample> constant(double length, double grade) {
        List<ElevationSample> out = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            double d = i * (length / 30.0);
            out.add(new ElevationSample(d, d * grade / 100.0, d, 0));
        }
        return out;
    }

    @Test
    void flatTriggersNothing() {
        var pts =
                ChainAdvisory.advise(flat(400), 1L, List.of(), new ChainAdvisory.Settings());
        assertTrue(pts.isEmpty());
    }

    @Test
    void mountainPassTriggersFitAndRemove() {
        List<ElevationSample> p = new ArrayList<>();
        for (int d = 0; d <= 500; d += 25) {
            double z = d <= 250 ? 200 + d * 0.12 : 200 + 30 - (d - 250) * 0.12;
            p.add(new ElevationSample(d, z, d, 0));
        }
        ChainAdvisory.Settings s = new ChainAdvisory.Settings();
        s.chainGradientPct = 6;
        s.chainMinDistanceM = 200;
        var pts = ChainAdvisory.advise(p, 42L, List.of(), s);
        Set<ChainKind> kinds = new HashSet<>();
        for (ChainPoint pt : pts) {
            kinds.add(pt.kind());
        }
        assertTrue(kinds.contains(ChainKind.FIT));
        assertTrue(kinds.contains(ChainKind.REMOVE));
    }

    @Test
    void sustainedClimbFit() {
        ChainAdvisory.Settings s = new ChainAdvisory.Settings();
        s.chainGradientPct = 6;
        s.chainMinDistanceM = 200;
        var pts = ChainAdvisory.advise(constant(300, 8), 7L, List.of(), s);
        assertTrue(pts.stream().anyMatch(p -> p.kind() == ChainKind.FIT));
    }

    @Test
    void clusterDedupes() {
        var clustered =
                ChainAdvisory.cluster(
                        List.of(
                                new ChainPoint(0, 0, ChainKind.FIT, "a", null),
                                new ChainPoint(10, 0, ChainKind.FIT, "b", null),
                                new ChainPoint(200, 0, ChainKind.REMOVE, "c", null)),
                        50);
        assertEquals(2, clustered.size());
    }

    @Test
    void clusterKeepsFitAndRemoveSeparateWhenNearby() {
        var clustered =
                ChainAdvisory.cluster(
                        List.of(
                                new ChainPoint(0, 0, ChainKind.FIT, "climb", null),
                                new ChainPoint(5, 0, ChainKind.REMOVE, "descent", null)),
                        50);
        assertEquals(2, clustered.size());
        Set<ChainKind> kinds = new HashSet<>();
        for (ChainPoint p : clustered) {
            kinds.add(p.kind());
            assertFalse(p.kind() == ChainKind.FIT_REMOVE);
        }
        assertTrue(kinds.contains(ChainKind.FIT));
        assertTrue(kinds.contains(ChainKind.REMOVE));
    }

    @Test
    void tunnelPortal() {
        var link =
                new NvdbLink(
                        1,
                        "0-1@1",
                        "HOVED",
                        "Tunnel",
                        "T",
                        0,
                        1,
                        new Polyline(List.of(new Coord(0, 0, 10), new Coord(100, 0, 10))));
        var pts = ChainAdvisory.advise(flat(100), 1L, List.of(link), new ChainAdvisory.Settings());
        assertFalse(pts.isEmpty());
        assertTrue(pts.stream().anyMatch(p -> p.reason().toLowerCase().contains("tunnel")));
    }
}
