package no.nvdbincline.core.model;

public final class SegmentSuggestion {
    private final double startM;
    private final double endM;
    private final double averagePct;
    private final double maxSustainedPct;
    private final String inclineTag;
    private final Coord startXy;
    private final Coord endXy;

    public SegmentSuggestion(
            double startM,
            double endM,
            double averagePct,
            double maxSustainedPct,
            String inclineTag,
            Coord startXy,
            Coord endXy) {
        this.startM = startM;
        this.endM = endM;
        this.averagePct = averagePct;
        this.maxSustainedPct = maxSustainedPct;
        this.inclineTag = inclineTag;
        this.startXy = startXy;
        this.endXy = endXy;
    }

    public double startM() {
        return startM;
    }

    public double endM() {
        return endM;
    }

    public double averagePct() {
        return averagePct;
    }

    public double maxSustainedPct() {
        return maxSustainedPct;
    }

    public String inclineTag() {
        return inclineTag;
    }

    public Coord startXy() {
        return startXy;
    }

    public Coord endXy() {
        return endXy;
    }
}
