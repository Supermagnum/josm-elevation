package org.openstreetmap.josm.plugins.nvdbincline.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.geo.WktParser;
import no.nvdbincline.core.model.NvdbLink;
import no.nvdbincline.core.model.Polyline;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Read-only NVDB API client. Never talks to the OSM write API.
 * Results are cached under the JOSM plugin preferences directory.
 */
public class NvdbClient {
    public static final String DEFAULT_BASE = "https://nvdbapiles.atlas.vegvesen.no";
    public static final String USER_AGENT =
            "josm-nvdb-incline/0.1 (JOSM plugin; read-only; does not write to OpenStreetMap)";
    public static final String X_CLIENT = "josm-nvdb-incline";

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Path cacheDir;
    private final long minIntervalMs;
    private long lastCallMs;

    public NvdbClient() {
        this(DEFAULT_BASE, defaultCacheDir(), 250);
    }

    public NvdbClient(String baseUrl, Path cacheDir, long minIntervalMs) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.cacheDir = cacheDir;
        this.minIntervalMs = minIntervalMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {
            // cache optional
        }
    }

    static Path defaultCacheDir() {
        try {
            String home = Config.getDirs().getCacheDirectory(true).getAbsolutePath();
            return Path.of(home, "nvdb_incline");
        } catch (Exception e) {
            return Path.of(System.getProperty("java.io.tmpdir"), "nvdb_incline_cache");
        }
    }

    /** Fetch segmented links for a WGS84 bbox. */
    public List<NvdbLink> fetchSegmentedLinks(
            double minLon, double minLat, double maxLon, double maxLat)
            throws IOException, InterruptedException {
        double[] a = Utm33.lonLatToUtm(minLon, minLat);
        double[] b = Utm33.lonLatToUtm(maxLon, maxLat);
        double minX = Math.min(a[0], b[0]);
        double minY = Math.min(a[1], b[1]);
        double maxX = Math.max(a[0], b[0]);
        double maxY = Math.max(a[1], b[1]);
        String kartutsnitt =
                String.format(Locale.ROOT, "%s,%s,%s,%s", minX, minY, maxX, maxY);

        List<NvdbLink> all = new ArrayList<>();
        String start = null;
        int page = 0;
        while (true) {
            StringBuilder q = new StringBuilder();
            q.append("srid=5973&antall=1000&inkluderAntall=false&kartutsnitt=")
                    .append(URLEncoder.encode(kartutsnitt, StandardCharsets.UTF_8));
            if (start != null) {
                q.append("&start=").append(URLEncoder.encode(start, StandardCharsets.UTF_8));
            }
            String url = baseUrl + "/vegnett/api/v4/veglenkesekvenser/segmentert?" + q;
            JsonNode root = getJson(url, "segmentert_p" + page + "_" + hash(kartutsnitt));
            JsonNode objekter = root.get("objekter");
            if (objekter == null || !objekter.isArray() || objekter.isEmpty()) {
                break;
            }
            for (JsonNode obj : objekter) {
                NvdbLink link = parseLink(obj);
                if (link != null) {
                    all.add(link);
                }
            }
            JsonNode neste = root.path("metadata").path("neste").path("start");
            if (neste.isMissingNode() || neste.isNull() || neste.asText().isBlank()) {
                break;
            }
            start = neste.asText();
            page++;
            if (page > 500) {
                break;
            }
        }
        return all;
    }

    /** Parse NVDB link JSON (also used by fixture-based tests). */
    public static NvdbLink parseLink(JsonNode obj) {
        JsonNode geom = obj.get("geometri");
        if (geom == null || !geom.has("wkt")) {
            return null;
        }
        try {
            Polyline line = WktParser.parseLineString(geom.get("wkt").asText());
            String medium = null;
            if (geom.has("medium") && !geom.get("medium").isNull()) {
                medium = geom.get("medium").asText();
            } else if (obj.has("medium") && !obj.get("medium").isNull()) {
                medium = obj.get("medium").asText();
            }
            return new NvdbLink(
                    obj.path("veglenkesekvensid").asLong(0),
                    obj.path("kortform").asText(""),
                    obj.path("type").asText(""),
                    obj.path("typeVeg").asText(""),
                    medium,
                    obj.path("startposisjon").asDouble(0),
                    obj.path("sluttposisjon").asDouble(1),
                    line);
        } catch (RuntimeException e) {
            return null;
        }
    }

    protected JsonNode getJson(String url, String cacheKey) throws IOException, InterruptedException {
        Path cacheFile = cacheDir.resolve(cacheKey + ".json");
        if (Files.isRegularFile(cacheFile)) {
            return mapper.readTree(Files.readString(cacheFile));
        }
        pace();
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(120))
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .header("X-Client", X_CLIENT)
                        .GET()
                        .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("NVDB HTTP " + resp.statusCode() + " for " + url);
        }
        Files.writeString(cacheFile, resp.body());
        return mapper.readTree(resp.body());
    }

    private synchronized void pace() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = minIntervalMs - (now - lastCallMs);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCallMs = System.currentTimeMillis();
    }

    private static String hash(String s) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
