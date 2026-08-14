package no.nvdbincline.core.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LocalNorwayExtractStatusTest {
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void missingWhenNoFile() {
        var st = LocalNorwayExtractStatus.missing("gone");
        assertEquals(LocalNorwayExtractStatus.Kind.MISSING, st.kind());
        assertFalse(st.isUsable());
        assertFalse(st.isFreshEnough());
    }

    @Test
    void currentWhenRecent() {
        var st =
                LocalNorwayExtractStatus.evaluate(
                        NOW.minus(Duration.ofDays(2)),
                        NOW.minus(Duration.ofDays(1)),
                        1_300_000_000L,
                        Duration.ofDays(14),
                        CLOCK);
        assertEquals(LocalNorwayExtractStatus.Kind.CURRENT, st.kind());
        assertTrue(st.isFreshEnough());
    }

    @Test
    void staleWhenOlderThanMaxAge() {
        var st =
                LocalNorwayExtractStatus.evaluate(
                        NOW.minus(Duration.ofDays(30)),
                        NOW.minus(Duration.ofDays(30)),
                        1_300_000_000L,
                        Duration.ofDays(14),
                        CLOCK);
        assertEquals(LocalNorwayExtractStatus.Kind.STALE, st.kind());
        assertTrue(st.isUsable());
        assertFalse(st.isFreshEnough());
    }
}
