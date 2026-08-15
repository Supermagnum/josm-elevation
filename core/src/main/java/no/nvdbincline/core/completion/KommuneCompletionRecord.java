package no.nvdbincline.core.completion;

import java.time.Instant;
import java.util.Objects;
import no.nvdbincline.core.tag.ExistingTagCoverage;

/**
 * Per-kommune bookkeeping for the local completion tracker.
 *
 * <p>This is personal progress on one machine — never uploaded, never shared,
 * never written to OSM objects. Optional coverage fields reflect the last OSM
 * extract scan (any source), distinct from review accept/reject counts.
 *
 * @param inclineCoveragePct percent of matched ways with incline=* (-1 unknown)
 * @param pluginInclinePct percent with source:incline=nvdb_estimate (-1 unknown)
 * @param otherInclinePct percent with other/surveyed incline (-1 unknown)
 * @param hazardCount nodes/ways with hazard=* (-1 unknown)
 * @param pluginHazardCount with source:hazard=nvdb_sign (-1 unknown)
 * @param otherHazardCount other hazard tags (-1 unknown)
 * @param chainAdvisoryCount ways/nodes with chain_advisory=* (-1 unknown)
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
        Boolean manualOverride,
        int inclineCoveragePct,
        int pluginInclinePct,
        int otherInclinePct,
        int hazardCount,
        int pluginHazardCount,
        int otherHazardCount,
        int chainAdvisoryCount) {

    public KommuneCompletionRecord {
        if (kommuneNummer <= 0) {
            throw new IllegalArgumentException("kommunenummer");
        }
        if (matchedWays < 0 || accepted < 0 || rejected < 0 || pending < 0 || unmatched < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }

    public static KommuneCompletionRecord empty(int kommuneNummer) {
        return new KommuneCompletionRecord(
                kommuneNummer, 0, 0, 0, 0, 0, false, 0L, null, -1, -1, -1, -1, -1, -1, -1);
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
                override,
                inclineCoveragePct,
                pluginInclinePct,
                otherInclinePct,
                hazardCount,
                pluginHazardCount,
                otherHazardCount,
                chainAdvisoryCount);
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
                manualOverride,
                inclineCoveragePct,
                pluginInclinePct,
                otherInclinePct,
                hazardCount,
                pluginHazardCount,
                otherHazardCount,
                chainAdvisoryCount);
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
                manualOverride,
                inclineCoveragePct,
                pluginInclinePct,
                otherInclinePct,
                hazardCount,
                pluginHazardCount,
                otherHazardCount,
                chainAdvisoryCount);
    }

    public KommuneCompletionRecord withCoverage(ExistingTagCoverage cov) {
        Objects.requireNonNull(cov, "cov");
        return new KommuneCompletionRecord(
                kommuneNummer,
                matchedWays,
                accepted,
                rejected,
                pending,
                unmatched,
                unmatchedDismissed,
                lastRunEpochMilli,
                manualOverride,
                cov.inclineCoveragePercent(),
                cov.pluginInclinePercent(),
                cov.otherInclinePercent(),
                cov.withHazard,
                cov.withPluginHazard,
                cov.withOtherHazard,
                cov.withChainAdvisory);
    }

    public boolean hasCoverage() {
        return inclineCoveragePct >= 0;
    }

    public String formatCoverageLine() {
        if (!hasCoverage()) {
            return "";
        }
        return String.format(
                java.util.Locale.ROOT,
                "Existing incline coverage: %d%% (%d%% previously suggested by this tool, %d%% other/surveyed)",
                inclineCoveragePct,
                pluginInclinePct,
                otherInclinePct);
    }

    public CompletionStatus status() {
        return CompletionLogic.statusOf(this);
    }

    public boolean isDone() {
        return status() == CompletionStatus.DONE;
    }
}
