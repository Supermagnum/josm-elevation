package org.openstreetmap.josm.plugins.nvdbincline.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import no.nvdbincline.core.SuggestionEngine;
import no.nvdbincline.core.curve.CurveDetector;
import no.nvdbincline.core.model.CurveFeature;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.review.ReviewModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.preferences.JosmUrls;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.nvdbincline.io.LayerAdapter;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Offline integration tests against recorded Innlandet steep-road fixtures.
 * No live network — see {@code tests/fixtures/steep_roads/README.md}.
 */
class SteepRoadFixtureIT {

    /** Recorded 2026-08-14 from SteepRoadFixtureDumpTest against captured fixtures. */
    private static final Map<Long, Expected> EXPECTED =
            Map.of(
                    764390363L, new Expected(9.576, +1, "Venabygdsvegen"),
                    757907237L, new Expected(5.656, +1, "Friisvegen"),
                    330233844L, new Expected(-6.374, -1, "Kilevegen"));

    private static final class Expected {
        final double avgPct;
        final int sign;
        final String name;

        Expected(double avgPct, int sign, String name) {
            this.avgPct = avgPct;
            this.sign = sign;
            this.name = name;
        }
    }

    private static List<OsmWayGeom> targetWays;
    private static List<NvdbLink> nvdbLinks;
    private static SuggestionEngine.Output engineOut;
    private static Map<Long, WaySuggestion> suggestionsById;
    private static Map<Long, MatchResult> matchesById;

