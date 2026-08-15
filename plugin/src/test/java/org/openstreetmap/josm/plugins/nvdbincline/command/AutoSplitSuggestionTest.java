package org.openstreetmap.josm.plugins.nvdbincline.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.SegmentSuggestion;
import no.nvdbincline.core.review.InclineAudit;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.tag.AppliedTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.preferences.JosmUrls;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.nvdbincline.NvdbInclinePreferences;
import org.openstreetmap.josm.plugins.nvdbincline.dialog.ReviewDialog;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Opt-in automatic way-splitting: default off, multi-segment split, relation
 * membership, warning label, and single-undo restore.
 */
class AutoSplitSuggestionTest {

    @BeforeAll
    static void initJosmHeadless() {
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
        Config.setUrlsProvider(JosmUrls.getInstance());
        Preferences prefs = new Preferences(JosmBaseDirectories.getInstance());
        Config.setPreferencesInstance(prefs);
        prefs.init(false);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    private DataSet ds;
    private Node start;
    private Node end;
    private Way way;
    private double[] startUtm;
    private double[] endUtm;

    @BeforeEach
    void setUp() {
        NvdbInclinePreferences.setAutoSplitVariableGradient(false);
        ds = new DataSet();
        // ~300 m north-south so two interior splits have room.
        start = new Node(new LatLon(62.6, 9.7));
        end = new Node(new LatLon(62.6027, 9.7));
        ds.addPrimitive(start);
        ds.addPrimitive(end);
        way = new Way();
        way.addNode(start);
        way.addNode(end);
        way.put("highway", "secondary");
        way.put("name", "Testvegen");
        way.put("ref", "Rv27");
        ds.addPrimitive(way);
        startUtm = Utm33.lonLatToUtm(start.getCoor().lon(), start.getCoor().lat());
        endUtm = Utm33.lonLatToUtm(end.getCoor().lon(), end.getCoor().lat());
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void preferenceDefaultsToOff() {
        NvdbInclinePreferences.setAutoSplitVariableGradient(false);
        assertFalse(NvdbInclinePreferences.autoSplitVariableGradient());
    }

    @Test
    void settingOffNeverIssuesSplitWayCommand() {
        ReviewModel.Row row = threeSegmentRow(true);
        List<Command> cmds = SuggestionApplier.buildCommands(ds, List.of(row), false);
        assertFalse(SuggestionApplier.containsSplitWayCommand(cmds));
        assertEquals(1, cmds.size());
        SuggestionApplier.applyAccepted(ds, List.of(row), false);
        assertEquals(1, ds.getWays().size());
        assertEquals(2, way.getNodesCount());
        assertEquals("6%", way.get("incline"));
        assertFalse(way.hasKey("incline:split_recommended"));
    }

    @Test
    void settingOnSplitsIntoThreeWaysWithPerSegmentInclineAndCopiedTags() {
        ReviewModel.Row row = threeSegmentRow(true);
        List<Command> cmds = SuggestionApplier.buildCommands(ds, List.of(row), true);
        assertTrue(SuggestionApplier.containsSplitWayCommand(cmds));
        int n = SuggestionApplier.applyAccepted(ds, List.of(row), true);
        assertEquals(1, n);
        assertEquals(3, ds.getWays().size(), "two split points -> three sub-ways");

        List<Way> ways = new ArrayList<>(ds.getWays());
        Set<String> inclines =
                ways.stream().map(w -> w.get("incline")).collect(Collectors.toSet());
        assertEquals(Set.of("2%", "8%", "12%"), inclines);
        for (Way w : ways) {
            assertEquals("secondary", w.get("highway"));
            assertEquals("Testvegen", w.get("name"));
            assertEquals("Rv27", w.get("ref"));
            assertEquals("nvdb_estimate", w.get(AppliedTags.SOURCE_INCLINE));
            assertFalse(w.hasKey("incline:split_recommended"));
            assertFalse(w.hasKey("incline:suggested_segments"));
            assertEquals(AppliedTags.WAY_INCLINE_KEYS.size() + 3, w.keySet().size());
        }
        assertEquals("2%", way.get("incline"));
    }

    @Test
    void multipleSplitPointsAllHappenNotJustTheFirst() {
        ReviewModel.Row row = threeSegmentRow(true);
        SuggestionApplier.applyAccepted(ds, List.of(row), true);
        assertEquals(3, ds.getWays().size());
        long with12 =
                ds.getWays().stream().filter(w -> "12%".equals(w.get("incline"))).count();
        assertEquals(1, with12);
    }

    @Test
    void relationMembershipUpdatedInOrderAndWarningIsShown() {
        Relation route = new Relation();
        route.put("type", "route");
        route.addMember(new RelationMember("", way));
        ds.addPrimitive(route);

        ReviewModel.Row row = threeSegmentRow(true);
        assertEquals(1, SuggestionApplier.splitWaysInRelations(ds, List.of(row)).size());
        assertEquals(
                "Auto-split (relation)",
                ReviewDialog.splitColumnLabel(row, true, Set.of(way.getUniqueId())));
        assertEquals(
                "Split suggested",
                ReviewDialog.splitColumnLabel(row, false, Set.of(way.getUniqueId())));

        SuggestionApplier.applyAccepted(ds, List.of(row), true);
        assertEquals(3, route.getMembersCount());
        assertEquals(way, route.getMember(0).getWay());
        assertEquals(start, route.getMember(0).getWay().firstNode());
        Way last = route.getMember(2).getWay();
        assertEquals(end, last.lastNode());
        for (int i = 0; i < 2; i++) {
            Way a = route.getMember(i).getWay();
            Way b = route.getMember(i + 1).getWay();
            assertEquals(a.lastNode(), b.firstNode());
        }
    }

    @Test
    void singleUndoRestoresUnsplitWayAndOriginalTags() {
        Relation route = new Relation();
        route.put("type", "route");
        route.addMember(new RelationMember("", way));
        ds.addPrimitive(route);

        ReviewModel.Row row = threeSegmentRow(true);
        SuggestionApplier.applyAccepted(ds, List.of(row), true);
        assertEquals(3, ds.getWays().size());
        assertTrue(way.hasKey("incline"));

        assertTrue(UndoRedoHandler.getInstance().hasUndoCommands());
        UndoRedoHandler.getInstance().undo();

        assertEquals(1, ds.getWays().size());
        assertEquals(way, ds.getWays().iterator().next());
        assertEquals(2, way.getNodesCount());
        assertEquals(start, way.firstNode());
        assertEquals(end, way.lastNode());
        assertFalse(way.hasKey("incline"));
        assertFalse(way.hasKey(AppliedTags.SOURCE_INCLINE));
        assertEquals("Testvegen", way.get("name"));
        assertEquals("secondary", way.get("highway"));
        assertEquals(1, route.getMembersCount());
        assertEquals(way, route.getMember(0).getMember());
    }

    private ReviewModel.Row threeSegmentRow(boolean accepted) {
        Coord a = new Coord(startUtm[0], startUtm[1]);
        Coord b =
                new Coord(
                        startUtm[0] + (endUtm[0] - startUtm[0]) / 3.0,
                        startUtm[1] + (endUtm[1] - startUtm[1]) / 3.0);
        Coord c =
                new Coord(
                        startUtm[0] + 2.0 * (endUtm[0] - startUtm[0]) / 3.0,
                        startUtm[1] + 2.0 * (endUtm[1] - startUtm[1]) / 3.0);
        Coord d = new Coord(endUtm[0], endUtm[1]);
        List<SegmentSuggestion> segs =
                List.of(
                        new SegmentSuggestion(0, 100, 2, 2, "2%", a, b),
                        new SegmentSuggestion(100, 200, 8, 8, "8%", b, c),
                        new SegmentSuggestion(200, 300, 12, 12, "12%", c, d));
        InclineAudit audit =
                new InclineAudit(
                        "geometry",
                        MatchConfidence.HIGH,
                        1.0,
                        6.0,
                        12.0,
                        "6%",
                        "2%;8%;12%",
                        true,
                        segs);
        return new ReviewModel.Row(
                ReviewModel.Kind.WAY_TAGS,
                ReviewModel.Section.INCLINES,
                way.getUniqueId(),
                "incline=6% — split suggested",
                MatchConfidence.HIGH,
                AppliedTags.incline("6%"),
                null,
                null,
                false,
                null,
                null,
                true,
                audit,
                accepted);
    }
}
