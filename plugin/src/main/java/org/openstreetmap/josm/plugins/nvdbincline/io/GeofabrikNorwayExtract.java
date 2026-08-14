package org.openstreetmap.josm.plugins.nvdbincline.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.nvdbincline.core.osm.LocalNorwayExtractStatus;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.Logging;

/**
 * On-demand Geofabrik Norway {@code .osm.pbf} download into the plugin data dir.
 *
 * <p>URL verified 2026-08 against
 * {@code https://download.geofabrik.de/europe/norway.html}:
 * {@code https://download.geofabrik.de/europe/norway-latest.osm.pbf} (~1.3&nbsp;GB).
 */
public final class GeofabrikNorwayExtract {
    public static final String DOWNLOAD_PAGE = "https://download.geofabrik.de/europe/norway.html";
    public static final String PBF_URL =
            "https://download.geofabrik.de/europe/norway-latest.osm.pbf";
    public static final Duration DEFAULT_MAX_AGE = Duration.ofDays(14);

    private static final Pattern OSM_UNTIL =
            Pattern.compile(
                    "contains all OSM data up to\\s+([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_SIZE =
            Pattern.compile("File size:\\s*([0-9.]+)\\s*(GB|MB)", Pattern.CASE_INSENSITIVE);

    private GeofabrikNorwayExtract() {}

    public record RemoteInfo(Instant osmDataUntil, long approximateBytes, String rawSnippet) {}

    public record LocalMeta(
            Instant osmDataUntil, Instant downloadedAt, long fileSizeBytes, String sourceUrl) {}

    public static Path pbfFile() {
        return LocalDataPaths.norwayExtractPbf();
    }

    public static Path metaFile() {
        return LocalDataPaths.norwayExtractMeta();
    }

    public static LocalNorwayExtractStatus status(Clock clock) {
        return status(clock, DEFAULT_MAX_AGE);
    }

    public static LocalNorwayExtractStatus status(Clock clock, Duration maxAge) {
        Path pbf = pbfFile();
        try {
            if (!Files.isRegularFile(pbf) || Files.size(pbf) <= 0) {
                return LocalNorwayExtractStatus.missing("No local Norway extract at " + pbf);
            }
            long size = Files.size(pbf);
            Optional<LocalMeta> meta = readMeta();
            Instant until = meta.map(LocalMeta::osmDataUntil).orElse(null);
            Instant downloaded = meta.map(LocalMeta::downloadedAt).orElse(null);
            if (downloaded == null) {
                downloaded = Files.getLastModifiedTime(pbf).toInstant();
            }
            return LocalNorwayExtractStatus.evaluate(until, downloaded, size, maxAge, clock);
        } catch (IOException e) {
            return LocalNorwayExtractStatus.missing(e.getMessage());
        }
    }

    public static Optional<LocalMeta> readMeta() {
        Path meta = metaFile();
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(meta, StandardCharsets.UTF_8);
            Instant until = parseFieldInstant(text, "osmDataUntil");
            Instant downloaded = parseFieldInstant(text, "downloadedAt");
            long size = parseFieldLong(text, "fileSizeBytes");
            String url = parseFieldString(text, "sourceUrl");
            return Optional.of(new LocalMeta(until, downloaded, size, url));
        } catch (Exception e) {
            Logging.warn("nvdb_incline: bad norway extract meta: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static RemoteInfo probeRemote() throws IOException {
        long headLen = -1;
        HttpURLConnection head = open(PBF_URL, "HEAD");
        try {
            int code = head.getResponseCode();
            if (code >= 200 && code < 400) {
                headLen = head.getContentLengthLong();
            }
        } finally {
            head.disconnect();
        }

        Instant until = null;
        long approx = headLen;
        String snippet = "";
        HttpURLConnection page = open(DOWNLOAD_PAGE, "GET");
        try {
            if (page.getResponseCode() >= 200 && page.getResponseCode() < 300) {
                try (InputStream in = page.getInputStream()) {
                    snippet = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Matcher m = OSM_UNTIL.matcher(snippet);
                if (m.find()) {
                    until = Instant.parse(m.group(1));
                }
                if (approx <= 0) {
                    Matcher sm = FILE_SIZE.matcher(snippet);
                    if (sm.find()) {
                        double n = Double.parseDouble(sm.group(1));
                        String unit = sm.group(2).toUpperCase(Locale.ROOT);
                        approx =
                                "GB".equals(unit)
                                        ? (long) (n * 1_000_000_000L)
                                        : (long) (n * 1_000_000L);
                    }
                }
            }
        } finally {
            page.disconnect();
        }
        return new RemoteInfo(until, approx, snippet.length() > 200 ? snippet.substring(0, 200) : snippet);
    }

    public static void download(ProgressMonitor progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        RemoteInfo remote = probeRemote();
        long expected = remote.approximateBytes();
        progress.subTask(
                "Downloading Norway extract from Geofabrik"
                        + (expected > 0
                                ? String.format(
                                        Locale.ROOT,
                                        " (about %.1f GB)…",
                                        expected / 1_000_000_000.0)
                                : "…"));

        Path dir = LocalDataPaths.dataDir();
        Files.createDirectories(dir);
        Path tmp = dir.resolve("norway-latest.osm.pbf.partial");
        Path dest = pbfFile();

        HttpURLConnection conn = open(PBF_URL, "GET");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Geofabrik HTTP " + code + " for " + PBF_URL);
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) {
                total = expected;
            }
            if (total > 0) {
                progress.setTicksCount(1000);
            }
            long read = 0;
            try (InputStream in = conn.getInputStream();
                    OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (progress.isCanceled()) {
                        throw new IOException("Download cancelled");
                    }
                    out.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        progress.setTicks((int) Math.min(1000, (read * 1000) / total));
                        progress.subTask(
                                String.format(
                                        Locale.ROOT,
                                        "Downloading Norway extract… %.0f / %.0f MB",
                                        read / 1_000_000.0,
                                        total / 1_000_000.0));
                    }
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writeMeta(
                    new LocalMeta(
                            remote.osmDataUntil(),
                            Instant.now(),
                            Files.size(dest),
                            PBF_URL));
        } finally {
            conn.disconnect();
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    public static void writeMeta(LocalMeta meta) throws IOException {
        String json =
                "{\n"
                        + "  \"osmDataUntil\": "
                        + jsonString(meta.osmDataUntil() == null ? null : meta.osmDataUntil().toString())
                        + ",\n"
                        + "  \"downloadedAt\": "
                        + jsonString(meta.downloadedAt() == null ? null : meta.downloadedAt().toString())
                        + ",\n"
                        + "  \"fileSizeBytes\": "
                        + meta.fileSizeBytes()
                        + ",\n"
                        + "  \"sourceUrl\": "
                        + jsonString(meta.sourceUrl())
                        + "\n"
                        + "}\n";
        Files.createDirectories(metaFile().getParent());
        Files.writeString(metaFile(), json, StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(300_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "nvdb_incline/0.2 (JOSM plugin; Geofabrik Norway extract)");
        return conn;
    }

    private static String jsonString(String v) {
        if (v == null) {
            return "null";
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Instant parseFieldInstant(String json, String field) {
        String v = parseFieldString(json, field);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long parseFieldLong(String json, String field) {
        Pattern p =
                Pattern.compile(
                        "\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+)",
                        Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return 0;
    }

    private static String parseFieldString(String json, String field) {
        Pattern p =
                Pattern.compile(
                        "\"" + Pattern.quote(field) + "\"\\s*:\\s*(null|\"([^\"]*)\")",
                        Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        if ("null".equalsIgnoreCase(m.group(1))) {
            return null;
        }
        return m.group(2);
    }
}