    @BeforeAll
    static void init() throws Exception {
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
        Config.setUrlsProvider(JosmUrls.getInstance());
        Preferences prefs = new Preferences(JosmBaseDirectories.getInstance());
        Config.setPreferencesInstance(prefs);
        prefs.init(false);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));

        DataSet ds = SteepRoadFixtures.loadMergedTargetWays();
        targetWays =
                LayerAdapter.extractWays(ds).stream()
                        .filter(w -> EXPECTED.containsKey(w.id()))
                        .toList();
        assertEquals(3, targetWays.size(), "expected three target highways in OSM fixtures");
        nvdbLinks = SteepRoadFixtures.loadNvdbAreaLinks();
        assertTrue(nvdbLinks.size() > 100, "NVDB area fixture too small: " + nvdbLinks.size());
        engineOut = SuggestionEngine.run(targetWays, nvdbLinks, new SuggestionEngine.Config());
        suggestionsById =
                engineOut.suggestions.stream()
                        .collect(Collectors.toMap(s -> s.match().way().id(), s -> s, (a, b) -> a));
        matchesById =
                engineOut.matchResult.matches.stream()
                        .collect(Collectors.toMap(m -> m.way().id(), m -> m, (a, b) -> a));
    }

    @Test
    void allThreeWaysMatchWithUsableConfidence() {
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            MatchResult m = matchesById.get(id);
            assertNotNull(m, "way " + id + " must match an NVDB link");
            assertTrue(
                    m.confidence() == MatchConfidence.HIGH
                            || m.confidence() == MatchConfidence.MEDIUM,
                    "way "
                            + id
                            + " confidence must be HIGH/MEDIUM (usable), got "
                            + m.confidence()
                            + " method="
                            + m.method());
        }
    }

    @Test
    void gradientWithinRecordedTolerance() {
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            WaySuggestion sug = suggestionsById.get(id);
            assertNotNull(sug, "way " + id + " missing suggestion");
            Expected exp = EXPECTED.get(id);
            double avg = sug.stats().averagePct();
            assertTrue(
                    Math.abs(avg) > 4.0,
                    "way " + id + " (" + exp.name + ") should be steep (>4% abs), got " + avg);
            assertEquals(
                    exp.avgPct,
                    avg,
                    1.5,
                    "way "
                            + id
                            + " avg gradient regression (recorded "
                            + exp.avgPct
                            + "% ±1.5)");
            assertEquals(
                    exp.sign,
                    avg > 0 ? 1 : (avg < 0 ? -1 : 0),
                    "way " + id + " incline sign vs way node direction");
            // Stronger sign check from elevation ends
            var profile = sug.profile();
            double dz =
                    profile.get(profile.size() - 1).elevationM() - profile.get(0).elevationM();
            assertEquals(exp.sign, dz > 0 ? 1 : (dz < 0 ? -1 : 0));
        }
    }

    @Test
    void inclineTagsProposedWithSourceAndFixme() {
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            WaySuggestion sug = suggestionsById.get(id);
            assertNotNull(sug);
            assertTrue(sug.isApplicable());
            Map<String, String> tags = sug.tagsToAdd();
            assertEquals("nvdb_estimate", tags.get("incline:source"));
            assertTrue(tags.containsKey("fixme"), "missing fixme for way " + id);
            assertTrue(
                    tags.containsKey("incline") || tags.containsKey("incline:suggested"),
                    "missing incline proposal for way " + id + ": " + tags.keySet());
            // Sign of average must match recorded direction (tag may be split into segments).
            Expected exp = EXPECTED.get(id);
            double avg = sug.stats().averagePct();
            assertEquals(exp.sign, avg > 0 ? 1 : (avg < 0 ? -1 : 0));
        }
    }

    @Test
    void atLeastOneSnowChainAdvisoryOnTargets() {
        long onTargets =
                engineOut.chainPoints.stream()
                        .filter(cp -> cp.wayId() != null && EXPECTED.containsKey(cp.wayId()))
                        .count();
        assertTrue(
                onTargets >= 1,
                "expected ≥1 snow-chain advisory on these steep roads; got "
                        + onTargets
                        + " (threshold miscalibration or heuristic bug)");
    }

    @Test
    void curveDetectorSmokeOnTargets() {
        List<CurveFeature> curves =
                CurveDetector.detect(targetWays, new CurveDetector.Settings());
        Map<Long, Double> lengths =
                targetWays.stream()
                        .collect(Collectors.toMap(OsmWayGeom::id, w -> w.line().lengthM()));
        for (CurveFeature c : curves) {
            assertFalse(Double.isNaN(c.radiusM()) || Double.isInfinite(c.radiusM()));
            assertFalse(Double.isNaN(c.startM()) || Double.isNaN(c.endM()));
            assertTrue(c.startM() >= -1e-6, "startM out of range: " + c.startM());
            assertTrue(c.endM() + 1e-6 >= c.startM());
            Double len = lengths.get(c.wayId());
            assertNotNull(len);
            assertTrue(
                    c.endM() <= len + 1.0,
                    "endM " + c.endM() + " beyond way length " + len + " for way " + c.wayId());
            // Normalized position in [0,1] for reviewability
            double fra = c.startM() / len;
            double til = c.endM() / len;
            assertTrue(fra >= -1e-6 && fra <= 1.0 + 1e-6, "fra_posisjon=" + fra);
            assertTrue(til >= -1e-6 && til <= 1.0 + 1e-6, "til_posisjon=" + til);
        }
    }

    @Test
    void reviewModelContainsAllThreeWays() {
        ReviewModel model =
                ReviewModel.fromEngine(engineOut.suggestions, engineOut.chainPoints);
        Set<Long> seen = new HashSet<>();
        for (ReviewModel.Row row : model.rows()) {
            if (EXPECTED.containsKey(row.osmId)) {
                seen.add(row.osmId);
            }
        }
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            assertTrue(
                    seen.contains(id),
                    "way " + id + " missing from review model (silently dropped?)");
        }
        // Incline section rows for all three
        Set<Long> inclineWays =
                model.rows().stream()
                        .filter(r -> r.kind == ReviewModel.Kind.WAY_TAGS)
                        .map(r -> r.osmId)
                        .collect(Collectors.toSet());
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            assertTrue(inclineWays.contains(id), "way " + id + " missing from incline section");
        }
    }

    @Test
    void areaOsmFixtureContainsAllThreeWays() throws Exception {
        DataSet area = SteepRoadFixtures.loadOsm("area.osm");
        Set<Long> ids = new HashSet<>();
        for (Way w : area.getWays()) {
            if (w.getOsmId() > 0) {
                ids.add(w.getOsmId());
            } else {
                ids.add(w.getUniqueId());
            }
        }
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            assertTrue(ids.contains(id), "area.osm missing way " + id);
        }
        // Pipeline on targets extracted from the area file (not only per-way saves)
        List<OsmWayGeom> fromArea =
                LayerAdapter.extractWays(area).stream()
                        .filter(w -> EXPECTED.containsKey(w.id()))
                        .toList();
        assertEquals(3, fromArea.size());
        SuggestionEngine.Output out =
                SuggestionEngine.run(fromArea, nvdbLinks, new SuggestionEngine.Config());
        assertEquals(3, out.matchResult.matches.size());
        ReviewModel model = ReviewModel.fromEngine(out.suggestions, out.chainPoints);
        Set<Long> inReview =
                model.rows().stream().map(r -> r.osmId).collect(Collectors.toSet());
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            assertTrue(inReview.contains(id), "area pipeline dropped way " + id);
        }
    }

    @Test
    void venabygdsvegenHasHighestAbsAverage_kilevegenLargestDrop() {
        Map<Long, Double> absAvg = new HashMap<>();
        Map<Long, Double> absDz = new HashMap<>();
        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            WaySuggestion sug = suggestionsById.get(id);
            absAvg.put(id, Math.abs(sug.stats().averagePct()));
            var p = sug.profile();
            absDz.put(
                    id,
                    Math.abs(
                            p.get(p.size() - 1).elevationM() - p.get(0).elevationM()));
        }
        long steepestAvg =
                absAvg.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .orElseThrow()
                        .getKey();
        long largestDrop =
                absDz.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .orElseThrow()
                        .getKey();
        // Recorded 2026-08-14: Venabygdsvegen ~9.6% avg; Kilevegen ~455 m drop over 7 km.
        assertEquals(764390363L, steepestAvg, "absAvgs=" + absAvg);
        assertEquals(330233844L, largestDrop, "absDz=" + absDz);
    }
}
