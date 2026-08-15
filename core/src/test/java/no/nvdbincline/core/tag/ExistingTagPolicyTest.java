package no.nvdbincline.core.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import no.nvdbincline.core.tag.ExistingTagPolicy.InclineDisposition;
import no.nvdbincline.core.tag.ExistingTagPolicy.InclineOrigin;
import org.junit.jupiter.api.Test;

class ExistingTagPolicyTest {

    @Test
    void classifyThreeOrigins() {
        assertEquals(InclineOrigin.NONE, ExistingTagPolicy.classifyIncline(null, null));
        assertEquals(InclineOrigin.NONE, ExistingTagPolicy.classifyIncline("", null));
        assertEquals(
                InclineOrigin.PLUGIN_NVDB_ESTIMATE,
                ExistingTagPolicy.classifyIncline("6%", AppliedTags.INCLINE_SOURCE_VALUE));
        assertEquals(InclineOrigin.OTHER, ExistingTagPolicy.classifyIncline("6%", null));
        assertEquals(InclineOrigin.OTHER, ExistingTagPolicy.classifyIncline("6%", "survey"));
        assertEquals(InclineOrigin.OTHER, ExistingTagPolicy.classifyIncline("6%", "Bing"));
    }

    @Test
    void decideFreshUpdateDiscrepancyUnchanged() {
        assertEquals(
                InclineDisposition.FRESH,
                ExistingTagPolicy.decideIncline(InclineOrigin.NONE, null, "8%"));
        assertEquals(
                InclineDisposition.UPDATE,
                ExistingTagPolicy.decideIncline(
                        InclineOrigin.PLUGIN_NVDB_ESTIMATE, "6%", "8%"));
        assertEquals(
                InclineDisposition.UNCHANGED,
                ExistingTagPolicy.decideIncline(
                        InclineOrigin.PLUGIN_NVDB_ESTIMATE, "8%", "8%"));
        assertEquals(
                InclineDisposition.DISCREPANCY_NOTE,
                ExistingTagPolicy.decideIncline(InclineOrigin.OTHER, "6%", "8%"));
        assertEquals(
                InclineDisposition.UNCHANGED,
                ExistingTagPolicy.decideIncline(InclineOrigin.OTHER, "8%", "8%"));
    }

    @Test
    void hazardSameThreeWay() {
        assertEquals(InclineOrigin.NONE, ExistingTagPolicy.classifyHazard(null, null));
        assertEquals(
                InclineOrigin.PLUGIN_NVDB_ESTIMATE,
                ExistingTagPolicy.classifyHazard("curve", AppliedTags.HAZARD_SOURCE_VALUE));
        assertEquals(InclineOrigin.OTHER, ExistingTagPolicy.classifyHazard("curve", "survey"));
        assertEquals(
                InclineDisposition.DISCREPANCY_NOTE,
                ExistingTagPolicy.decideHazard(InclineOrigin.OTHER, "curve", "dangerous_junction"));
        assertEquals(
                InclineDisposition.UPDATE,
                ExistingTagPolicy.decideHazard(
                        InclineOrigin.PLUGIN_NVDB_ESTIMATE, "curve", "dangerous_junction"));
        assertEquals(
                InclineDisposition.UNCHANGED,
                ExistingTagPolicy.decideHazard(
                        InclineOrigin.PLUGIN_NVDB_ESTIMATE, "curve", "curve"));
    }
}

class ExistingTagCoverageTest {

    private static OsmWayGeom way(
            long id, String incline, String sourceIncline, String hazard, String sourceHazard) {
        return new OsmWayGeom(
                id,
                new Polyline(List.of(new Coord(0, 0), new Coord(100, 0))),
                "secondary",
                "W",
                null,
                incline,
                sourceIncline,
                hazard,
                sourceHazard,
                null,
                null);
    }

    @Test
    void coveragePercentagesAgainstKnownFixture() {
        // 4 ways: untagged, plugin, other (no source), other (survey)
        List<OsmWayGeom> ways =
                List.of(
                        way(1, null, null, null, null),
                        way(2, "5%", AppliedTags.INCLINE_SOURCE_VALUE, null, null),
                        way(3, "7%", null, null, null),
                        way(4, "8%", "survey", "curve", "survey"));
        ExistingTagCoverage cov = ExistingTagCoverage.scanWays(ways);
        assertEquals(4, cov.totalWays);
        assertEquals(3, cov.withIncline);
        assertEquals(1, cov.withPluginIncline);
        assertEquals(2, cov.withOtherIncline);
        assertEquals(75, cov.inclineCoveragePercent());
        assertEquals(25, cov.pluginInclinePercent());
        assertEquals(50, cov.otherInclinePercent());
        assertEquals(1, cov.withHazard);
        assertEquals(0, cov.withPluginHazard);
        assertEquals(1, cov.withOtherHazard);
        assertTrue(
                cov.formatInclineLine()
                        .contains("75% (25% previously suggested by this tool, 50% other/surveyed)"));
    }

    @Test
    void substantialOtherCoverageThreshold() {
        assertFalse(new ExistingTagCoverage(10, 5, 0, 5, 0, 0, 0, 0).suggestsSubstantialOtherCoverage());
        assertFalse(new ExistingTagCoverage(100, 20, 10, 10, 0, 0, 0, 0).suggestsSubstantialOtherCoverage());
        assertTrue(new ExistingTagCoverage(100, 40, 10, 30, 0, 0, 0, 0).suggestsSubstantialOtherCoverage());
    }
}
