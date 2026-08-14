package no.nvdbincline.core.kommune;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import no.nvdbincline.core.geo.LonLatMultiPolygon;
import no.nvdbincline.core.geo.LonLatMultiPolygon.Polygon;
import no.nvdbincline.core.geo.LonLatMultiPolygon.Ring;

/**
 * Bundled Kartverket kommune boundary polygons keyed by kommunenummer.
 *
 * <p>Refresh with {@code tools/refresh_kommune_boundaries.py}. Missing numbers
 * fail loudly — never silently fall back to a bbox.
 */
public final class KommuneBoundaryCatalog {
    public static final String DEFAULT_RESOURCE =
            "/no/nvdbincline/core/kommune/kommune_boundaries_2026-01-01.json";

    private final String source;
    private final String referenceDate;
    private final String crs;
    private final Map<Integer, KommuneBoundary> byNummer;

    public KommuneBoundaryCatalog(
            String source,
            String referenceDate,
            String crs,
            Map<Integer, KommuneBoundary> byNummer) {
        this.source = source == null ? "" : source;
        this.referenceDate = referenceDate == null ? "" : referenceDate;
        this.crs = crs == null ? "" : crs;
        this.byNummer = Map.copyOf(byNummer);
    }

    public String source() {
        return source;
    }

    public String referenceDate() {
        return referenceDate;
    }

    public String crs() {
        return crs;
    }

    public int size() {
        return byNummer.size();
    }

    public Optional<KommuneBoundary> find(int nummer) {
        return Optional.ofNullable(byNummer.get(nummer));
    }

    /**
     * @throws IllegalArgumentException if this kommunenummer has no bundled polygon
     */
    public KommuneBoundary require(int nummer) {
        KommuneBoundary b = byNummer.get(nummer);
        if (b == null) {
            throw new IllegalArgumentException(
                    "No Kartverket boundary polygon bundled for kommunenummer "
                            + String.format(Locale.ROOT, "%04d", nummer)
                            + " (catalog size "
                            + byNummer.size()
                            + ", reference "
                            + referenceDate
                            + "). Re-run tools/refresh_kommune_boundaries.py after a kommune"
                            + " merger/renumbering.");
        }
        return b;
    }

    public static KommuneBoundaryCatalog loadDefault() throws IOException {
        return loadResource(DEFAULT_RESOURCE);
    }

    public static KommuneBoundaryCatalog loadResource(String resourcePath) throws IOException {
        InputStream in = KommuneBoundaryCatalog.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("Missing resource: " + resourcePath);
        }
        try (in) {
            return parse(new InputStreamReader(new BufferedInputStream(in), StandardCharsets.UTF_8));
        }
    }

    public static KommuneBoundaryCatalog parse(Reader reader) throws IOException {
        String json = readAll(reader);
        return parseJson(json);
    }

    /** Minimal JSON parse for the compact boundaries format (no Jackson in core). */
    static KommuneBoundaryCatalog parseJson(String json) {
        JsonTok tok = new JsonTok(json);
        tok.expect('{');
        String source = "";
        String referenceDate = "";
        String crs = "";
        Map<Integer, KommuneBoundary> map = new HashMap<>();
        while (!tok.peek('}')) {
            String key = tok.string();
            tok.expect(':');
            switch (key) {
                case "source" -> source = tok.string();
                case "reference_date" -> referenceDate = tok.string();
                case "crs" -> crs = tok.string();
                case "source_url", "note", "simplify_tolerance_deg" -> tok.skipValue();
                case "kommuner" -> {
                    tok.expect('{');
                    while (!tok.peek('}')) {
                        String numKey = tok.string();
                        tok.expect(':');
                        KommuneBoundary b = parseBoundary(tok, numKey);
                        map.put(b.nummer(), b);
                        tok.optional(',');
                    }
                    tok.expect('}');
                }
                default -> tok.skipValue();
            }
            tok.optional(',');
        }
        tok.expect('}');
        if (map.isEmpty()) {
            throw new IllegalArgumentException("boundaries JSON has no kommuner");
        }
        return new KommuneBoundaryCatalog(source, referenceDate, crs, map);
    }

    private static KommuneBoundary parseBoundary(JsonTok tok, String numKey) {
        int nummer;
        try {
            nummer = Integer.parseInt(numKey);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad kommunenummer key: " + numKey, e);
        }
        tok.expect('{');
        String navn = "";
        List<Polygon> polygons = new ArrayList<>();
        while (!tok.peek('}')) {
            String key = tok.string();
            tok.expect(':');
            switch (key) {
                case "navn" -> navn = tok.string();
                case "polygons" -> {
                    tok.expect('[');
                    while (!tok.peek(']')) {
                        polygons.add(parsePolygon(tok));
                        tok.optional(',');
                    }
                    tok.expect(']');
                }
                default -> tok.skipValue();
            }
            tok.optional(',');
        }
        tok.expect('}');
        if (polygons.isEmpty()) {
            throw new IllegalArgumentException("kommune " + numKey + " has no polygons");
        }
        return new KommuneBoundary(nummer, navn, new LonLatMultiPolygon(polygons));
    }

    private static Polygon parsePolygon(JsonTok tok) {
        tok.expect('[');
        List<Ring> rings = new ArrayList<>();
        while (!tok.peek(']')) {
            rings.add(parseRing(tok));
            tok.optional(',');
        }
        tok.expect(']');
        return new Polygon(rings);
    }

    private static Ring parseRing(JsonTok tok) {
        tok.expect('[');
        List<double[]> pts = new ArrayList<>();
        while (!tok.peek(']')) {
            tok.expect('[');
            double lon = tok.number();
            tok.expect(',');
            double lat = tok.number();
            tok.expect(']');
            pts.add(new double[] {lon, lat});
            tok.optional(',');
        }
        tok.expect(']');
        return new Ring(pts);
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) >= 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** Tiny JSON tokenizer for this fixed schema. */
    static final class JsonTok {
        private final String s;
        private int i;

        JsonTok(String s) {
            this.s = s;
            this.i = 0;
            skipWs();
        }

        void expect(char c) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException(
                        "expected '" + c + "' at " + i + " got "
                                + (i < s.length() ? s.charAt(i) : "EOF"));
            }
            i++;
            skipWs();
        }

        boolean peek(char c) {
            skipWs();
            return i < s.length() && s.charAt(i) == c;
        }

        void optional(char c) {
            skipWs();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                skipWs();
            }
        }

        String string() {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected string at " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("truncated escape");
                    }
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"', '\\', '/' -> sb.append(n);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            skipWs();
            return sb.toString();
        }

        double number() {
            skipWs();
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
            }
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '+')) {
                i++;
            }
            double v = Double.parseDouble(s.substring(start, i));
            skipWs();
            return v;
        }

        void skipValue() {
            skipWs();
            if (i >= s.length()) {
                return;
            }
            char c = s.charAt(i);
            if (c == '"') {
                string();
            } else if (c == '{') {
                i++;
                while (!peek('}')) {
                    string();
                    expect(':');
                    skipValue();
                    optional(',');
                }
                expect('}');
            } else if (c == '[') {
                i++;
                while (!peek(']')) {
                    skipValue();
                    optional(',');
                }
                expect(']');
            } else if (c == 't' || c == 'f' || c == 'n') {
                while (i < s.length() && Character.isLetter(s.charAt(i))) {
                    i++;
                }
                skipWs();
            } else {
                number();
            }
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
