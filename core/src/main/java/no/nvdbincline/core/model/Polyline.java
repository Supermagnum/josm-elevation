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
        return Math.max(directedHausdorff(this, other), directedHausdorff(other, this));
    }

    private static double directedHausdorff(Polyline a, Polyline b) {
        double max = 0;
        for (Coord p : a.points) {
            double min = Double.POSITIVE_INFINITY;
            for (int i = 1; i < b.size(); i++) {
                min = Math.min(min, distancePointToSegment(p, b.get(i - 1), b.get(i)));
            }
            if (min > max) {
                max = min;
            }
        }
        return max;
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
