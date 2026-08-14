package no.nvdbincline.core.completion;

/**
 * "Done" rules for personal kommune completion tracking.
 *
 * <ul>
 *   <li>Manual override true → DONE; false → IN_PROGRESS (reopened).
 *   <li>Otherwise DONE when there has been at least one run, every matched way
 *       is accepted or rejected (no pending), and unmatched triage is dismissed
 *       (or there were zero unmatched).
 *   <li>NOT_STARTED when never run and no override.
 * </ul>
 */
public final class CompletionLogic {
    private CompletionLogic() {}

    public static CompletionStatus statusOf(KommuneCompletionRecord r) {
        if (r == null) {
            return CompletionStatus.NOT_STARTED;
        }
        if (Boolean.TRUE.equals(r.manualOverride())) {
            return CompletionStatus.DONE;
        }
        if (Boolean.FALSE.equals(r.manualOverride())) {
            return CompletionStatus.IN_PROGRESS;
        }
        if (r.lastRunEpochMilli() <= 0 && r.matchedWays() == 0 && r.accepted() == 0) {
            return CompletionStatus.NOT_STARTED;
        }
        if (isAutomaticallyDone(r)) {
            return CompletionStatus.DONE;
        }
        return CompletionStatus.IN_PROGRESS;
    }

    public static boolean isAutomaticallyDone(KommuneCompletionRecord r) {
        if (r.matchedWays() <= 0 && r.lastRunEpochMilli() <= 0) {
            return false;
        }
        if (r.pending() > 0) {
            return false;
        }
        if (r.unmatched() > 0 && !r.unmatchedDismissed()) {
            return false;
        }
        // All matched suggestions settled (accepted + rejected covers matched
        // incline decisions; allow accepted+rejected >= matched when extra
        // non-way rows were also reviewed).
        return r.accepted() + r.rejected() >= r.matchedWays() || r.matchedWays() == 0;
    }
}
