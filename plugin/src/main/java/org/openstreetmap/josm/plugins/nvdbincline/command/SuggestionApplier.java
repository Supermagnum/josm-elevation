package org.openstreetmap.josm.plugins.nvdbincline.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <p>{@code hazard=*} is only emitted when the review row is sign-confirmed.
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
                        : new SequenceCommand(
                                "NVDB incline/safety suggestions (review-only)", commands);
        UndoRedoHandler.getInstance().add(seq);
        return accepted.size();
    }

    /** Build commands without registering them. */
    public static List<Command> buildCommands(DataSet ds, List<ReviewModel.Row> accepted) {
        List<Command> commands = new ArrayList<>();
        for (ReviewModel.Row row : accepted) {
            Map<String, String> tags = sanitizeTags(row);
            if (row.kind == ReviewModel.Kind.WAY_TAGS) {
                OsmPrimitive prim = findWay(ds, row.osmId);
                if (prim == null || tags.isEmpty()) {
                    continue;
                }
                if (prim.hasKey("incline")) {
                    tags = new LinkedHashMap<>(tags);
                    tags.remove("incline");
                }
                if (!tags.isEmpty()) {
                    commands.add(new ChangePropertyCommand(List.of(prim), tags));
                }
            } else if (isNodeKind(row.kind)) {
                Double x = row.x;
                Double y = row.y;
                if (row.chainPoint != null) {
                    x = row.chainPoint.x();
                    y = row.chainPoint.y();
                }
                if (x == null || y == null || tags.isEmpty()) {
                    continue;
                }
                double[] lonlat = Utm33.utmToLonLat(x, y);
                Node node = new Node(new LatLon(lonlat[1], lonlat[0]));
                for (Map.Entry<String, String> e : tags.entrySet()) {
                    node.put(e.getKey(), e.getValue());
                }
                commands.add(new AddCommand(ds, node));
            }
        }
        return commands;
    }

    /**
     * Strip {@code hazard=*} unless the row is sign-confirmed. This is the
     * hard safety gate for accident-cluster / geometry-only findings.
     */
    public static Map<String, String> sanitizeTags(ReviewModel.Row row) {
        Map<String, String> tags = new LinkedHashMap<>(row.tags);
        if (tags.containsKey("hazard") && !row.signConfirmed) {
            tags.remove("hazard");
            tags.remove("hazard:source");
        }
        return tags;
    }

    private static boolean isNodeKind(ReviewModel.Kind kind) {
        return kind == ReviewModel.Kind.CHAIN_NODE
                || kind == ReviewModel.Kind.CURVE_SIGNED
                || kind == ReviewModel.Kind.CURVE_ADVISORY
                || kind == ReviewModel.Kind.JUNCTION_SIGNED
                || kind == ReviewModel.Kind.ACCIDENT_CLUSTER;
    }

    private static Way findWay(DataSet ds, long id) {
        for (Way w : ds.getWays()) {
            if (w.getUniqueId() == id || w.getOsmId() == id || w.getId() == id) {
                return w;
            }
        }
        return null;
    }
}
