package no.nvdbincline.core.model;

public final class GradientStats {
    private final double averagePct;
    private final double maxSustainedPct;
    private final double minWindowPct;
    private final double maxWindowPct;
    private final double spreadPp;
    private final double lengthM;

    public GradientStats(
            double averagePct,
            double maxSustainedPct,
            double minWindowPct,
            double maxWindowPct,
            double spreadPp,
            double lengthM) {
        this.averagePct = averagePct;
        this.maxSustainedPct = maxSustainedPct;
        this.minWindowPct = minWindowPct;
        this.maxWindowPct = maxWindowPct;
        this.spreadPp = spreadPp;
        this.lengthM = lengthM;
    }

    public double averagePct() {
        return averagePct;
    }

    public double maxSustainedPct() {
        return maxSustainedPct;
    }

    public double minWindowPct() {
        return minWindowPct;
    }

    public double maxWindowPct() {
        return maxWindowPct;
    }

    public double spreadPp() {
        return spreadPp;
    }

    public double lengthM() {
        return lengthM;
    }
}
