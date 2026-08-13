package no.nvdbincline.core.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.MatchResult;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;

/** OSM-to-NVDB conflation: nvdb:id fast path, then geometry scoring. */
public final class WayMatcher {
    public static final class Settings {
        public double hausdorffHighM = 15.0;
        public double hausdorffMediumM = 30.0;
        public double nearestFallbackM = 50.0;
    }

    public static final class Result {
        public final List<MatchResult> matches;
        public final List<OsmWayGeom> unmatchedOsm;
        public final List<NvdbLink> unmatchedNvdb;

        public Result(
                List<MatchResult> matches,
                List<OsmWayGeom> unmatchedOsm,
                List<NvdbLink> unmatchedNvdb) {
            this.matches = List.copyOf(matches);
            this.unmatchedOsm = List.copyOf(unmatchedOsm);
            this.unmatchedNvdb = List.copyOf(unmatchedNvdb);
        }
    }

    private WayMatcher() {}

    public static Result match(List<OsmWayGeom> ways, List<NvdbLink> links, Settings settings) {
        List<NvdbLink> usable = new ArrayList<>();
        Map<String, List<NvdbLink>> byId = new HashMap<>();
        for (NvdbLink lk : links) {
            if (lk.isConnector()) {
                continue;
            }
            usable.add(lk);
            byId.computeIfAbsent(String.valueOf(lk.veglenkesekvensId()), k -> new ArrayList<>())
                    .add(lk);
        }

        List<MatchResult> matches = new ArrayList<>();
        List<OsmWayGeom> unmatchedOsm = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (OsmWayGeom way : ways) {
            if (way.line().lengthM() < 1.0) {
                unmatchedOsm.add(way);
                continue;
            }
            MatchResult m = matchOne(way, byId, usable, settings);
            if (m == null) {
                unmatchedOsm.add(way);
            } else {
                matches.add(m);
                for (NvdbLink lk : m.links()) {
                    used.add(lk.key());
                }
            }
        }
        List<NvdbLink> unmatchedNvdb = new ArrayList<>();
        for (NvdbLink lk : usable) {
            if (!used.contains(lk.key())) {
                unmatchedNvdb.add(lk);
            }
        }
        return new Result(matches, unmatchedOsm, unmatchedNvdb);
    }

