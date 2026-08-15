package org.openstreetmap.josm.plugins.nvdbincline.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.model.ChainKind;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.tag.AppliedTags;
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
import org.openstreetmap.josm.plugins.nvdbincline.NvdbInclinePreferences;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Plugin tests against an in-memory DataSet.
 * Commands are undoable via UndoRedoHandler; nothing uploads.
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
        NvdbInclinePreferences.setAutoSplitVariableGradient(false);
    }

    private static ReviewModel.Row wayRow(
            long osmId,
            String summary,
            MatchConfidence confidence,
            Map<String, String> tags,
            boolean accepted) {
        return new ReviewModel.Row(
                ReviewModel.Kind.WAY_TAGS,
                ReviewModel.Section.INCLINES,
                osmId,
                summary,
                confidence,
                tags,
                null,
                null,
                false,
                null,
                null,
                false,
                accepted);
    }

    private static ReviewModel.Row chainRow(
            long osmId, Map<String, String> tags, ChainPoint cp, boolean accepted) {
        return new ReviewModel.Row(
                ReviewModel.Kind.CHAIN_NODE,
                ReviewModel.Section.CHAINS,
                osmId,
                "chain_advisory=" + tags.getOrDefault("chain_advisory", "?"),
                MatchConfidence.MEDIUM,
                tags,
                cp,
                null,
                false,
                cp.x(),
                cp.y(),
                false,
                accepted);
    }

    @Test
    void onlyAcceptedRowsProduceCommands() {
        ReviewModel.Row accepted =
                wayRow(
                        way.getUniqueId(),
                        "incline=10%",
                        MatchConfidence.HIGH,
                        AppliedTags.incline("10%"),
                        true);
        ReviewModel.Row rejected =
                wayRow(
                        way.getUniqueId(),
                        "should not apply",
                        MatchConfidence.LOW,
                        AppliedTags.incline("99%"),
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
                wayRow(
                        way.getUniqueId(),
                        "incline=8%",
                        MatchConfidence.HIGH,
                        AppliedTags.incline("8%"),
                        true);
        assertFalse(way.hasKey("incline"));
        int n = SuggestionApplier.applyAccepted(ds, List.of(row));
        assertEquals(1, n);
        assertEquals("8%", way.get("incline"));
        assertEquals("nvdb_estimate", way.get(AppliedTags.SOURCE_INCLINE));
        assertTrue(UndoRedoHandler.getInstance().hasUndoCommands());
        UndoRedoHandler.getInstance().undo();
        assertFalse(way.hasKey("incline"));
    }

    @Test
    void appliedInclineTagsAreExactlyAllowlistedKeys() {
        Map<String, String> bloated = new java.util.LinkedHashMap<>(AppliedTags.incline("10%"));
        // If bookkeeping ever sneaks into a row map, sanitize must drop it.
        bloated.put("incline:match_confidence", "high");
        bloated.put("incline:estimated_avg", "9.6%");
        bloated.put("incline:split_recommended", "yes");
        bloated.put("incline:source", "nvdb_estimate"); // legacy suffix form

        // Row construction forbids forbidden keys — sanitize path tested via raw map filter:
        Map<String, String> sanitized =
                AppliedTags.retain(bloated, AppliedTags.WAY_INCLINE_KEYS);
        assertEquals(AppliedTags.WAY_INCLINE_KEYS, sanitized.keySet());
        assertFalse(sanitized.containsKey("incline:source"));
        assertEquals("nvdb_estimate", sanitized.get(AppliedTags.SOURCE_INCLINE));

        ReviewModel.Row row =
                wayRow(
                        way.getUniqueId(),
                        "incline=10%",
                        MatchConfidence.HIGH,
                        AppliedTags.incline("10%"),
                        true);
        SuggestionApplier.applyAccepted(ds, List.of(row));

        Set<String> pluginKeys = new LinkedHashSet<>();
        for (String k : way.keySet()) {
            if (k.startsWith("incline")
                    || k.startsWith("source:")
                    || k.equals("fixme")
                    || k.equals("note")) {
                pluginKeys.add(k);
            }
        }
        assertEquals(AppliedTags.WAY_INCLINE_KEYS, pluginKeys);
        assertFalse(way.hasKey("incline:source"));
        assertFalse(way.hasKey("incline:match_confidence"));
        assertEquals("nvdb_estimate", way.get(AppliedTags.SOURCE_INCLINE));
    }

    @Test
    void appliedHazardTagsUseSourcePrefixNotSuffix() {
        ReviewModel.Row signed =
                new ReviewModel.Row(
                        ReviewModel.Kind.CURVE_SIGNED,
                        ReviewModel.Section.CURVES_SIGNED,
                        1,
                        "signed",
                        MatchConfidence.HIGH,
                        AppliedTags.hazard("curve", "ok", "verify"),
                        null,
                        null,
                        true,
                        228100.0,
                        6952200.0,
                        false,
                        true);
        Map<String, String> kept = SuggestionApplier.sanitizeTags(signed);
        assertEquals(AppliedTags.HAZARD_KEYS, kept.keySet());
        assertEquals("nvdb_sign", kept.get(AppliedTags.SOURCE_HAZARD));
        assertFalse(kept.containsKey("hazard:source"));

        int before = ds.getNodes().size();
        List<Command> cmds = SuggestionApplier.buildCommands(ds, List.of(signed));
        assertEquals(1, cmds.size());
        cmds.get(0).executeCommand();
        assertEquals(before + 1, ds.getNodes().size());
        Node added =
                ds.getNodes().stream()
                        .filter(n -> "curve".equals(n.get("hazard")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(AppliedTags.HAZARD_KEYS, Set.copyOf(added.keySet()));
        assertEquals("nvdb_sign", added.get(AppliedTags.SOURCE_HAZARD));
        assertFalse(added.hasKey("hazard:source"));
    }

    @Test
    void chainNodeAddsNewNode() {
        int before = ds.getNodes().size();
        ReviewModel.Row row =
                chainRow(
                        way.getUniqueId(),
                        AppliedTags.chain("fit", "test"),
                        new ChainPoint(228100, 6952200, ChainKind.FIT, "test", way.getUniqueId()),
                        true);
        List<Command> cmds = SuggestionApplier.buildCommands(ds, List.of(row));
        assertEquals(1, cmds.size());
        assertInstanceOf(AddCommand.class, cmds.get(0));
        cmds.get(0).executeCommand();
        assertEquals(before + 1, ds.getNodes().size());
    }

    @Test
    void updateOverwritesPriorPluginIncline() {
        way.put("incline", "5%");
        way.put(AppliedTags.SOURCE_INCLINE, AppliedTags.INCLINE_SOURCE_VALUE);
        ReviewModel.Row row =
                wayRow(
                        way.getUniqueId(),
                        "Update: 5% → 10%",
                        MatchConfidence.HIGH,
                        AppliedTags.incline("10%"),
                        true);
        List<Command> cmds = SuggestionApplier.buildCommands(ds, List.of(row));
        assertEquals(1, cmds.size());
        cmds.get(0).executeCommand();
        assertEquals("10%", way.get("incline"));
        assertEquals(AppliedTags.INCLINE_SOURCE_VALUE, way.get(AppliedTags.SOURCE_INCLINE));
    }

    @Test
    void discrepancyRowNeverProducesCommandsOrOverwritesSurveyedIncline() {
        way.put("incline", "5%");
        way.put(AppliedTags.SOURCE_INCLINE, "survey");
        ReviewModel.Row disc =
                new ReviewModel.Row(
                        ReviewModel.Kind.DISCREPANCY,
                        ReviewModel.Section.INCLINES,
                        way.getUniqueId(),
                        "discrepancy note (not suggested): existing 5% vs NVDB 10%",
                        MatchConfidence.MEDIUM,
                        Map.of(),
                        null,
                        null,
                        false,
                        null,
                        null,
                        false,
                        true);
        // Even if mistakenly accepted, DISCREPANCY is filtered from acceptedRows and
        // allowedKeys is empty — never a silent overwrite of surveyed incline.
        assertTrue(SuggestionApplier.buildCommands(ds, List.of(disc)).isEmpty());
        ReviewModel model = new ReviewModel();
        model.rows().add(disc);
        model.acceptAll();
        assertTrue(model.acceptedRows().isEmpty());
        assertEquals("5%", way.get("incline"));
        assertEquals("survey", way.get(AppliedTags.SOURCE_INCLINE));
    }

    @Test
    void sanitizeKeepsHazardOnlyWhenSignConfirmed() {
        ReviewModel.Row unsigned =
                new ReviewModel.Row(
                        ReviewModel.Kind.CURVE_ADVISORY,
                        ReviewModel.Section.CURVES_ADVISORY,
                        1,
                        "adv",
                        MatchConfidence.LOW,
                        AppliedTags.safetyAdvisory("sharp_curve", "n"),
                        null,
                        null,
                        false,
                        1.0,
                        2.0,
                        false,
                        true);
        assertFalse(SuggestionApplier.sanitizeTags(unsigned).containsKey("hazard"));

        ReviewModel.Row signed =
                new ReviewModel.Row(
                        ReviewModel.Kind.CURVE_SIGNED,
                        ReviewModel.Section.CURVES_SIGNED,
                        1,
                        "signed",
                        MatchConfidence.HIGH,
                        AppliedTags.hazard("curve", "ok", "verify"),
                        null,
                        null,
                        true,
                        1.0,
                        2.0,
                        false,
                        true);
        Map<String, String> kept = SuggestionApplier.sanitizeTags(signed);
        assertEquals("curve", kept.get("hazard"));
        assertEquals("nvdb_sign", kept.get(AppliedTags.SOURCE_HAZARD));
    }
}
