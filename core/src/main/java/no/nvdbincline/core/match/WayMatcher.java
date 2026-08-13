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
                Polyline concat = concat(links);
                double hd = way.line().hausdorffDistance(concat);
                return new MatchResult(
                        way, links, MatchConfidence.HIGH, "nvdb:id", hd, "fast-path join on nvdb:id");
            }
        }
        if (usable.isEmpty()) {
            return null;
        }

        List<Scored> scored = new ArrayList<>();
        for (NvdbLink lk : usable) {
            double hd = way.line().hausdorffDistance(lk.line());
            if (hd > settings.nearestFallbackM * 1.5) {
                continue;
            }
            double score = score(way.line(), lk.line(), hd, settings);
            scored.add(new Scored(score, hd, lk));
        }
        if (scored.isEmpty()) {
            return null;
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        NvdbLink best = scored.get(0).link;
        List<NvdbLink> sameSeq = new ArrayList<>();
        for (Scored s : scored) {
            if (s.link.veglenkesekvensId() == best.veglenkesekvensId()) {
                sameSeq.add(s.link);
            }
        }
        List<NvdbLink> links = sameSeq.isEmpty() ? List.of(best) : sameSeq;
        Polyline concat = concat(links);
        double concatHd = way.line().hausdorffDistance(concat);
        double bestScore = scored.get(0).score;

        if (concatHd <= settings.hausdorffHighM && bestScore >= 0.55) {
            return new MatchResult(way, links, MatchConfidence.HIGH, "geometry", concatHd, "");
        }
        if (concatHd <= settings.hausdorffMediumM && bestScore >= 0.35) {
            return new MatchResult(way, links, MatchConfidence.MEDIUM, "geometry", concatHd, "");
        }
        if (concatHd <= settings.nearestFallbackM) {
            return new MatchResult(
                    way,
                    links,
                    MatchConfidence.LOW,
                    "nearest-fallback",
                    concatHd,
                    "no confident overlap; nearest NVDB link within fallback distance");
        }
        return null;
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
