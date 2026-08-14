package no.nvdbincline.core.completion;

import java.time.Instant;
import java.util.Objects;

/**
 * Per-kommune bookkeeping for the local completion tracker.
 *
 * <p>This is personal progress on one machine — never uploaded, never shared,
 * never written to OSM objects.
 *
 * @param kommuneNummer key
 * @param matchedWays ways matched to an NVDB link on the most recent run
 * @param accepted suggestions accepted on that run
 * @param rejected suggestions explicitly rejected on that run
 * @param pending suggestions left pending on that run
 * @param unmatched unresolved unmatched OSM ways from that run
 * @param unmatchedDismissed user dismissed unmatched triage for completion
 * @param lastRunEpochMilli timestamp of last recorded run (0 if never)
 * @param manualOverride {@code null} = use calculated status; {@code true} =
 *     force DONE; {@code false} = force reopen (IN_PROGRESS)
 */
public record KommuneCompletionRecord(
        int kommuneNummer,
        int matchedWays,
        int accepted,
        int rejected,
        int pending,
        int unmatched,
        boolean unmatchedDismissed,
        long lastRunEpochMilli,
        Boolean manualOverride) {

    public KommuneCompletionRecord {
        if (kommuneNummer <= 0) {
            throw new IllegalArgumentException("kommunenummer");
        }
        if (matchedWays < 0 || accepted < 0 || rejected < 0 || pending < 0 || unmatched < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }

    public static KommuneCompletionRecord empty(int kommuneNummer) {
        return new KommuneCompletionRecord(kommuneNummer, 0, 0, 0, 0, 0, false, 0L, null);
    }

    public KommuneCompletionRecord withManualOverride(Boolean override) {
        return new KommuneCompletionRecord(
                kommuneNummer,
                matchedWays,
                accepted,
                rejected,
                pending,
                unmatched,
                unmatchedDismissed,
                lastRunEpochMilli,
                override);
    }

    public KommuneCompletionRecord withUnmatchedDismissed(boolean dismissed) {
        return new KommuneCompletionRecord(
                kommuneNummer,
                matchedWays,
                accepted,
                rejected,
                pending,
                unmatched,
                dismissed,
                lastRunEpochMilli,
                manualOverride);
    }

    public KommuneCompletionRecord withSession(
            int matched,
            int acceptedCount,
            int rejectedCount,
            int pendingCount,
            int unmatchedCount,
            boolean dismissUnmatched,
            Instant when) {
        Objects.requireNonNull(when, "when");
        return new KommuneCompletionRecord(
                kommuneNummer,
                matched,
                acceptedCount,
                rejectedCount,
                pendingCount,
                unmatchedCount,
                dismissUnmatched || unmatchedCount == 0,
                when.toEpochMilli(),
                manualOverride);
    }

    public CompletionStatus status() {
        return CompletionLogic.statusOf(this);
    }

    public boolean isDone() {
        return status() == CompletionStatus.DONE;
    }
}
