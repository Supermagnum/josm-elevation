package no.nvdbincline.core.model;

import java.util.List;
import java.util.Map;

/**
 * Safety finding for the review dialog.
 *
 * {@code hazard=*} tags are only present when {@link #signConfirmed()} is true.
 * Accident clusters and unsigned sharp curves stay advisory-only.
 */
public final class SafetyFinding {
    public enum Kind {
        /** Signed Farlig sving → may suggest hazard=curve. */
        CURVE_SIGNED,
        /** Sharp geometry, no matching NVDB warning sign → advisory only. */
        CURVE_ADVISORY,
        /** Signed Farlig vegkryss / similar → may suggest hazard=dangerous_junction. */
        JUNCTION_SIGNED,
        /** Accident cluster without (or with) sign; hazard only if signConfirmed. */
        ACCIDENT_CLUSTER
    }

    private final Kind kind;
    private final boolean signConfirmed;
    private final double x;
    private final double y;
    private final Long wayId;
    private final String summary;
    private final MatchConfidence confidence;
    private final Map<String, String> tags;
    private final int accidentCount;
    private final String dateRange;
    private final double radiusM;

    public SafetyFinding(
            Kind kind,
            boolean signConfirmed,
            double x,
            double y,
            Long wayId,
            String summary,
            MatchConfidence confidence,
            Map<String, String> tags,
            int accidentCount,
            String dateRange,
            double radiusM) {
        this.kind = kind;
        this.signConfirmed = signConfirmed;
        this.x = x;
        this.y = y;
        this.wayId = wayId;
        this.summary = summary;
        this.confidence = confidence;
        this.tags = Map.copyOf(tags);
        this.accidentCount = accidentCount;
        this.dateRange = dateRange;
        this.radiusM = radiusM;
        if (tags.containsKey("hazard") && !signConfirmed) {
            throw new IllegalArgumentException(
                    "hazard=* is only allowed when signConfirmed=true (OSM hazard requires a posted sign)");
        }
    }

    public Kind kind() {
        return kind;
    }

    public boolean signConfirmed() {
        return signConfirmed;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public Long wayId() {
        return wayId;
    }

    public String summary() {
        return summary;
    }

    public MatchConfidence confidence() {
        return confidence;
    }

    public Map<String, String> tags() {
        return tags;
    }

    public int accidentCount() {
        return accidentCount;
    }

    public String dateRange() {
        return dateRange;
    }

    public double radiusM() {
        return radiusM;
    }

    public boolean allowsHazardTag() {
        return signConfirmed && tags.containsKey("hazard");
    }
}
