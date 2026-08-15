package no.nvdbincline.core.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.tag.ExistingTagCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionLogicTest {
    private static KommuneCompletionRecord rec(
            int nummer,
            int matched,
            int accepted,
            int rejected,
            int pending,
            int unmatched,
            boolean dismissed,
            long last,
            Boolean override) {
        return new KommuneCompletionRecord(
                nummer,
                matched,
                accepted,
                rejected,
                pending,
                unmatched,
                dismissed,
                last,
                override,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1);
    }

    @Test
    void allAcceptedIsDone() {
        KommuneCompletionRecord r = rec(301, 5, 5, 0, 0, 0, true, 1L, null);
        assertEquals(CompletionStatus.DONE, CompletionLogic.statusOf(r));
    }

    @Test
    void somePendingNotDone() {
        KommuneCompletionRecord r = rec(301, 5, 2, 0, 3, 0, true, 1L, null);
        assertEquals(CompletionStatus.IN_PROGRESS, CompletionLogic.statusOf(r));
    }

    @Test
    void unmatchedUndismissedNotDone() {
        KommuneCompletionRecord r = rec(301, 5, 5, 0, 0, 2, false, 1L, null);
        assertEquals(CompletionStatus.IN_PROGRESS, CompletionLogic.statusOf(r));
    }

    @Test
    void unmatchedDismissedAllowsDone() {
        KommuneCompletionRecord r = rec(301, 5, 5, 0, 0, 2, true, 1L, null);
        assertEquals(CompletionStatus.DONE, CompletionLogic.statusOf(r));
    }

    @Test
    void manualOverrideForcesDone() {
        KommuneCompletionRecord r = rec(301, 5, 0, 0, 5, 9, false, 1L, true);
        assertEquals(CompletionStatus.DONE, CompletionLogic.statusOf(r));
    }

    @Test
    void manualReopenForcesInProgress() {
        KommuneCompletionRecord r = rec(301, 5, 5, 0, 0, 0, true, 1L, false);
        assertEquals(CompletionStatus.IN_PROGRESS, CompletionLogic.statusOf(r));
    }

    @Test
    void neverRunIsNotStarted() {
        assertEquals(
                CompletionStatus.NOT_STARTED,
                CompletionLogic.statusOf(KommuneCompletionRecord.empty(301)));
    }

    @Test
    void coverageLineFromScan() {
        ExistingTagCoverage cov = new ExistingTagCoverage(100, 34, 12, 22, 0, 0, 0, 0);
        KommuneCompletionRecord r = KommuneCompletionRecord.empty(301).withCoverage(cov);
        assertTrue(r.hasCoverage());
        assertEquals(
                "Existing incline coverage: 34% (12% previously suggested by this tool, 22% other/surveyed)",
                r.formatCoverageLine());
    }
}

class KommuneCompletionStoreTest {
    @Test
    void roundTripPersistence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kommune_completion.json");
        KommuneCompletionStore store = new KommuneCompletionStore();
        store.put(rec(301, 10, 7, 3, 0, 1, true, 1_700_000_000_000L, null));
        store.put(rec(5001, 2, 0, 0, 2, 0, false, 1_700_000_000_100L, false));
        ExistingTagCoverage cov = new ExistingTagCoverage(50, 20, 5, 15, 2, 1, 1, 0);
        store.put(store.getOrEmpty(301).withCoverage(cov));
        store.save(file);

        String raw = Files.readString(file);
        assertTrue(raw.contains("LOCAL ONLY"));
        assertTrue(raw.contains("Never uploaded"));
        assertTrue(raw.contains("inclineCoveragePct"));

        KommuneCompletionStore loaded = KommuneCompletionStore.load(file);
        assertEquals(store.get(301).orElseThrow(), loaded.get(301).orElseThrow());
        assertEquals(store.get(5001).orElseThrow(), loaded.get(5001).orElseThrow());
        assertEquals(40, loaded.get(301).orElseThrow().inclineCoveragePct());
        assertEquals(10, loaded.get(301).orElseThrow().pluginInclinePct());
        assertEquals(30, loaded.get(301).orElseThrow().otherInclinePct());
        assertFalse(loaded.get(9999).isPresent());
    }

    private static KommuneCompletionRecord rec(
            int nummer,
            int matched,
            int accepted,
            int rejected,
            int pending,
            int unmatched,
            boolean dismissed,
            long last,
            Boolean override) {
        return new KommuneCompletionRecord(
                nummer,
                matched,
                accepted,
                rejected,
                pending,
                unmatched,
                dismissed,
                last,
                override,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1);
    }
}

class ReviewSessionStatsTest {
    @Test
    void appliedUncheckedAreRejected() {
        ReviewModel model = new ReviewModel();
        model.rows()
                .add(
                        new ReviewModel.Row(
                                ReviewModel.Kind.WAY_TAGS,
                                ReviewModel.Section.INCLINES,
                                1,
                                "a",
                                MatchConfidence.HIGH,
                                Map.of("incline", "5%", "source:incline", "nvdb_estimate"),
                                null,
                                null,
                                false,
                                null,
                                null,
                                false,
                                true));
        model.rows()
                .add(
                        new ReviewModel.Row(
                                ReviewModel.Kind.WAY_TAGS,
                                ReviewModel.Section.INCLINES,
                                2,
                                "b",
                                MatchConfidence.MEDIUM,
                                Map.of("incline", "6%", "source:incline", "nvdb_estimate"),
                                null,
                                null,
                                false,
                                null,
                                null,
                                false,
                                false));
        ReviewSessionStats applied = ReviewSessionStats.fromReview(model, 2, 0, true);
        assertEquals(1, applied.accepted);
        assertEquals(1, applied.rejected);
        assertEquals(0, applied.pending);

        ReviewSessionStats cancelled = ReviewSessionStats.fromReview(model, 2, 0, false);
        assertEquals(1, cancelled.accepted);
        assertEquals(0, cancelled.rejected);
        assertEquals(1, cancelled.pending);
    }
}
