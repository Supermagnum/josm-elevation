package org.openstreetmap.josm.plugins.nvdbincline.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import no.nvdbincline.core.model.ChainKind;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.review.ReviewModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.preferences.JosmUrls;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Plugin tests against an in-memory DataSet.
 * Commands are undoable via UndoRedoHandler; nothing uploads.
 * Bootstraps JOSM preferences headlessly (no unpublished josm-tests jar).
 */
class SuggestionApplierTest {

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
    private Way way;

    @BeforeEach
    void setUp() {
        ds = new DataSet();
        Node n1 = new Node(new LatLon(62.6, 9.7));
        Node n2 = new Node(new LatLon(62.6001, 9.701));
        ds.addPrimitive(n1);
        ds.addPrimitive(n2);
        way = new Way();
        way.addNode(n1);
        way.addNode(n2);
        way.put("highway", "secondary");
        ds.addPrimitive(way);
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void onlyAcceptedRowsProduceCommands() {
        ReviewModel.Row accepted =
                new ReviewModel.Row(
                        ReviewModel.Kind.WAY_TAGS,
                        way.getUniqueId(),
                        "incline=10%",
                        MatchConfidence.HIGH,
                        Map.of(
                                "incline",
                                "10%",
                                "incline:source",
                                "nvdb_estimate",
                                "fixme",
                                "verify"),
                        null,
                        true);
        ReviewModel.Row rejected =
                new ReviewModel.Row(
                        ReviewModel.Kind.WAY_TAGS,
                        way.getUniqueId(),
                        "should not apply",
                        MatchConfidence.LOW,
                        Map.of("incline", "99%"),
                        null,
                        false);

        List<Command> cmds =
                SuggestionApplier.buildCommands(
                        ds, List.of(accepted, rejected).stream().filter(r -> r.accepted).toList());
        assertEquals(1, cmds.size());
        assertInstanceOf(ChangePropertyCommand.class, cmds.get(0));
    }

    @Test
    void applyIsUndoable() {
        ReviewModel.Row row =
                new ReviewModel.Row(
                        ReviewModel.Kind.WAY_TAGS,
                        way.getUniqueId(),
                        "incline=8%",
                        MatchConfidence.HIGH,
                        Map.of("incline", "8%", "incline:source", "nvdb_estimate"),
                        null,
                        true);
        assertFalse(way.hasKey("incline"));
        int n = SuggestionApplier.applyAccepted(ds, List.of(row));
        assertEquals(1, n);
        assertEquals("8%", way.get("incline"));
        assertEquals("nvdb_estimate", way.get("incline:source"));
        assertTrue(UndoRedoHandler.getInstance().hasUndoCommands());
        UndoRedoHandler.getInstance().undo();
        assertFalse(way.hasKey("incline"));
    }

    @Test
    void chainNodeAddsNewNode() {
        int before = ds.getNodes().size();
        ReviewModel.Row row =
                new ReviewModel.Row(
                        ReviewModel.Kind.CHAIN_NODE,
                        way.getUniqueId(),
                        "chain_advisory=fit",
                        MatchConfidence.MEDIUM,
                        Map.of("note", "test", "chain_advisory", "fit"),
                        new ChainPoint(228100, 6952200, ChainKind.FIT, "test", way.getUniqueId()),
                        true);
        List<Command> cmds = SuggestionApplier.buildCommands(ds, List.of(row));
        assertEquals(1, cmds.size());
        assertInstanceOf(AddCommand.class, cmds.get(0));
        cmds.get(0).executeCommand();
        assertEquals(before + 1, ds.getNodes().size());
    }

    @Test
    void doesNotOverwriteExistingIncline() {
        way.put("incline", "5%");
        ReviewModel.Row row =
                new ReviewModel.Row(
                        ReviewModel.Kind.WAY_TAGS,
                        way.getUniqueId(),
                        "incline=10%",
                        MatchConfidence.HIGH,
                        Map.of("incline", "10%", "incline:source", "nvdb_estimate"),
                        null,
                        true);
        SuggestionApplier.applyAccepted(ds, List.of(row));
        assertEquals("5%", way.get("incline"));
        assertEquals("nvdb_estimate", way.get("incline:source"));
    }
}
