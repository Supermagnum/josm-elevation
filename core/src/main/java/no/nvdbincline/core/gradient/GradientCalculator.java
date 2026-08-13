package no.nvdbincline.core.gradient;

import java.util.ArrayList;
import java.util.List;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.ElevationSample;
import no.nvdbincline.core.model.GradientStats;
import no.nvdbincline.core.model.SegmentSuggestion;
import no.nvdbincline.core.tag.InclineTags;

/** Gradient calculation, rolling windows, and split suggestions. */
public final class GradientCalculator {
    private GradientCalculator() {}

    public static double averagePct(List<ElevationSample> profile) {
        if (profile.size() < 2) {
            return 0.0;
        }
        double run = profile.get(profile.size() - 1).distanceM() - profile.get(0).distanceM();
        if (run <= 1e-9) {
            return 0.0;
        }
        double rise =
                profile.get(profile.size() - 1).elevationM() - profile.get(0).elevationM();
        return 100.0 * rise / run;
    }

    public static List<double[]> windowGradients(List<ElevationSample> profile, double windowM) {
        List<double[]> out = new ArrayList<>();
        if (profile.size() < 2 || windowM <= 0) {
            return out;
        }
        int n = profile.size();
        int j = 0;
        for (int i = 0; i < n; i++) {
            double target = profile.get(i).distanceM() + windowM;
            while (j + 1 < n && profile.get(j).distanceM() < target) {
                j++;
            }
            double z1 = profile.get(i).elevationM();
            double z2;
            double run;
            if (profile.get(j).distanceM() < target - 1e-6) {
                if (profile.get(n - 1).distanceM() - profile.get(i).distanceM()
                        < Math.min(10.0, windowM * 0.4)) {
                    continue;
                }
                z2 = profile.get(n - 1).elevationM();
                run = profile.get(n - 1).distanceM() - profile.get(i).distanceM();
            } else {
                z2 = elevationAt(profile, target);
                run = windowM;
            }
            if (run <= 1e-9) {
                continue;
            }
            double pct = 100.0 * (z2 - z1) / run;
            double endM = Math.min(profile.get(i).distanceM() + run, profile.get(n - 1).distanceM());
            out.add(new double[] {profile.get(i).distanceM(), endM, pct});
        }
        return out;
    }

    public static double maxSustainedPct(List<ElevationSample> profile, double windowM) {
        List<double[]> windows = windowGradients(profile, windowM);
        if (windows.isEmpty()) {
            return averagePct(profile);
        }
        double best = windows.get(0)[2];
        for (double[] w : windows) {
            if (Math.abs(w[2]) > Math.abs(best)) {
                best = w[2];
            }
        }
        return best;
    }

    public static GradientStats stats(List<ElevationSample> profile, double windowM) {
        double avg = averagePct(profile);
        List<double[]> windows = windowGradients(profile, windowM);
        double minW = avg;
        double maxW = avg;
        double maxSust = avg;
        if (!windows.isEmpty()) {
            minW = windows.get(0)[2];
            maxW = windows.get(0)[2];
            for (double[] w : windows) {
                minW = Math.min(minW, w[2]);
                maxW = Math.max(maxW, w[2]);
            }
            maxSust = maxSustainedPct(profile, windowM);
        }
        double length =
                profile.size() < 2
                        ? 0.0
                        : profile.get(profile.size() - 1).distanceM() - profile.get(0).distanceM();
        return new GradientStats(avg, maxSust, minW, maxW, maxW - minW, length);
    }

    public static final class SplitConfig {
        public double windowM = 50.0;
        public double spreadPp = 4.0;
        public double minSegmentM = 50.0;
        public double mergePp = 2.0;
    }

    public static final class SplitResult {
        public final List<SegmentSuggestion> segments;
        public final boolean split;

        public SplitResult(List<SegmentSuggestion> segments, boolean split) {
            this.segments = List.copyOf(segments);
            this.split = split;
        }
    }

    public static SplitResult suggestSegments(List<ElevationSample> profile, SplitConfig cfg) {
        if (profile.size() < 2) {
            return new SplitResult(List.of(), false);
        }
        GradientStats st = stats(profile, cfg.windowM);
        List<double[]> regions = gradientRegions(profile, cfg);
        double spread = 0;
        if (!regions.isEmpty()) {
            double min = regions.get(0)[3];
            double max = regions.get(0)[3];
            for (double[] r : regions) {
                min = Math.min(min, r[3]);
                max = Math.max(max, r[3]);
            }
            spread = max - min;
        }
        boolean shouldSplit = spread > cfg.spreadPp && regions.size() > 1;
        if (!shouldSplit) {
            SegmentSuggestion seg =
                    new SegmentSuggestion(
                            profile.get(0).distanceM(),
                            profile.get(profile.size() - 1).distanceM(),
                            st.averagePct(),
                            st.maxSustainedPct(),
                            InclineTags.formatIncline(st.maxSustainedPct()),
                            new Coord(profile.get(0).x(), profile.get(0).y()),
                            new Coord(
                                    profile.get(profile.size() - 1).x(),
                                    profile.get(profile.size() - 1).y()));
            return new SplitResult(List.of(seg), false);
        }
        List<SegmentSuggestion> segs = new ArrayList<>();
        for (double[] r : regions) {
            List<ElevationSample> part = slice(profile, r[0], r[1]);
            GradientStats ps = stats(part, cfg.windowM);
            segs.add(
                    new SegmentSuggestion(
                            r[0],
                            r[1],
                            ps.averagePct(),
                            ps.maxSustainedPct(),
                            InclineTags.formatIncline(ps.maxSustainedPct()),
                            xyAt(profile, r[0]),
                            xyAt(profile, r[1])));
        }
        return new SplitResult(segs, true);
    }

