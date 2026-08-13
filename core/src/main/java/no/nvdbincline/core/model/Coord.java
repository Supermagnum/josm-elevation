package no.nvdbincline.core.model;

/** Immutable 2D/3D point. Z may be NaN if unknown. */
public final class Coord {
    private final double x;
    private final double y;
    private final double z;

    public Coord(double x, double y) {
        this(x, y, Double.NaN);
    }

    public Coord(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public boolean hasZ() {
        return !Double.isNaN(z);
    }

    public double distanceXy(Coord other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.hypot(dx, dy);
    }
}
