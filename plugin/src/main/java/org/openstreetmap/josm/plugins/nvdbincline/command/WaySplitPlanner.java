package org.openstreetmap.josm.plugins.nvdbincline.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.SegmentSuggestion;
import no.nvdbincline.core.tag.AppliedTags;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SplitWayCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Builds undoable commands that insert split nodes (when needed), split a way
 * with {@link SplitWayCommand}, and tag each resulting sub-way with its own
 * {@code incline=*}.
 *
 * <p>New nodes are projected onto the existing OSM way (UTM33), not placed at
 * raw NVDB coordinates, so the way is not pulled sideways.
 */
public final class WaySplitPlanner {
    /** Reuse an existing node if the projected split point is this close. */
    static final double SNAP_M = 1.0;
    /** Refuse to insert if the NVDB split point is this far off the OSM way. */
    static final double MAX_OFF_WAY_M = 15.0;

    private WaySplitPlanner() {}

    /**
     * @return commands (node adds, split, per-piece tags), or {@code null} if
     *     splitting is not feasible and the caller should fall back to a
     *     whole-way tag
     */
    public static List<Command> buildSplitAndTagCommands(
            DataSet ds, Way way, List<SegmentSuggestion> segments) {
        if (ds == null
                || way == null
                || way.getDataSet() != ds
                || way.getNodesCount() < 2
                || segments == null
                || segments.size() < 2) {
            return null;
        }
        List<Node> working = new ArrayList<>(way.getNodes());
        List<Node> created = new ArrayList<>();
        List<Node> splitAt = new ArrayList<>();
        for (int i = 1; i < segments.size(); i++) {
            Coord xy = segments.get(i).startXy();
            if (xy == null) {
                return null;
            }
            Snap snap = snapOntoWay(working, xy);
            if (snap == null) {
                return null;
            }
            Node node;
            if (snap.existing != null) {
                node = snap.existing;
            } else {
                double[] lonlat = Utm33.utmToLonLat(snap.x, snap.y);
                node = new Node(new LatLon(lonlat[1], lonlat[0]));
                created.add(node);
                working.add(snap.insertIndex, node);
            }
            if (node.equals(working.get(0)) || node.equals(working.get(working.size() - 1))) {
                continue;
            }
            if (!splitAt.contains(node)) {
                splitAt.add(node);
            }
        }
        if (splitAt.isEmpty()) {
            return null;
        }
        List<List<Node>> chunks = chunksFromSplitNodes(working, splitAt);
        if (chunks == null || chunks.size() < 2) {
            return null;
        }
        Optional<SplitWayCommand> split =
                SplitWayCommand.splitWay(
                        way,
                        chunks,
                        List.of(),
                        SplitWayCommand.Strategy.keepFirstChunk(),
                        SplitWayCommand.WhenRelationOrderUncertain.SPLIT_ANYWAY);
        if (split.isEmpty()) {
            return null;
        }
        SplitWayCommand splitCmd = split.get();
        List<Command> cmds = new ArrayList<>();
        for (Node n : created) {
            cmds.add(new AddCommand(ds, n));
        }
        cmds.add(splitCmd);

        List<Way> resulting = new ArrayList<>();
        resulting.add(splitCmd.getOriginalWay());
        resulting.addAll(splitCmd.getNewWays());
        int n = Math.min(resulting.size(), segments.size());
        for (int i = 0; i < n; i++) {
            Map<String, String> tags = AppliedTags.incline(segments.get(i).inclineTag());
            Way piece = resulting.get(i);
            if (piece.getDataSet() != null) {
                cmds.add(new ChangePropertyCommand(List.of(piece), tags));
            } else {
                // New ways are not in the DataSet until SplitWayCommand runs;
                // ChangePropertyCommand requires a dataset, so set keys now.
                for (Map.Entry<String, String> e : tags.entrySet()) {
                    piece.put(e.getKey(), e.getValue());
                }
            }
        }
        return cmds;
    }

    static List<List<Node>> chunksFromSplitNodes(List<Node> nodes, List<Node> splitAt) {
        Set<Node> cuts = new HashSet<>(splitAt);
        List<List<Node>> chunks = new ArrayList<>();
        List<Node> current = new ArrayList<>();
        chunks.add(current);
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            boolean atEnd = current.isEmpty() || i == nodes.size() - 1;
            current.add(n);
            if (cuts.contains(n) && !atEnd) {
                current = new ArrayList<>();
                current.add(n);
                chunks.add(current);
            }
        }
        return chunks.size() >= 2 ? chunks : null;
    }

    static Snap snapOntoWay(List<Node> nodes, Coord targetUtm) {
        double bestD = Double.POSITIVE_INFINITY;
        int bestSegEnd = -1;
        double bestX = 0;
        double bestY = 0;
        for (int i = 1; i < nodes.size(); i++) {
            LatLon a = nodes.get(i - 1).getCoor();
            LatLon b = nodes.get(i).getCoor();
            if (a == null || b == null) {
                continue;
            }
            double[] au = Utm33.lonLatToUtm(a.lon(), a.lat());
            double[] bu = Utm33.lonLatToUtm(b.lon(), b.lat());
            double abx = bu[0] - au[0];
            double aby = bu[1] - au[1];
            double ab2 = abx * abx + aby * aby;
            double t = 0;
            if (ab2 > 1e-18) {
                t = ((targetUtm.x() - au[0]) * abx + (targetUtm.y() - au[1]) * aby) / ab2;
                t = Math.max(0, Math.min(1, t));
            }
            double cx = au[0] + t * abx;
            double cy = au[1] + t * aby;
            double d = Math.hypot(targetUtm.x() - cx, targetUtm.y() - cy);
            if (d < bestD) {
                bestD = d;
                bestSegEnd = i;
                bestX = cx;
                bestY = cy;
            }
        }
        if (bestSegEnd < 0 || bestD > MAX_OFF_WAY_M) {
            return null;
        }
        Node start = nodes.get(bestSegEnd - 1);
        Node end = nodes.get(bestSegEnd);
        if (nodeUtmDistance(start, bestX, bestY) <= SNAP_M) {
            return Snap.existing(start);
        }
        if (nodeUtmDistance(end, bestX, bestY) <= SNAP_M) {
            return Snap.existing(end);
        }
        return Snap.insert(bestSegEnd, bestX, bestY);
    }

    private static double nodeUtmDistance(Node node, double x, double y) {
        LatLon ll = node.getCoor();
        if (ll == null) {
            return Double.POSITIVE_INFINITY;
        }
        double[] u = Utm33.lonLatToUtm(ll.lon(), ll.lat());
        return Math.hypot(u[0] - x, u[1] - y);
    }

    static final class Snap {
        final Node existing;
        final int insertIndex;
        final double x;
        final double y;

        private Snap(Node existing, int insertIndex, double x, double y) {
            this.existing = existing;
            this.insertIndex = insertIndex;
            this.x = x;
            this.y = y;
        }

        static Snap existing(Node n) {
            return new Snap(n, -1, 0, 0);
        }

        static Snap insert(int insertIndex, double x, double y) {
            return new Snap(null, insertIndex, x, y);
        }
    }
}
