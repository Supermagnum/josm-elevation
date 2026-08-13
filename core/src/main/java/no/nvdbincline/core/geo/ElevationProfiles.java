package no.nvdbincline.core.geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.ElevationSample;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;

/** Sample NVDB Z onto OSM way vertices (node order defines incline sign). */
public final class ElevationProfiles {
    private ElevationProfiles() {}

    public static List<ElevationSample> build(OsmWayGeom way, List<NvdbLink> links) {
        Polyline merged = mergeLinks(links);
        if (merged == null) {
            return List.of();
        }
        List<ElevationSample> samples = new ArrayList<>();
        double dist = 0;
        Coord prev = null;
        for (Coord c : way.line().points()) {
            if (prev != null) {
                dist += prev.distanceXy(c);
            }
            Double z = interpolateZ(merged, c);
            if (z != null) {
                samples.add(new ElevationSample(dist, z, c.x(), c.y()));
            }
            prev = c;
        }
        return samples;
    }

    public static Polyline mergeLinks(List<NvdbLink> links) {
        if (links == null || links.isEmpty()) {
            return null;
        }
        List<NvdbLink> ordered = new ArrayList<>(links);
        ordered.sort(
                Comparator.comparingLong(NvdbLink::veglenkesekvensId)
                        .thenComparingDouble(NvdbLink::startposisjon));
        List<Polyline> parts = new ArrayList<>();
        for (NvdbLink lk : ordered) {
            parts.add(lk.line());
        }
        return Polyline.merge(parts);
    }

    static Double interpolateZ(Polyline line3d, Coord point) {
        if (!line3d.hasZ()) {
            return null;
        }
        double bestD = Double.POSITIVE_INFINITY;
        Double bestZ = null;
        // Project onto nearest segment and interpolate Z.
        for (int i = 1; i < line3d.size(); i++) {
            Coord a = line3d.get(i - 1);
            Coord b = line3d.get(i);
            double abx = b.x() - a.x();
            double aby = b.y() - a.y();
            double ab2 = abx * abx + aby * aby;
            double t = 0;
            if (ab2 > 1e-18) {
                t = ((point.x() - a.x()) * abx + (point.y() - a.y()) * aby) / ab2;
                t = Math.max(0, Math.min(1, t));
            }
            double cx = a.x() + t * abx;
            double cy = a.y() + t * aby;
            double d = Math.hypot(point.x() - cx, point.y() - cy);
            if (d < bestD) {
                bestD = d;
                if (a.hasZ() && b.hasZ()) {
                    bestZ = a.z() + t * (b.z() - a.z());
                } else if (a.hasZ()) {
                    bestZ = a.z();
                } else if (b.hasZ()) {
                    bestZ = b.z();
                }
            }
        }
        return bestZ;
    }
}
