package no.nvdbincline.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Polyline in a projected metre CRS (typically UTM33). */
public final class Polyline {
    private final List<Coord> points;

    public Polyline(List<Coord> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("polyline needs at least 2 points");
        }
        this.points = List.copyOf(points);
    }

    public List<Coord> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    public Coord get(int i) {
        return points.get(i);
    }

    public double lengthM() {
        double len = 0;
        for (int i = 1; i < points.size(); i++) {
            len += points.get(i - 1).distanceXy(points.get(i));
        }
        return len;
    }

    /** Axis-aligned envelope as {@code [minX, minY, maxX, maxY]}. */
    public double[] envelope() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Coord p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    /** True if envelopes are within {@code padM} (inclusive) of each other. */
    public boolean envelopesWithin(Polyline other, double padM) {
        double[] a = envelope();
        double[] b = other.envelope();
        return !(a[2] + padM < b[0]
                || b[2] + padM < a[0]
                || a[3] + padM < b[1]
                || b[3] + padM < a[1]);
    }

    public boolean hasZ() {
        for (Coord c : points) {
            if (c.hasZ()) {
                return true;
            }
        }
        return false;
    }

    /** Discrete Hausdorff distance between vertex sets (upper bound of true Hausdorff). */
    public double hausdorffDistance(Polyline other) {
        return Math.max(directedHausdorff(other), other.directedHausdorff(this));
    }

    /**
     * One-sided Hausdorff: max over this polyline's vertices of min distance to {@code other}.
     * Use this for "does {@code other} cover this geometry" when lengths differ.
     */
    public double directedHausdorff(Polyline other) {
        double max = 0;
        for (Coord p : points) {
            double d = minDistanceTo(p, other);
            if (d > max) {
                max = d;
            }
        }
        return max;
    }

    /** Minimum distance from any vertex of this polyline to {@code other}. */
    public double minDistance(Polyline other) {
        double best = Double.POSITIVE_INFINITY;
        for (Coord p : points) {
            best = Math.min(best, minDistanceTo(p, other));
        }
        for (Coord p : other.points) {
            best = Math.min(best, minDistanceTo(p, this));
        }
        return best;
    }

    /** Per-vertex distance from this polyline to {@code other} (along this polyline's vertices). */
    public double[] vertexDistancesTo(Polyline other) {
        double[] out = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            out[i] = minDistanceTo(points.get(i), other);
        }
        return out;
    }

    /** Fraction of this polyline's vertices within {@code bufferM} of {@code other}. */
    public double coverageFraction(Polyline other, double bufferM) {
        if (points.isEmpty()) {
            return 0;
        }
        int inside = 0;
        for (Coord p : points) {
            if (minDistanceTo(p, other) <= bufferM) {
                inside++;
            }
        }
        return (double) inside / points.size();
    }

    private static double minDistanceTo(Coord p, Polyline line) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 1; i < line.size(); i++) {
            min = Math.min(min, distancePointToSegment(p, line.get(i - 1), line.get(i)));
        }
        return min;
    }

    private static double distancePointToSegment(Coord p, Coord a, Coord b) {
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double apx = p.x() - a.x();
        double apy = p.y() - a.y();
        double ab2 = abx * abx + aby * aby;
        if (ab2 < 1e-18) {
            return p.distanceXy(a);
        }
        double t = Math.max(0, Math.min(1, (apx * abx + apy * aby) / ab2));
        double cx = a.x() + t * abx;
        double cy = a.y() + t * aby;
        return Math.hypot(p.x() - cx, p.y() - cy);
    }

    public Polyline concat(Polyline other) {
        List<Coord> out = new ArrayList<>(points);
        List<Coord> b = other.points;
        if (!out.isEmpty() && !b.isEmpty() && out.get(out.size() - 1).distanceXy(b.get(0)) < 0.05) {
            out.addAll(b.subList(1, b.size()));
        } else {
            out.addAll(b);
        }
        return new Polyline(out);
    }

    public static Polyline merge(List<Polyline> parts) {
        Objects.requireNonNull(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        Polyline acc = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            acc = acc.concat(parts.get(i));
        }
        return acc;
    }
}
