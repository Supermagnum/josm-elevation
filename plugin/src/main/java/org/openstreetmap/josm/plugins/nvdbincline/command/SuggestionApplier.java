package org.openstreetmap.josm.plugins.nvdbincline.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.review.ReviewModel;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Turns accepted review rows into ordinary JOSM Commands and registers them
 * with UndoRedoHandler. Never uploads.
 */
public final class SuggestionApplier {
    private SuggestionApplier() {}

    /**
     * @return number of accepted rows that produced commands
     */
    public static int applyAccepted(DataSet ds, List<ReviewModel.Row> accepted) {
        if (accepted == null || accepted.isEmpty()) {
            return 0;
        }
        List<Command> commands = buildCommands(ds, accepted);
        if (commands.isEmpty()) {
            return 0;
        }
        Command seq =
                commands.size() == 1
                        ? commands.get(0)
                        : new SequenceCommand("NVDB incline suggestions (review-only)", commands);
        UndoRedoHandler.getInstance().add(seq);
        return accepted.size();
    }

    /** Package-visible for tests: build commands without registering them. */
    public static List<Command> buildCommands(DataSet ds, List<ReviewModel.Row> accepted) {
        List<Command> commands = new ArrayList<>();
        for (ReviewModel.Row row : accepted) {
            if (row.kind == ReviewModel.Kind.WAY_TAGS) {
                OsmPrimitive prim = findWay(ds, row.osmId);
                if (prim == null || row.tags.isEmpty()) {
                    continue;
                }
                // Do not overwrite an existing incline=*
                Map<String, String> tags = new java.util.LinkedHashMap<>(row.tags);
                if (prim.hasKey("incline")) {
                    tags.remove("incline");
                }
                if (!tags.isEmpty()) {
                    commands.add(new ChangePropertyCommand(java.util.List.of(prim), tags));
                }
            } else if (row.kind == ReviewModel.Kind.CHAIN_NODE && row.chainPoint != null) {
                double[] lonlat = Utm33.utmToLonLat(row.chainPoint.x(), row.chainPoint.y());
                Node node = new Node(new LatLon(lonlat[1], lonlat[0]));
                for (Map.Entry<String, String> e : row.tags.entrySet()) {
                    node.put(e.getKey(), e.getValue());
                }
                commands.add(new AddCommand(ds, node));
            }
        }
        return commands;
    }

    private static Way findWay(DataSet ds, long id) {
        for (Way w : ds.getWays()) {
            if (w.getUniqueId() == id || w.getOsmId() == id) {
                return w;
            }
        }
        // Newly created / negative ids in downloaded data use uniqueId
        for (Way w : ds.getWays()) {
            if (w.getId() == id) {
                return w;
            }
        }
        return null;
    }
}
