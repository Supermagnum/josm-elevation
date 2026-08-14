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

    /** Fetch segmented links for a WGS84 bbox ({@code kartutsnitt}). */
    public List<NvdbLink> fetchSegmentedLinks(
            double minLon, double minLat, double maxLon, double maxLat)
            throws IOException, InterruptedException {
        return fetchSegmentedLinksInternal(areaQueryBbox(minLon, minLat, maxLon, maxLat));
    }

    /**
     * Fetch segmented links for one kommune via NVDB's {@code kommune=} filter
     * (preferred over bbox for kommune mode — exact administrative filter).
     */
    public List<NvdbLink> fetchSegmentedLinksByKommune(int kommuneNummer)
            throws IOException, InterruptedException {
        if (kommuneNummer <= 0) {
            throw new IllegalArgumentException("kommunenummer");
        }
        return fetchSegmentedLinksInternal("kommune=" + kommuneNummer);
    }

    private List<NvdbLink> fetchSegmentedLinksInternal(String areaQuery)
            throws IOException, InterruptedException {
        List<NvdbLink> all = new ArrayList<>();
        String start = null;
        int page = 0;
        String cacheStem = "segmentert_" + hash(areaQuery);
        while (true) {
            StringBuilder q = new StringBuilder();
            q.append("srid=5973&antall=1000&inkluderAntall=false&").append(areaQuery);
            if (start != null) {
                q.append("&start=").append(URLEncoder.encode(start, StandardCharsets.UTF_8));
            }
            String url = baseUrl + "/vegnett/api/v4/veglenkesekvenser/segmentert?" + q;
            JsonNode root = getJson(url, cacheStem + "_p" + page);
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

    /**
     * Fetch NVDB vegobjekter of a given type for a WGS84 bbox (points with geometry).
     * Used for Skiltplate (96) and Trafikkulykke (570).
     */
    public List<no.nvdbincline.core.model.NvdbPointFeature> fetchVegobjektPoints(
            long typeId, double minLon, double minLat, double maxLon, double maxLat)
            throws IOException, InterruptedException {
        return fetchVegobjektPointsInternal(
                typeId, areaQueryBbox(minLon, minLat, maxLon, maxLat));
    }

    /** Same as {@link #fetchVegobjektPoints} but filtered by NVDB {@code kommune=}. */
    public List<no.nvdbincline.core.model.NvdbPointFeature> fetchVegobjektPointsByKommune(
            long typeId, int kommuneNummer) throws IOException, InterruptedException {
        if (kommuneNummer <= 0) {
            throw new IllegalArgumentException("kommunenummer");
        }
        return fetchVegobjektPointsInternal(typeId, "kommune=" + kommuneNummer);
    }

    private List<no.nvdbincline.core.model.NvdbPointFeature> fetchVegobjektPointsInternal(
            long typeId, String areaQuery) throws IOException, InterruptedException {
        List<no.nvdbincline.core.model.NvdbPointFeature> all = new ArrayList<>();
        String start = null;
        int page = 0;
        String cacheStem = "vegobjekt_" + typeId + "_" + hash(areaQuery);
        while (true) {
            StringBuilder q = new StringBuilder();
            q.append("srid=5973&antall=1000&inkluderAntall=false&inkluder=egenskaper,geometri&")
                    .append(areaQuery);
            if (start != null) {
                q.append("&start=").append(URLEncoder.encode(start, StandardCharsets.UTF_8));
            }
            String url = baseUrl + "/vegobjekter/api/v4/vegobjekter/" + typeId + "?" + q;
            JsonNode root = getJson(url, cacheStem + "_p" + page);
            JsonNode objekter = root.get("objekter");
            if (objekter == null || !objekter.isArray() || objekter.isEmpty()) {
                break;
            }
            for (JsonNode obj : objekter) {
                no.nvdbincline.core.model.NvdbPointFeature pt = parsePointFeature(typeId, obj);
                if (pt != null) {
                    all.add(pt);
                }
            }
            JsonNode neste = root.path("metadata").path("neste").path("start");
            if (neste.isMissingNode() || neste.isNull() || neste.asText().isBlank()) {
                break;
            }
            start = neste.asText();
            page++;
            if (page > 200) {
                break;
            }
        }
        return all;
    }

    private static String areaQueryBbox(double minLon, double minLat, double maxLon, double maxLat) {
        double[] a = Utm33.lonLatToUtm(minLon, minLat);
        double[] b = Utm33.lonLatToUtm(maxLon, maxLat);
        double minX = Math.min(a[0], b[0]);
        double minY = Math.min(a[1], b[1]);
        double maxX = Math.max(a[0], b[0]);
        double maxY = Math.max(a[1], b[1]);
        String kartutsnitt =
                String.format(Locale.ROOT, "%s,%s,%s,%s", minX, minY, maxX, maxY);
        return "kartutsnitt=" + URLEncoder.encode(kartutsnitt, StandardCharsets.UTF_8);
    }

    /** Parse a vegobjekt point (also used by fixture tests). */
    public static no.nvdbincline.core.model.NvdbPointFeature parsePointFeature(
            long typeId, JsonNode obj) {
        JsonNode geom = obj.get("geometri");
        if (geom == null || !geom.has("wkt")) {
            return null;
        }
        String wkt = geom.get("wkt").asText();
        double x;
        double y;
        try {
            if (wkt.toUpperCase(Locale.ROOT).contains("LINESTRING")) {
                Polyline line = WktParser.parseLineString(wkt);
                x = line.get(0).x();
                y = line.get(0).y();
            } else {
                // POINT Z (x y z) or POINT (x y)
                String body = wkt.substring(wkt.indexOf('(') + 1, wkt.lastIndexOf(')')).trim();
                String[] parts = body.split("\\s+");
                x = Double.parseDouble(parts[0]);
                y = Double.parseDouble(parts[1]);
            }
        } catch (RuntimeException e) {
            return null;
        }
        String skiltnummer = null;
        String dateIso = null;
        String label = "";
        JsonNode props = obj.get("egenskaper");
        if (props != null && props.isArray()) {
            for (JsonNode p : props) {
                int id = p.path("id").asInt(0);
                String name = p.path("navn").asText("").toLowerCase(Locale.ROOT);
                String verdi =
                        p.has("verdi")
                                ? p.get("verdi").asText()
                                : (p.has("enum_verdi")
                                        ? p.path("enum_verdi").path("verdi").asText("")
                                        : "");
                // Skiltnummer property id 5530; Trafikkulykke date 5055
                if (id == 5530 || name.contains("skiltnummer")) {
                    skiltnummer = verdi;
                }
                if (id == 5055 || name.contains("ulykkesdato") || name.equals("dato")) {
                    dateIso = verdi;
                }
                if (name.equals("navn") && !verdi.isBlank()) {
                    label = verdi;
                }
            }
        }
        if (label.isBlank() && skiltnummer != null) {
            label = skiltnummer;
        }
        return new no.nvdbincline.core.model.NvdbPointFeature(
                typeId,
                obj.path("id").asLong(0),
                x,
                y,
                label,
                dateIso,
                skiltnummer);
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
