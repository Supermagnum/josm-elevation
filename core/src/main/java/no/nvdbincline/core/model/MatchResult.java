package no.nvdbincline.core.model;

import java.util.List;

public final class MatchResult {
    private final OsmWayGeom way;
    private final List<NvdbLink> links;
    private final MatchConfidence confidence;
    private final String method;
    private final Double hausdorffM;
    private final String notes;

    public MatchResult(
            OsmWayGeom way,
            List<NvdbLink> links,
            MatchConfidence confidence,
            String method,
            Double hausdorffM,
            String notes) {
        this.way = way;
        this.links = List.copyOf(links);
        this.confidence = confidence;
        this.method = method;
        this.hausdorffM = hausdorffM;
        this.notes = notes == null ? "" : notes;
    }

    public OsmWayGeom way() {
        return way;
    }

    public List<NvdbLink> links() {
        return links;
    }

    public MatchConfidence confidence() {
        return confidence;
    }

    public String method() {
        return method;
    }

    public Double hausdorffM() {
        return hausdorffM;
    }

    public String notes() {
        return notes;
    }
}
