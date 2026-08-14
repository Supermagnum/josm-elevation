package no.nvdbincline.core.kommune;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bundled kommune list (nummer → navn). Loaded from classpath JSON produced by
 * {@code tools/refresh_kommune_list.py}. Never fetched at plugin startup.
 */
public final class KommuneCatalog {
    public static final String DEFAULT_RESOURCE =
            "/no/nvdbincline/core/kommune/kommuner_2024-01-01.json";

    private final String effectiveDate;
    private final String source;
    private final List<Kommune> kommuner;
    private final Map<Integer, Kommune> byNummer;

    public KommuneCatalog(String effectiveDate, String source, List<Kommune> kommuner) {
        this.effectiveDate = effectiveDate == null ? "" : effectiveDate;
        this.source = source == null ? "" : source;
        List<Kommune> copy = List.copyOf(kommuner);
        this.kommuner = copy;
        Map<Integer, Kommune> map = new LinkedHashMap<>();
        for (Kommune k : copy) {
            map.put(k.nummer(), k);
        }
        this.byNummer = Collections.unmodifiableMap(map);
    }

    public String effectiveDate() {
        return effectiveDate;
    }

    public String source() {
        return source;
    }

    public List<Kommune> all() {
        return kommuner;
    }

    public Optional<Kommune> byNummer(int nummer) {
        return Optional.ofNullable(byNummer.get(nummer));
    }

    /** Case-insensitive substring filter on name (and nummer as string). */
    public List<Kommune> search(String query) {
        return KommuneSearch.filter(kommuner, query);
    }

    public static KommuneCatalog loadDefault() {
        try (InputStream in = KommuneCatalog.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled resource " + DEFAULT_RESOURCE);
            }
            return parseJson(readUtf8(in));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + DEFAULT_RESOURCE, e);
        }
    }

    public static KommuneCatalog parseJson(String json) {
        String effectiveDate = extractStringField(json, "effective_date");
        String source = extractStringField(json, "source");
        int arrStart = json.indexOf("\"kommuner\"");
        if (arrStart < 0) {
            throw new IllegalArgumentException("kommune catalog JSON missing \"kommuner\" array");
        }
        int bracket = json.indexOf('[', arrStart);
        int end = json.lastIndexOf(']');
        if (bracket < 0 || end < bracket) {
            throw new IllegalArgumentException("kommune catalog JSON: malformed kommuner array");
        }
        String body = json.substring(bracket + 1, end);
        List<Kommune> list = new ArrayList<>();
        int pos = 0;
        while (true) {
            int objStart = body.indexOf('{', pos);
            if (objStart < 0) {
                break;
            }
            int objEnd = body.indexOf('}', objStart);
            if (objEnd < 0) {
                throw new IllegalArgumentException("kommune catalog JSON: unclosed object");
            }
            String obj = body.substring(objStart, objEnd + 1);
            int nummer = extractIntField(obj, "nummer");
            String navn = extractStringField(obj, "navn");
            if (nummer <= 0 || navn == null || navn.isBlank()) {
                throw new IllegalArgumentException("invalid kommune object: " + obj);
            }
            Integer fylkesnummer = null;
            String fylkesnavn = null;
            if (obj.contains("\"fylkesnummer\"")) {
                fylkesnummer = extractIntField(obj, "fylkesnummer");
            }
            if (obj.contains("\"fylkesnavn\"")) {
                fylkesnavn = extractStringField(obj, "fylkesnavn");
            }
            list.add(new Kommune(nummer, navn, fylkesnummer, fylkesnavn));
            pos = objEnd + 1;
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("kommune catalog JSON has zero kommuner");
        }
        return new KommuneCatalog(effectiveDate, source, list);
    }

    private static String readUtf8(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String extractStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int p = q1 + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                sb.append(json.charAt(p + 1));
                p++;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    private static int extractIntField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            throw new IllegalArgumentException("missing field " + field);
        }
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) {
            throw new IllegalArgumentException("missing value for " + field);
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        int start = p;
        if (p < json.length() && json.charAt(p) == '-') {
            p++;
        }
        while (p < json.length() && Character.isDigit(json.charAt(p))) {
            p++;
        }
        if (start == p || (json.charAt(start) == '-' && start + 1 == p)) {
            throw new IllegalArgumentException("non-integer " + field);
        }
        return Integer.parseInt(json.substring(start, p));
    }

    /** Normalize for search: lower-case, keep Norwegian letters. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
