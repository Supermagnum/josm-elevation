package org.openstreetmap.josm.plugins.nvdbincline.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.SplitWayCommand;
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
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Feasibility spike: {@link SplitWayCommand} is usable from plugin code against
 * an in-memory {@link DataSet}, and relation membership survives the split.
 */
class SplitWayCommandFeasibilityTest {

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
    private Node n1;
    private Node n2;
    private Node n3;
    private Way way;
    private Relation route;

    @BeforeEach
    void setUp() {
        ds = new DataSet();
        n1 = new Node(new LatLon(62.6, 9.7));
        n2 = new Node(new LatLon(62.601, 9.7));
        n3 = new Node(new LatLon(62.602, 9.7));
        ds.addPrimitive(n1);
        ds.addPrimitive(n2);
        ds.addPrimitive(n3);
        way = new Way();
        way.addNode(n1);
        way.addNode(n2);
        way.addNode(n3);
        way.put("highway", "secondary");
        way.put("name", "Feasibilityvegen");
        ds.addPrimitive(way);
        route = new Relation();
        route.put("type", "route");
        route.put("route", "road");
        route.addMember(new RelationMember("", way));
        ds.addPrimitive(route);
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void splitAtExistingNodePreservesRelationMembershipAndTags() {
        List<List<Node>> chunks = SplitWayCommand.buildSplitChunks(way, List.of(n2));
        assertNotNull(chunks);
        SplitWayCommand cmd =
                SplitWayCommand.splitWay(
                                way,
                                chunks,
                                List.of(),
                                SplitWayCommand.Strategy.keepFirstChunk(),
                                SplitWayCommand.WhenRelationOrderUncertain.SPLIT_ANYWAY)
                        .orElse(null);
        assertNotNull(cmd, "SplitWayCommand.splitWay must be usable from plugin code");
        cmd.executeCommand();

        assertEquals(2, way.getNodesCount());
        assertEquals(n1, way.firstNode());
        assertEquals(n2, way.lastNode());
        assertEquals(2, ds.getWays().size());
        Way other =
                ds.getWays().stream().filter(w -> w != way).findFirst().orElseThrow();
        assertEquals(n2, other.firstNode());
        assertEquals(n3, other.lastNode());
        assertEquals("secondary", other.get("highway"));
        assertEquals("Feasibilityvegen", other.get("name"));

        assertEquals(2, route.getMembersCount());
        assertEquals(way, route.getMember(0).getMember());
        assertEquals(other, route.getMember(1).getMember());
    }
}
