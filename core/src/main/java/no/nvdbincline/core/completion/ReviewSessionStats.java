package no.nvdbincline.core.completion;

import no.nvdbincline.core.review.ReviewModel;

/** Derive completion session counters from a finished review model. */
public final class ReviewSessionStats {
    public final int matchedWays;
    public final int accepted;
    public final int rejected;
    public final int pending;
    public final int unmatched;

    public ReviewSessionStats(int matchedWays, int accepted, int rejected, int pending, int unmatched) {
        this.matchedWays = matchedWays;
        this.accepted = accepted;
        this.rejected = rejected;
        this.pending = pending;
        this.unmatched = unmatched;
    }

    /**
     * Counts only incline-related rows ({@link ReviewModel.Kind#WAY_TAGS} and
     * {@link ReviewModel.Kind#DISCREPANCY}) against {@code matchedWays}.
     *
     * @param applied when true, unchecked incline rows count as rejected; when
     *     false (cancel), they remain pending
     */
    public static ReviewSessionStats fromReview(
            ReviewModel model, int matchedWays, int unmatched, boolean applied) {
        int accepted = 0;
        int rejected = 0;
        int pending = 0;
        for (ReviewModel.Row r : model.rows()) {
            if (r.kind != ReviewModel.Kind.WAY_TAGS && r.kind != ReviewModel.Kind.DISCREPANCY) {
                continue;
            }
            if (r.accepted) {
                accepted++;
            } else if (applied) {
                rejected++;
            } else {
                pending++;
            }
        }
        return new ReviewSessionStats(matchedWays, accepted, rejected, pending, unmatched);
    }
}
