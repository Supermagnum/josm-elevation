package org.openstreetmap.josm.plugins.nvdbincline.io;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import no.nvdbincline.core.osm.PbfHighwayExtractor;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Build a JOSM {@link DataSet} from polygon-clipped PBF highways.
 *
 * <p>Nodes are new local primitives (JOSM assigns temporary ids). Ways keep their
 * real positive OSM ids from the Geofabrik extract when available.
 */
public final class OsmDatasetFromExtract {
    private OsmDatasetFromExtract() {}

    public static DataSet fromHighways(List<PbfHighwayExtractor.LonLatHighway> highways) {
        DataSet ds = new DataSet();
        ds.beginUpdate();
        try {
            Map<String, Node> shared = new HashMap<>();
            for (PbfHighwayExtractor.LonLatHighway h : highways) {
                Way way = h.id > 0 ? new Way(h.id, 1) : new Way();
                for (double[] ll : h.nodes) {
                    String key = String.format(Locale.ROOT, "%.7f,%.7f", ll[1], ll[0]);
                    Node node = shared.get(key);
                    if (node == null) {
                        // Do not call setOsmId with negative ids — JOSM requires id > 0.
                        node = new Node(new LatLon(ll[1], ll[0]));
                        ds.addPrimitive(node);
                        shared.put(key, node);
                    }
                    way.addNode(node);
                }
                way.put("highway", h.highway);
                if (h.name != null && !h.name.isBlank()) {
                    way.put("name", h.name);
                }
                if (h.nvdbId != null && !h.nvdbId.isBlank()) {
                    way.put("nvdb:id", h.nvdbId);
                }
                if (h.incline != null && !h.incline.isBlank()) {
                    way.put("incline", h.incline);
                }
                if (h.sourceIncline != null && !h.sourceIncline.isBlank()) {
                    way.put("source:incline", h.sourceIncline);
                }
                if (h.hazard != null && !h.hazard.isBlank()) {
                    way.put("hazard", h.hazard);
                }
                if (h.sourceHazard != null && !h.sourceHazard.isBlank()) {
                    way.put("source:hazard", h.sourceHazard);
                }
                if (h.chainAdvisory != null && !h.chainAdvisory.isBlank()) {
                    way.put("chain_advisory", h.chainAdvisory);
                }
                if (h.maxspeed != null) {
                    way.put("maxspeed", Integer.toString(h.maxspeed));
                }
                ds.addPrimitive(way);
            }
        } finally {
            ds.endUpdate();
        }
        return ds;
    }
}
