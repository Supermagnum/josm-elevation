package no.nvdbincline.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.NvdbPointFeature;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import no.nvdbincline.core.model.SafetyFinding;
import org.junit.jupiter.api.Test;

class SafetyAnalyzerTest {

    private static List<Coord> hairpin() {
        List<Coord> pts = new ArrayList<>();
        double r = 25;
        for (int i = 0; i <= 24; i++) {
            double a = Math.toRadians(i * 7.5);
            pts.add(new Coord(1000 + r * Math.sin(a), 2000 + r * (1 - Math.cos(a))));
        }
        Coord last = pts.get(pts.size() - 1);
        for (int i = 1; i <= 5; i++) {
            pts.add(new Coord(last.x() - i * 15, last.y()));
        }
        return pts;
    }

    private static OsmWayGeom hairpinWay() {
        return new OsmWayGeom(
                42, new Polyline(hairpin()), "secondary", "T", null, null, 80);
    }

    @Test
    void curveNearKnownSignUpgradesToHighConfidenceHazard() {
        List<Coord> pts = hairpin();
        Coord mid = pts.get(pts.size() / 2);
        NvdbPointFeature sign =
                new NvdbPointFeature(
                        SafetyAnalyzer.TYPE_SKILTPLATE,
                        1,
                        mid.x() + 5,
                        mid.y() + 5,
                        "100.1",
                        null,
                        "100.1");
        SafetyAnalyzer.Settings settings = new SafetyAnalyzer.Settings();
        settings.curve.minChordM = 5;
        List<SafetyFinding> findings =
                SafetyAnalyzer.analyze(
                        List.of(hairpinWay()), List.of(sign), List.of(), settings);
        SafetyFinding signed =
                findings.stream()
                        .filter(f -> f.kind() == SafetyFinding.Kind.CURVE_SIGNED)
                        .findFirst()
                        .orElseThrow();
        assertTrue(signed.signConfirmed());
        assertEquals(MatchConfidence.HIGH, signed.confidence());
        assertEquals("curve", signed.tags().get("hazard"));
        assertEquals("nvdb_sign", signed.tags().get("source:hazard"));
        assertFalse(signed.tags().containsKey("hazard:source"));
        assertEquals(
                no.nvdbincline.core.tag.AppliedTags.HAZARD_KEYS, signed.tags().keySet());
    }

    @Test
    void curveWithoutSignStaysAdvisoryOnly() {
        SafetyAnalyzer.Settings settings = new SafetyAnalyzer.Settings();
        settings.curve.minChordM = 5;
        List<SafetyFinding> findings =
                SafetyAnalyzer.analyze(
                        List.of(hairpinWay()), List.of(), List.of(), settings);
        assertFalse(findings.isEmpty());
        for (SafetyFinding f : findings) {
            assertEquals(SafetyFinding.Kind.CURVE_ADVISORY, f.kind());
            assertFalse(f.signConfirmed());
            assertFalse(f.tags().containsKey("hazard"));
            assertEquals("sharp_curve", f.tags().get("safety_advisory"));
            assertEquals(MatchConfidence.LOW, f.confidence());
        }
    }
}
