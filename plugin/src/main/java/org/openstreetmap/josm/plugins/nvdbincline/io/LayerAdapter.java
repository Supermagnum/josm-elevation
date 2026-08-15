package org.openstreetmap.josm.plugins.nvdbincline.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/** Convert JOSM DataSet highways into core OsmWayGeom (UTM33 metres). */
public final class LayerAdapter {
    private static final Set<String> EXCLUDE = Set.of("footway", "path", "steps");

    private LayerAdapter() {}

    public static List<OsmWayGeom> extractWays(DataSet ds) {
        List<OsmWayGeom> out = new ArrayList<>();
        for (Way w : ds.getWays()) {
            if (!w.isUsable() || !w.hasKey("highway")) {
                continue;
            }
            String hw = w.get("highway");
            if (EXCLUDE.contains(hw)) {
                continue;
            }
            List<Coord> coords = new ArrayList<>();
            for (Node n : w.getNodes()) {
                LatLon ll = n.getCoor();
                if (ll == null) {
                    continue;
                }
                double[] xy = Utm33.lonLatToUtm(ll.lon(), ll.lat());
                coords.add(new Coord(xy[0], xy[1]));
            }
            if (coords.size() < 2) {
                continue;
            }
            String nvdb = w.get("nvdb:id");
            if (nvdb == null) {
                nvdb = w.get("nvdb:veglenkesekvensid");
            }
            Integer speed = parseMaxspeed(w.get("maxspeed"));
            out.add(
                    new OsmWayGeom(
                            w.getUniqueId(),
                            new Polyline(coords),
                            hw,
                            w.getName(),
                            nvdb,
                            w.get("incline"),
                            w.get("source:incline"),
                            w.get("hazard"),
                            w.get("source:hazard"),
                            w.get("chain_advisory"),
                            speed));
        }
        return out;
    }

    static Integer parseMaxspeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.contains("mph")) {
            return null;
        }
        // Values like "signals"/"none" have no digits — avoid split()[0] AIOOBE.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            int v = Integer.parseInt(m.group(1));
            return v > 0 && v < 200 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns minLon, minLat, maxLon, maxLat. */
    public static double[] bboxLonLat(DataSet ds) {
        Bounds b = null;
        for (Node n : ds.getNodes()) {
            LatLon ll = n.getCoor();
            if (ll == null) {
                continue;
            }
            if (b == null) {
                b = new Bounds(ll);
            } else {
                b.extend(ll);
            }
        }
        if (b == null) {
            throw new IllegalStateException("empty dataset");
        }
        return new double[] {
            b.getMin().lon(), b.getMin().lat(), b.getMax().lon(), b.getMax().lat()
        };
    }
}