    private static MatchResult matchOne(
            OsmWayGeom way,
            Map<String, List<NvdbLink>> byId,
            List<NvdbLink> usable,
            Settings settings) {
        if (way.nvdbId().isPresent()) {
            String id = way.nvdbId().get();
            List<NvdbLink> links = byId.get(id);
            if (links != null && !links.isEmpty()) {
                List<NvdbLink> near = overlappingLinks(way.line(), links, settings);
                if (near.isEmpty()) {
                    near = links;
                }
                return resultFromLinks(way, near, "nvdb:id", "fast-path join on nvdb:id", settings);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }

        List<NvdbLink> overlapping = overlappingLinks(way.line(), usable, settings);
        if (overlapping.isEmpty()) {
            return null;
        }

        // Score individual overlapping links; keep all overlapping links for elevation coverage
        // (a single OSM way often spans multiple NVDB veglenkesekvenser).
        List<Scored> scored = new ArrayList<>();
        for (NvdbLink lk : overlapping) {
            double sep = lk.line().minDistance(way.line());
            double score = score(way.line(), lk.line(), sep, settings);
            scored.add(new Scored(score, sep, lk));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        MatchResult built =
                resultFromLinks(way, overlapping, "geometry", "", settings);
        if (built == null) {
            // Soft fallback: still return LOW if any overlap exists within fallback distance.
            Polyline concat = concat(overlapping);
            double frac = way.line().coverageFraction(concat, settings.nearestFallbackM);
            if (frac >= 0.5) {
                double median = median(way.line().vertexDistancesTo(concat));
                return new MatchResult(
                        way,
                        overlapping,
                        MatchConfidence.LOW,
                        "nearest-fallback",
                        median,
                        "partial NVDB coverage ("
                                + String.format(java.util.Locale.ROOT, "%.0f%%", 100 * frac)
                                + " of vertices within fallback)");
            }
            return null;
        }
        // Preserve score-based method note when geometry path used.
        if (scored.get(0).score < 0.35 && built.confidence() != MatchConfidence.LOW) {
            return new MatchResult(
                    way,
                    built.links(),
                    built.confidence(),
                    built.method(),
                    built.hausdorffM(),
                    built.notes());
        }
        return built;
    }

    private static MatchResult resultFromLinks(
            OsmWayGeom way,
            List<NvdbLink> links,
            String method,
            String notes,
            Settings settings) {
        Polyline concat = concat(links);
        double fracHigh = way.line().coverageFraction(concat, settings.hausdorffHighM);
        double fracMed = way.line().coverageFraction(concat, settings.hausdorffMediumM);
        double[] dists = way.line().vertexDistancesTo(concat);
        double median = median(dists);
        double p90 = percentile(dists, 0.90);

        // Prefer coverage fraction: mountain ways often overhang NVDB at one end.
        if (fracHigh >= 0.90 && median <= settings.hausdorffHighM) {
            return new MatchResult(way, links, MatchConfidence.HIGH, method, p90, notes);
        }
        if (fracMed >= 0.70 && median <= settings.hausdorffMediumM) {
            return new MatchResult(way, links, MatchConfidence.MEDIUM, method, p90, notes);
        }
        if (fracMed >= 0.50 && median <= settings.nearestFallbackM) {
            return new MatchResult(
                    way,
                    links,
                    MatchConfidence.LOW,
                    method.equals("nvdb:id") ? method : "nearest-fallback",
                    p90,
                    notes.isBlank()
                            ? "partial NVDB coverage"
                            : notes);
        }
        return null;
    }

    /**
     * NVDB segments that overlap this OSM way: at least half their vertices lie within
     * the medium buffer, or the geometries come within {@code hausdorffHighM}.
     */
    private static List<NvdbLink> overlappingLinks(
            Polyline way, List<NvdbLink> links, Settings settings) {
        List<NvdbLink> out = new ArrayList<>();
        for (NvdbLink lk : links) {
            if (isFootLike(lk)) {
                continue;
            }
            double frac = lk.line().coverageFraction(way, settings.hausdorffMediumM);
            if (frac >= 0.5
                    || lk.line().minDistance(way) <= settings.nearestFallbackM) {
                out.add(lk);
            }
        }
        return out;
    }

    private static boolean isFootLike(NvdbLink lk) {
        String tv = lk.typeVeg() == null ? "" : lk.typeVeg().toLowerCase(java.util.Locale.ROOT);
        return tv.contains("gang") || tv.contains("fortau") || tv.contains("trapp");
    }

    private static double median(double[] values) {
        if (values.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double percentile(double[] values, double p) {
        if (values.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.round(p * (sorted.length - 1));
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    private static double score(Polyline osm, Polyline nvdb, double hausdorffM, Settings settings) {
        double hdTerm = Math.max(0.0, 1.0 - hausdorffM / Math.max(settings.nearestFallbackM, 1.0));
        double coverage = coverage(osm, nvdb, settings.hausdorffMediumM);
        double lengthRatio = lengthRatio(osm, nvdb);
        double direction = Math.abs(directionAlignment(osm, nvdb));
        return 0.4 * hdTerm + 0.35 * coverage + 0.15 * lengthRatio + 0.10 * direction;
    }

    private static double coverage(Polyline osm, Polyline nvdb, double bufferM) {
        if (osm.lengthM() <= 0) {
            return 0;
        }
        // Approximate: fraction of OSM vertices within buffer of NVDB.
        int inside = 0;
        for (var p : osm.points()) {
            double min = Double.POSITIVE_INFINITY;
            for (int i = 1; i < nvdb.size(); i++) {
                min = Math.min(min, distPointSeg(p.x(), p.y(), nvdb.get(i - 1), nvdb.get(i)));
            }
            if (min <= bufferM) {
                inside++;
            }
        }
        return (double) inside / osm.size();
    }

    private static double distPointSeg(
            double px, double py, no.nvdbincline.core.model.Coord a, no.nvdbincline.core.model.Coord b) {
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double ab2 = abx * abx + aby * aby;
        double t = 0;
        if (ab2 > 1e-18) {
            t = ((px - a.x()) * abx + (py - a.y()) * aby) / ab2;
            t = Math.max(0, Math.min(1, t));
        }
        return Math.hypot(px - (a.x() + t * abx), py - (a.y() + t * aby));
    }

    private static double lengthRatio(Polyline a, Polyline b) {
        double la = a.lengthM();
        double lb = b.lengthM();
        if (la <= 0 || lb <= 0) {
            return 0;
        }
        return Math.min(la, lb) / Math.max(la, lb);
    }

    private static double directionAlignment(Polyline a, Polyline b) {
        double[] va = endVector(a);
        double[] vb = endVector(b);
        double na = Math.hypot(va[0], va[1]);
        double nb = Math.hypot(vb[0], vb[1]);
        if (na < 1e-9 || nb < 1e-9) {
            return 0;
        }
        return (va[0] * vb[0] + va[1] * vb[1]) / (na * nb);
    }

    private static double[] endVector(Polyline line) {
        var first = line.get(0);
        var last = line.get(line.size() - 1);
        return new double[] {last.x() - first.x(), last.y() - first.y()};
    }

    private static Polyline concat(List<NvdbLink> links) {
        List<NvdbLink> ordered = new ArrayList<>(links);
        ordered.sort(
                java.util.Comparator.comparingLong(NvdbLink::veglenkesekvensId)
                        .thenComparingDouble(NvdbLink::startposisjon));
        List<Polyline> parts = new ArrayList<>();
        for (NvdbLink lk : ordered) {
            parts.add(lk.line());
        }
        return Polyline.merge(parts);
    }

    private static final class Scored {
        final double score;
        final double hd;
        final NvdbLink link;

        Scored(double score, double hd, NvdbLink link) {
            this.score = score;
            this.hd = hd;
            this.link = link;
        }
    }
}
