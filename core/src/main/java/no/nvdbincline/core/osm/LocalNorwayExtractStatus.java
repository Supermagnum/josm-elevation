package no.nvdbincline.core.osm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Status of the on-disk Geofabrik Norway extract used by kommune mode.
 *
 * <p>Pure logic — no I/O. UI and download code map files into this model.
 */
public final class LocalNorwayExtractStatus {
    public enum Kind {
        MISSING,
        STALE,
        CURRENT
    }

    private final Kind kind;
    private final Instant osmDataUntil;
    private final Instant downloadedAt;
    private final long fileSizeBytes;
    private final Duration maxAge;
    private final String detail;

    public LocalNorwayExtractStatus(
            Kind kind,
            Instant osmDataUntil,
            Instant downloadedAt,
            long fileSizeBytes,
            Duration maxAge,
            String detail) {
        this.kind = Objects.requireNonNull(kind);
        this.osmDataUntil = osmDataUntil;
        this.downloadedAt = downloadedAt;
        this.fileSizeBytes = fileSizeBytes;
        this.maxAge = maxAge == null ? Duration.ofDays(14) : maxAge;
        this.detail = detail == null ? "" : detail;
    }

    public Kind kind() {
        return kind;
    }

    public Optional<Instant> osmDataUntil() {
        return Optional.ofNullable(osmDataUntil);
    }

    public Optional<Instant> downloadedAt() {
        return Optional.ofNullable(downloadedAt);
    }

    public long fileSizeBytes() {
        return fileSizeBytes;
    }

    public Duration maxAge() {
        return maxAge;
    }

    public String detail() {
        return detail;
    }

    public boolean isUsable() {
        return kind == Kind.CURRENT || kind == Kind.STALE;
    }

    /** Kommune mode requires CURRENT unless the user explicitly accepts stale. */
    public boolean isFreshEnough() {
        return kind == Kind.CURRENT;
    }

    public static LocalNorwayExtractStatus missing(String detail) {
        return new LocalNorwayExtractStatus(Kind.MISSING, null, null, 0, Duration.ofDays(14), detail);
    }

    public static LocalNorwayExtractStatus evaluate(
            Instant osmDataUntil,
            Instant downloadedAt,
            long fileSizeBytes,
            Duration maxAge,
            Clock clock) {
        Objects.requireNonNull(clock, "clock");
        Duration ageLimit = maxAge == null ? Duration.ofDays(14) : maxAge;
        if (fileSizeBytes <= 0) {
            return missing("Extract file is missing or empty");
        }
        Instant ref = osmDataUntil != null ? osmDataUntil : downloadedAt;
        if (ref == null) {
            return new LocalNorwayExtractStatus(
                    Kind.STALE,
                    null,
                    downloadedAt,
                    fileSizeBytes,
                    ageLimit,
                    "Extract present but has no timestamp metadata");
        }
        Instant now = clock.instant();
        if (ref.isBefore(now.minus(ageLimit))) {
            return new LocalNorwayExtractStatus(
                    Kind.STALE,
                    osmDataUntil,
                    downloadedAt,
                    fileSizeBytes,
                    ageLimit,
                    "Local OSM data older than " + ageLimit.toDays() + " days");
        }
        return new LocalNorwayExtractStatus(
                Kind.CURRENT, osmDataUntil, downloadedAt, fileSizeBytes, ageLimit, "up to date");
    }
}
