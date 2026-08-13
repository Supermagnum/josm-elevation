package no.nvdbincline.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One sample along an elevation profile (distance along way, elevation, planar XY). */
public final class ElevationSample {
    private final double distanceM;
    private final double elevationM;
    private final double x;
    private final double y;

    public ElevationSample(double distanceM, double elevationM, double x, double y) {
        this.distanceM = distanceM;
        this.elevationM = elevationM;
        this.x = x;
        this.y = y;
    }

    public double distanceM() {
        return distanceM;
    }

    public double elevationM() {
        return elevationM;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }
}
