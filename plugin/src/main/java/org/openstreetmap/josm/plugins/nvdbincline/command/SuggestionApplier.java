package org.openstreetmap.josm.plugins.nvdbincline.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.tag.AppliedTags;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.nvdbincline.NvdbInclinePreferences;

/**
 * Turns accepted review rows into ordinary JOSM Commands and registers them
 * with UndoRedoHandler. Never uploads.
 *
 * <p>{@code hazard=*} is only emitted when the review row is sign-confirmed.
 * Only allowlisted OSM keys are written — no match/estimate bookkeeping tags.
 */
public final class SuggestionApplier {
    private SuggestionApplier() {}

    /**
     * @return number of accepted rows that produced commands
     */
    public static int applyAccepted(DataSet ds, List<ReviewModel.Row> accepted) {
        return applyAccepted(ds, accepted, NvdbInclinePreferences.autoSplitVariableGradient());
    }

    /**
     * @param autoSplit when true, split-recommended incline rows are split
     *     (nodes + {@link SplitWayCommand} + per-piece tags) instead of a
     *     whole-way {@code incline=*}
     */
    public static int applyAccepted(
            DataSet ds, List<ReviewModel.Row> accepted, boolean autoSplit) {
        if (accepted == null || accepted.isEmpty()) {
            return 0;
        }
        List<Command> commands = buildCommands(ds, accepted, autoSplit);
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

    /** Build commands without registering them. Auto-split follows the plugin preference. */
    public static List<Command> buildCommands(DataSet ds, List<ReviewModel.Row> accepted) {
        return buildCommands(ds, accepted, NvdbInclinePreferences.autoSplitVariableGradient());
    }

    /** Build commands without registering them. */
    public static List<Command> buildCommands(
            DataSet ds, List<ReviewModel.Row> accepted, boolean autoSplit) {
        List<Command> commands = new ArrayList<>();
        for (ReviewModel.Row row : accepted) {
            Map<String, String> tags = sanitizeTags(row);
            if (row.kind == ReviewModel.Kind.WAY_TAGS) {
                Way way = findWay(ds, row.osmId);
                if (way == null || tags.isEmpty()) {
                    continue;
                }
                if (autoSplit) {
                    List<Command> splitCmds = tryAutoSplit(ds, way, row);
                    if (splitCmds != null && !splitCmds.isEmpty()) {
                        commands.add(
                                splitCmds.size() == 1
                                        ? splitCmds.get(0)
                                        : new SequenceCommand(
                                                "Split way and apply incline=*", splitCmds));
                        continue;
                    }
                }
                // Incline is only present in tags for FRESH and UPDATE rows. DISCREPANCY
                // rows never reach here. Do not strip incline — updates must overwrite
                // prior source:incline=nvdb_estimate values.
                commands.add(new ChangePropertyCommand(List.of(way), tags));
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
     * Strip {@code hazard=*} unless sign-confirmed, then retain only allowlisted
     * OSM keys for the row kind (drops bookkeeping and legacy {@code *:source}
     * suffix forms).
     */
    public static Map<String, String> sanitizeTags(ReviewModel.Row row) {
        Map<String, String> tags = new LinkedHashMap<>(row.tags);
        if (tags.containsKey("hazard") && !row.signConfirmed) {
            tags.remove("hazard");
            tags.remove(AppliedTags.SOURCE_HAZARD);
            tags.remove("hazard:source");
        }
        return AppliedTags.retain(tags, allowedKeys(row.kind));
    }

    static Set<String> allowedKeys(ReviewModel.Kind kind) {
        return switch (kind) {
            case WAY_TAGS -> AppliedTags.WAY_INCLINE_KEYS;
            case CHAIN_NODE -> AppliedTags.CHAIN_KEYS;
            case CURVE_SIGNED, JUNCTION_SIGNED -> AppliedTags.HAZARD_KEYS;
            case ACCIDENT_CLUSTER ->
                    // Sign-confirmed clusters carry hazard keys; unsigned carry advisory keys.
                    // Retain is intersection with whatever is present after hazard strip.
                    union(AppliedTags.HAZARD_KEYS, AppliedTags.SAFETY_ADVISORY_KEYS);
            case CURVE_ADVISORY -> AppliedTags.SAFETY_ADVISORY_KEYS;
            case DISCREPANCY -> Set.of();
        };
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    private static boolean isNodeKind(ReviewModel.Kind kind) {
        return kind == ReviewModel.Kind.CHAIN_NODE
                || kind == ReviewModel.Kind.CURVE_SIGNED
                || kind == ReviewModel.Kind.CURVE_ADVISORY
                || kind == ReviewModel.Kind.JUNCTION_SIGNED
                || kind == ReviewModel.Kind.ACCIDENT_CLUSTER;
    }

    private static List<Command> tryAutoSplit(DataSet ds, Way way, ReviewModel.Row row) {
        if (!row.splitSuggested || row.inclineAudit == null) {
            return null;
        }
        var segments = row.inclineAudit.segments();
        if (segments == null || segments.size() < 2) {
            return null;
        }
        return WaySplitPlanner.buildSplitAndTagCommands(ds, way, segments);
    }

    /**
     * Split-recommended incline ways among {@code rows} that are members of at
     * least one usable relation. Used for the review-dialog warning.
     */
    public static List<Way> splitWaysInRelations(DataSet ds, List<ReviewModel.Row> rows) {
        List<Way> out = new ArrayList<>();
        if (ds == null || rows == null) {
            return out;
        }
        for (ReviewModel.Row row : rows) {
            if (row.kind != ReviewModel.Kind.WAY_TAGS || !row.splitSuggested) {
                continue;
            }
            Way way = findWay(ds, row.osmId);
            if (way != null && isRelationMember(way) && !out.contains(way)) {
                out.add(way);
            }
        }
        return out;
    }

    public static boolean isRelationMember(Way way) {
        if (way == null) {
            return false;
        }
        for (OsmPrimitive p : way.getReferrers()) {
            if (p instanceof Relation r && r.isUsable()) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsSplitWayCommand(List<Command> commands) {
        if (commands == null) {
            return false;
        }
        for (Command c : commands) {
            if (containsSplitWayCommand(c)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsSplitWayCommand(Command cmd) {
        if (cmd instanceof SplitWayCommand) {
            return true;
        }
        var children = cmd.getChildren();
        if (children == null) {
            return false;
        }
        for (var child : children) {
            if (child instanceof Command c && containsSplitWayCommand(c)) {
                return true;
            }
        }
        return false;
    }

    static Way findWay(DataSet ds, long id) {
        for (Way w : ds.getWays()) {
            if (w.getUniqueId() == id || w.getOsmId() == id || w.getId() == id) {
                return w;
            }
        }
        return null;
    }
}
