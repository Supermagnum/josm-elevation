package no.nvdbincline.core.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.review.ReviewModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionLogicTest {
    @Test
    void allAcceptedIsDone() {
        KommuneCompletionRecord r =
                new KommuneCompletionRecord(301, 5, 5, 0, 0, 0, true, 1L, null);
        assertEquals(CompletionStatus.DONE, CompletionLogic.statusOf(r));
    }

    @Test
    void somePendingNotDone() {
        KommuneCompletionRecord r =
                new KommuneCompletionRecord(301, 5, 2, 0, 3, 0, true, 1L, null);
        assertEquals(CompletionStatus.IN_PROGRESS, CompletionLogic.statusOf(r));
    }

    @Test
    void unmatchedUndismissedNotDone() {
        KommuneCompletionRecord r =
                new KommuneCompletionRecord(301, 5, 5, 0, 0, 2, false, 1L, null);
        assertEquals(CompletionStatus.IN_PROGRESS, CompletionLogic.statusOf(r));
    }

    @Test
    void unmatchedDismissedAllowsDone() {
        KommuneCompletionRecord r =
                new KommuneCompletionRecord(301, 5, 5, 0, 0, 2, true, 1L, null);
        assertEquals(CompletionStatus.DONE, CompletionLogic.statusOf(r));
    }

    @Test
    void manualOverrideForcesDone() {
        KommuneCompletionRecord r =
                new KommuneCompletionRecord(301, 5, 0, 0, 5, 9, false, 1L, true);
        assertEquals(CompletionStatus.DONE, CompletionLogic.statusOf(r));
    }

    @Test
    void manualReopenForcesInProgress() {
        KommuneCompletionRecord r =
                new KommuneCompletionRecord(301, 5, 5, 0, 0, 0, true, 1L, false);
        assertEquals(CompletionStatus.IN_PROGRESS, CompletionLogic.statusOf(r));
    }

    @Test
    void neverRunIsNotStarted() {
        assertEquals(
                CompletionStatus.NOT_STARTED,
                CompletionLogic.statusOf(KommuneCompletionRecord.empty(301)));
    }
}

class KommuneCompletionStoreTest {
    @Test
    void roundTripPersistence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kommune_completion.json");
        KommuneCompletionStore store = new KommuneCompletionStore();
        store.put(
                new KommuneCompletionRecord(301, 10, 7, 3, 0, 1, true, 1_700_000_000_000L, null));
        store.put(
                new KommuneCompletionRecord(5001, 2, 0, 0, 2, 0, false, 1_700_000_000_100L, false));
        store.save(file);

        String raw = Files.readString(file);
        assertTrue(raw.contains("LOCAL ONLY"));
        assertTrue(raw.contains("Never uploaded"));

        KommuneCompletionStore loaded = KommuneCompletionStore.load(file);
        assertEquals(store.get(301).orElseThrow(), loaded.get(301).orElseThrow());
        assertEquals(store.get(5001).orElseThrow(), loaded.get(5001).orElseThrow());
        assertFalse(loaded.get(9999).isPresent());
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
