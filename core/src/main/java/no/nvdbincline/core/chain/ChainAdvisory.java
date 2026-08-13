package no.nvdbincline.core.chain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.nvdbincline.core.gradient.GradientCalculator;
import no.nvdbincline.core.model.ChainKind;
import no.nvdbincline.core.model.ChainPoint;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.ElevationSample;
import no.nvdbincline.core.model.NvdbLink;

/** Snow-chain fit/remove candidates from elevation and NVDB context. */
public final class ChainAdvisory {
    public static final class Settings {
        public double chainGradientPct = 6.0;
        public double chainMinDistanceM = 200.0;
        public double rollingWindowM = 50.0;
        public double clusterDistanceM = 75.0;
    }

    private ChainAdvisory() {}

    public static List<ChainPoint> advise(
            List<ElevationSample> profile,
            Long wayId,
            List<NvdbLink> links,
            Settings settings) {
        if (profile.size() < 2) {
            return List.of();
        }
        List<ChainPoint> points = new ArrayList<>();
        points.addAll(fromSustainedGrade(profile, wayId, settings));
        points.addAll(fromPass(profile, wayId, settings));
        points.addAll(fromTunnelPortals(links, wayId));
        return points;
    }

    public static List<ChainPoint> cluster(List<ChainPoint> points, double clusterM) {
        List<ChainPoint> remaining = new ArrayList<>(points);
        List<ChainPoint> out = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ChainPoint seed = remaining.remove(0);
            List<ChainPoint> group = new ArrayList<>();
            group.add(seed);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < remaining.size(); ) {
                    boolean close = false;
                    for (ChainPoint g : group) {
                        if (dist(remaining.get(i), g) <= clusterM) {
                            close = true;
                            break;
                        }
                    }
                    if (close) {
                        group.add(remaining.remove(i));
                        changed = true;
                    } else {
                        i++;
                    }
                }
            }
            double cx = 0;
            double cy = 0;
            for (ChainPoint p : group) {
                cx += p.x();
                cy += p.y();
            }
            cx /= group.size();
            cy /= group.size();
            ChainPoint rep = group.get(0);
            double best = Double.POSITIVE_INFINITY;
            for (ChainPoint p : group) {
                double d = Math.hypot(p.x() - cx, p.y() - cy);
                if (d < best) {
                    best = d;
                    rep = p;
                }
            }
            Set<ChainKind> kinds = new HashSet<>();
            Set<String> reasons = new HashSet<>();
            for (ChainPoint p : group) {
                kinds.add(p.kind());
                reasons.add(p.reason());
            }
            ChainKind kind;
            if (kinds.size() == 1) {
                kind = kinds.iterator().next();
            } else {
                kind = ChainKind.FIT_REMOVE;
            }
            out.add(
                    new ChainPoint(
                            rep.x(),
                            rep.y(),
                            kind,
                            String.join("; ", reasons.stream().sorted().toList()),
                            rep.wayId()));
        }
        return out;
    }

    private static List<ChainPoint> fromSustainedGrade(
            List<ElevationSample> profile, Long wayId, Settings settings) {
        List<double[]> stretches =
                sustainedStretches(
                        profile,
                        settings.chainGradientPct,
                        settings.chainMinDistanceM,
                        settings.rollingWindowM);
        List<ChainPoint> points = new ArrayList<>();
        for (double[] s : stretches) {
            double mean = s[2];
            if (mean >= settings.chainGradientPct) {
                Coord xy = GradientCalculator.xyAt(profile, s[0]);
                points.add(
                        new ChainPoint(
                                xy.x(),
                                xy.y(),
                                ChainKind.FIT,
                                String.format(
                                        "sustained climb %.1f%% over %.0fm", mean, s[1] - s[0]),
                                wayId));
            } else if (mean <= -settings.chainGradientPct) {
                Coord xy = GradientCalculator.xyAt(profile, s[1]);
                points.add(
                        new ChainPoint(
                                xy.x(),
                                xy.y(),
                                ChainKind.REMOVE,
                                String.format(
                                        "end of sustained descent %.1f%% over %.0fm",
                                        mean, s[1] - s[0]),
                                wayId));
            }
        }
        return points;
    }

    private static List<ChainPoint> fromPass(
            List<ElevationSample> profile, Long wayId, Settings settings) {
        if (profile.size() < 3) {
            return List.of();
        }
        int idx = 0;
        double zmax = profile.get(0).elevationM();
        double zmin = zmax;
        for (int i = 0; i < profile.size(); i++) {
            double z = profile.get(i).elevationM();
            if (z > zmax) {
                zmax = z;
                idx = i;
            }
            zmin = Math.min(zmin, z);
        }
        if (zmax - zmin < 15.0 || idx <= 0 || idx >= profile.size() - 1) {
            return List.of();
        }
        double left = profile.get(idx).elevationM() - profile.get(0).elevationM();
        double right = profile.get(idx).elevationM() - profile.get(profile.size() - 1).elevationM();
        if (left < 8.0 || right < 8.0) {
            return List.of();
        }
        double leftRun = profile.get(idx).distanceM() - profile.get(0).distanceM();
        double rightRun =
                profile.get(profile.size() - 1).distanceM() - profile.get(idx).distanceM();
        if (leftRun < settings.chainMinDistanceM * 0.5
                || rightRun < settings.chainMinDistanceM * 0.5) {
            return List.of();
        }
        double leftG = leftRun == 0 ? 0 : 100.0 * left / leftRun;
        double rightG = rightRun == 0 ? 0 : 100.0 * right / rightRun;
        if (leftG < settings.chainGradientPct * 0.7
                && rightG < settings.chainGradientPct * 0.7) {
            return List.of();
        }
        ElevationSample first = profile.get(0);
        ElevationSample last = profile.get(profile.size() - 1);
        return List.of(
                new ChainPoint(
                        first.x(),
                        first.y(),
                        ChainKind.FIT,
                        "approach to local elevation maximum (pass)",
                        wayId),
                new ChainPoint(
                        last.x(),
                        last.y(),
                        ChainKind.REMOVE,
                        "descent from local elevation maximum (pass)",
                        wayId),
                new ChainPoint(
                        last.x(),
                        last.y(),
                        ChainKind.FIT,
                        "opposite-direction approach to pass",
                        wayId),
                new ChainPoint(
                        first.x(),
                        first.y(),
                        ChainKind.REMOVE,
                        "opposite-direction descent from pass",
                        wayId));
    }

    private static List<ChainPoint> fromTunnelPortals(List<NvdbLink> links, Long wayId) {
        List<ChainPoint> points = new ArrayList<>();
        List<NvdbLink> ordered = new ArrayList<>(links);
        ordered.sort(
                java.util.Comparator.comparingLong(NvdbLink::veglenkesekvensId)
                        .thenComparingDouble(NvdbLink::startposisjon));
        for (int i = 0; i < ordered.size(); i++) {
            NvdbLink lk = ordered.get(i);
            if (!lk.isTunnel()) {
                continue;
            }
            var coords = lk.line().points();
            if (coords.size() < 2) {
                continue;
            }
            boolean prevT = i > 0 && ordered.get(i - 1).isTunnel();
            boolean nextT = i + 1 < ordered.size() && ordered.get(i + 1).isTunnel();
            if (!prevT) {
                var c = coords.get(0);
                points.add(new ChainPoint(c.x(), c.y(), ChainKind.FIT, "NVDB tunnel portal", wayId));
            }
            if (!nextT) {
                var c = coords.get(coords.size() - 1);
                points.add(
                        new ChainPoint(c.x(), c.y(), ChainKind.REMOVE, "NVDB tunnel portal", wayId));
            }
        }
        return points;
    }

    private static List<double[]> sustainedStretches(
            List<ElevationSample> profile,
            double minAbsPct,
            double minLengthM,
            double windowM) {
        List<double[]> windows = GradientCalculator.windowGradients(profile, windowM);
        List<double[]> stretches = new ArrayList<>();
        Double curStart = null;
        Double curEnd = null;
        int curSign = 0;
        double acc = 0;
        int n = 0;
        for (double[] w : windows) {
            int sign = w[2] >= minAbsPct ? 1 : (w[2] <= -minAbsPct ? -1 : 0);
            if (sign == 0) {
                flush(stretches, curStart, curEnd, acc, n, minLengthM, minAbsPct);
                curStart = curEnd = null;
                acc = 0;
                n = 0;
                curSign = 0;
                continue;
            }
            if (curStart == null) {
                curStart = w[0];
                curEnd = w[1];
                curSign = sign;
                acc = w[2];
                n = 1;
                continue;
            }
            if (sign != curSign) {
                flush(stretches, curStart, curEnd, acc, n, minLengthM, minAbsPct);
                curStart = w[0];
                curEnd = w[1];
                curSign = sign;
                acc = w[2];
                n = 1;
                continue;
            }
            curEnd = w[1];
            acc += w[2];
            n++;
        }
        flush(stretches, curStart, curEnd, acc, n, minLengthM, minAbsPct);
        return stretches;
    }

    private static void flush(
            List<double[]> stretches,
            Double curStart,
            Double curEnd,
            double acc,
            int n,
            double minLengthM,
            double minAbsPct) {
        if (curStart == null || curEnd == null || n == 0) {
            return;
        }
        double mean = acc / n;
        if (curEnd - curStart >= minLengthM && Math.abs(mean) >= minAbsPct) {
            stretches.add(new double[] {curStart, curEnd, mean});
        }
    }

    private static double dist(ChainPoint a, ChainPoint b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