    private static List<ElevationSample> slice(
            List<ElevationSample> profile, double startM, double endM) {
        List<ElevationSample> pts = new ArrayList<>();
        for (ElevationSample p : profile) {
            if (p.distanceM() >= startM - 1e-6 && p.distanceM() <= endM + 1e-6) {
                pts.add(p);
            }
        }
        if (pts.size() >= 2) {
            return pts;
        }
        Coord a = xyAt(profile, startM);
        Coord b = xyAt(profile, endM);
        return List.of(
                new ElevationSample(startM, elevationAt(profile, startM), a.x(), a.y()),
                new ElevationSample(endM, elevationAt(profile, endM), b.x(), b.y()));
    }

    private static List<double[]> gradientRegions(List<ElevationSample> profile, SplitConfig cfg) {
        List<double[]> windows = windowGradients(profile, cfg.windowM);
        if (windows.isEmpty()) {
            double avg = averagePct(profile);
            return List.of(
                    new double[] {
                        profile.get(0).distanceM(),
                        profile.get(profile.size() - 1).distanceM(),
                        avg,
                        avg
                    });
        }
        List<double[]> raw = new ArrayList<>();
        for (double[] w : windows) {
            if (raw.isEmpty()) {
                raw.add(new double[] {w[0], w[1], w[2], 1.0});
                continue;
            }
            double[] prev = raw.get(raw.size() - 1);
            double prevAvg = prev[2] / prev[3];
            if (Math.abs(w[2] - prevAvg) <= cfg.mergePp) {
                prev[1] = w[1];
                prev[2] += w[2];
                prev[3] += 1.0;
            } else {
                raw.add(new double[] {w[0], w[1], w[2], 1.0});
            }
        }
        List<double[]> merged = new ArrayList<>();
        for (double[] r : raw) {
            merged.add(r.clone());
        }
        boolean changed = true;
        while (changed && merged.size() > 1) {
            changed = false;
            for (int i = 0; i < merged.size(); i++) {
                double length = merged.get(i)[1] - merged.get(i)[0];
                if (length < cfg.minSegmentM && merged.size() > 1) {
                    int target;
                    if (i == 0) {
                        target = 1;
                    } else if (i == merged.size() - 1) {
                        target = i - 1;
                    } else {
                        double avgI = merged.get(i)[2] / merged.get(i)[3];
                        double avgL = merged.get(i - 1)[2] / merged.get(i - 1)[3];
                        double avgR = merged.get(i + 1)[2] / merged.get(i + 1)[3];
                        target = Math.abs(avgI - avgL) <= Math.abs(avgI - avgR) ? i - 1 : i + 1;
                    }
                    int lo = Math.min(i, target);
                    int hi = Math.max(i, target);
                    merged.get(lo)[0] = Math.min(merged.get(lo)[0], merged.get(hi)[0]);
                    merged.get(lo)[1] = Math.max(merged.get(lo)[1], merged.get(hi)[1]);
                    merged.get(lo)[2] += merged.get(hi)[2];
                    merged.get(lo)[3] += merged.get(hi)[3];
                    merged.remove(hi);
                    changed = true;
                    break;
                }
            }
        }
        if (!merged.isEmpty()) {
            merged.get(0)[0] = profile.get(0).distanceM();
            merged.get(merged.size() - 1)[1] = profile.get(profile.size() - 1).distanceM();
        }
        List<double[]> out = new ArrayList<>();
        for (double[] m : merged) {
            double avg = m[3] == 0 ? 0 : m[2] / m[3];
            out.add(new double[] {m[0], m[1], avg, avg});
        }
        return out;
    }

    public static double elevationAt(List<ElevationSample> profile, double distanceM) {
        if (distanceM <= profile.get(0).distanceM()) {
            return profile.get(0).elevationM();
        }
        for (int i = 1; i < profile.size(); i++) {
            ElevationSample a = profile.get(i - 1);
            ElevationSample b = profile.get(i);
            if (b.distanceM() >= distanceM) {
                double span = b.distanceM() - a.distanceM();
                double t = span <= 1e-9 ? 0 : (distanceM - a.distanceM()) / span;
                return a.elevationM() + t * (b.elevationM() - a.elevationM());
            }
        }
        return profile.get(profile.size() - 1).elevationM();
    }

    public static Coord xyAt(List<ElevationSample> profile, double distanceM) {
        if (distanceM <= profile.get(0).distanceM()) {
            return new Coord(profile.get(0).x(), profile.get(0).y());
        }
        for (int i = 1; i < profile.size(); i++) {
            ElevationSample a = profile.get(i - 1);
            ElevationSample b = profile.get(i);
            if (b.distanceM() >= distanceM) {
                double span = b.distanceM() - a.distanceM();
                double t = span <= 1e-9 ? 0 : (distanceM - a.distanceM()) / span;
                return new Coord(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()));
            }
        }
        ElevationSample last = profile.get(profile.size() - 1);
        return new Coord(last.x(), last.y());
    }
}
