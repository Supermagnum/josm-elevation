package no.nvdbincline.core.model;

/** Sharp curve detected from geometry (before sign cross-check). */
public final class CurveFeature {
    private final long wayId;
    private final double x;
    private final double y;
    private final double radiusM;
    private final double startM;
    private final double endM;
    private final Integer speedLimitKph;

    public CurveFeature(
            long wayId,
            double x,
            double y,
            double radiusM,
            double startM,
            double endM,
            Integer speedLimitKph) {
        this.wayId = wayId;
        this.x = x;
        this.y = y;
        this.radiusM = radiusM;
        this.startM = startM;
        this.endM = endM;
        this.speedLimitKph = speedLimitKph;
    }

    public long wayId() {
        return wayId;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double radiusM() {
        return radiusM;
    }

    public double startM() {
        return startM;
    }

    public double endM() {
        return endM;
    }

    public Integer speedLimitKph() {
        return speedLimitKph;
    }
}
