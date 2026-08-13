package no.nvdbincline.core.model;

public final class ChainPoint {
    private final double x;
    private final double y;
    private final ChainKind kind;
    private final String reason;
    private final Long wayId;

    public ChainPoint(double x, double y, ChainKind kind, String reason, Long wayId) {
        this.x = x;
        this.y = y;
        this.kind = kind;
        this.reason = reason == null ? "" : reason;
        this.wayId = wayId;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public ChainKind kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }

    public Long wayId() {
        return wayId;
    }
}
