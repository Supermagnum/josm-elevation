package no.nvdbincline.core.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import no.nvdbincline.core.model.NvdbPointFeature;
import org.junit.jupiter.api.Test;

class AccidentClustererTest {

    private static NvdbPointFeature accident(long id, double x, double y, String date) {
        return new NvdbPointFeature(SafetyAnalyzer.TYPE_TRAFIKKULYKKE, id, x, y, "", date, null);
    }

    @Test
    void tightClusterOverThresholdIsFlagged() {
        AccidentClusterer.Settings s = new AccidentClusterer.Settings();
        s.clusterRadiusM = 50;
        s.minCount = 3;
        s.lookbackYears = 20;
        List<AccidentClusterer.Cluster> clusters =
                AccidentClusterer.cluster(
                        List.of(
                                accident(1, 100, 100, "2020-01-01"),
                                accident(2, 110, 105, "2021-06-15"),
                                accident(3, 95, 98, "2022-03-20"),
                                accident(4, 102, 101, "2023-11-01")),
                        s);
        assertEquals(1, clusters.size());
        assertEquals(4, clusters.get(0).count);
        assertEquals("2020-01-01", clusters.get(0).dateFrom);
        assertEquals("2023-11-01", clusters.get(0).dateTo);
    }

    @Test
    void scatteredIsolatedAccidentsUnderThresholdNotFlagged() {
        AccidentClusterer.Settings s = new AccidentClusterer.Settings();
        s.clusterRadiusM = 50;
        s.minCount = 3;
        s.lookbackYears = 20;
        List<AccidentClusterer.Cluster> clusters =
                AccidentClusterer.cluster(
                        List.of(
                                accident(1, 0, 0, "2020-01-01"),
                                accident(2, 500, 0, "2021-01-01"),
                                accident(3, 0, 500, "2022-01-01"),
                                accident(4, 1000, 1000, "2023-01-01")),
                        s);
        assertTrue(clusters.isEmpty());
    }

    @Test
    void noteTextFromAnalyzerReportsCountAndPeriod() {
        AccidentClusterer.Settings ac = new AccidentClusterer.Settings();
        ac.minCount = 3;
        ac.lookbackYears = 20;
        SafetyAnalyzer.Settings settings = new SafetyAnalyzer.Settings();
        settings.accidents = ac;
        var findings =
                SafetyAnalyzer.analyze(
                        List.of(),
                        List.of(),
                        List.of(
                                accident(1, 10, 10, "2019-05-01"),
                                accident(2, 12, 11, "2020-08-01"),
                                accident(3, 8, 9, "2021-12-01")),
                        settings);
        assertEquals(1, findings.size());
        var f = findings.get(0);
        assertEquals(3, f.accidentCount());
        assertTrue(f.summary().contains("n=3"));
        assertTrue(f.tags().get("note").contains("3 ulykker"));
        assertTrue(f.tags().get("note").contains("2019-05-01"));
        assertTrue(f.tags().get("note").contains("2021-12-01"));
        assertTrue(f.tags().get("note").contains("type 570"));
        assertFalseHazard(f);
    }

    private static void assertFalseHazard(no.nvdbincline.core.model.SafetyFinding f) {
        org.junit.jupiter.api.Assertions.assertFalse(f.signConfirmed());
        org.junit.jupiter.api.Assertions.assertFalse(f.tags().containsKey("hazard"));
    }
}
