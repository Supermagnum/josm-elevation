package org.openstreetmap.josm.plugins.nvdbincline.fixtures;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.nvdbincline.core.SuggestionEngine;
import no.nvdbincline.core.curve.CurveDetector;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.CurveFeature;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.WaySuggestion;
import no.nvdbincline.core.review.ReviewModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.preferences.JosmUrls;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.nvdbincline.io.LayerAdapter;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * One-shot dump of computed gradients against recorded fixtures.
 * Run with: {@code ./gradlew :plugin:test --tests '*.SteepRoadFixtureDumpTest'}
 * Copy printed numbers into tests/fixtures/steep_roads/README.md — do not invent them.
 */
@Tag("fixture-dump")
class SteepRoadFixtureDumpTest {

    @BeforeAll
    static void initJosmHeadless() {
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
        Config.setUrlsProvider(JosmUrls.getInstance());
        Preferences prefs = new Preferences(JosmBaseDirectories.getInstance());
        Config.setPreferencesInstance(prefs);
        prefs.init(false);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void dumpComputedGradientsAndDirections() throws Exception {
        DataSet ds = SteepRoadFixtures.loadMergedTargetWays();
        List<OsmWayGeom> ways = LayerAdapter.extractWays(ds);
        List<NvdbLink> links = SteepRoadFixtures.loadNvdbAreaLinks();
        SuggestionEngine.Output out =
                SuggestionEngine.run(ways, links, new SuggestionEngine.Config());
        Map<Long, WaySuggestion> byId =
                out.suggestions.stream()
                        .collect(Collectors.toMap(s -> s.match().way().id(), s -> s, (a, b) -> a));

        System.out.println("=== Steep-road fixture dump (" + SteepRoadFixtures.root() + ") ===");
        System.out.println("NVDB links loaded: " + links.size());
        System.out.println("OSM highways extracted: " + ways.size());
        System.out.println("Matches: " + out.matchResult.matches.size());
        System.out.println("Unmatched OSM: " + out.matchResult.unmatchedOsm.size());
        System.out.println("Chain points (clustered): " + out.chainPoints.size());

        for (long id : SteepRoadFixtures.TARGET_WAY_IDS) {
            System.out.println("--- way " + id + " ---");
            OsmWayGeom way =
                    ways.stream().filter(w -> w.id() == id).findFirst().orElse(null);
            if (way == null) {
                System.out.println("  MISSING from extracted ways");
                continue;
            }
            System.out.println(
                    "  highway="
                            + way.highway()
                            + " name="
                            + way.name()
                            + " len_m="
                            + String.format("%.1f", way.line().lengthM())
                            + " maxspeed="
                            + way.speedLimitKph());
            MatchResult m =
                    out.matchResult.matches.stream()
                            .filter(mr -> mr.way().id() == id)
                            .findFirst()
                            .orElse(null);
            if (m == null) {
                System.out.println("  NO MATCH");
                continue;
            }
            System.out.println(
                    "  match conf="
                            + m.confidence()
                            + " method="
                            + m.method()
                            + " hausdorff_m="
                            + m.hausdorffM()
                            + " links="
                            + m.links().size());
            WaySuggestion sug = byId.get(id);
            if (sug == null) {
                System.out.println("  matched but no suggestion (no elevation profile?)");
                continue;
            }
            var profile = sug.profile();
            double z0 = profile.get(0).elevationM();
            double z1 = profile.get(profile.size() - 1).elevationM();
            System.out.println(
                    "  avg_pct="
                            + String.format("%.3f", sug.stats().averagePct())
                            + " max_sustained_pct="
                            + String.format("%.3f", sug.stats().maxSustainedPct())
                            + " z_start="
                            + String.format("%.1f", z0)
                            + " z_end="
                            + String.format("%.1f", z1)
                            + " dz="
                            + String.format("%.1f", z1 - z0)
                            + " uphill_in_way_direction="
                            + (z1 > z0));
            System.out.println(
                    "  incline_tag="
                            + sug.tagsToAdd().getOrDefault(
                                    "incline", sug.tagsToAdd().get("incline:suggested"))
                            + " source="
                            + sug.tagsToAdd().get("incline:source")
                            + " fixme="
                            + (sug.tagsToAdd().containsKey("fixme")));
            System.out.println("  tags=" + sug.tagsToAdd());
        }

        List<ChainPoint> forTargets =
                out.chainPoints.stream()
                        .filter(
                                cp ->
                                        cp.wayId() != null
                                                && (cp.wayId() == 764390363L
                                                        || cp.wayId() == 757907237L
                                                        || cp.wayId() == 330233844L))
                        .toList();
        System.out.println("Chain points on target ways: " + forTargets.size());
        for (ChainPoint cp : forTargets) {
            System.out.println(
                    "  way=" + cp.wayId() + " kind=" + cp.kind() + " reason=" + cp.reason());
        }

        List<OsmWayGeom> targets =
                ways.stream()
                        .filter(
                                w ->
                                        w.id() == 764390363L
                                                || w.id() == 757907237L
                                                || w.id() == 330233844L)
                        .toList();
        List<CurveFeature> curves =
                CurveDetector.detect(targets, new CurveDetector.Settings());
        System.out.println("Curve features on targets: " + curves.size());
        for (CurveFeature c : curves) {
            System.out.println(
                    "  way="
                            + c.wayId()
                            + " R="
                            + String.format("%.1f", c.radiusM())
                            + " along=["
                            + String.format("%.1f", c.startM())
                            + ","
                            + String.format("%.1f", c.endM())
                            + "]");
        }

        ReviewModel model = ReviewModel.fromEngine(out.suggestions, out.chainPoints);
        System.out.println("Review rows: " + model.rows().size());
        for (ReviewModel.Row r : model.rows()) {
            if (r.osmId == 764390363L || r.osmId == 757907237L || r.osmId == 330233844L) {
                System.out.println(
                        "  row way="
                                + r.osmId
                                + " kind="
                                + r.kind
                                + " section="
                                + r.section
                                + " "
                                + r.summary);
            }
        }
    }
}
