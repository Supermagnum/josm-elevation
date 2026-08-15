package no.nvdbincline.core.completion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Local JSON store of per-kommune completion records.
 *
 * <p>File header comment (first lines) states explicitly that this is personal
 * machine-local bookkeeping and must never be uploaded or shared as map data.
 */
public final class KommuneCompletionStore {
    public static final String FILE_HEADER =
            """
            # nvdb_incline kommune completion — LOCAL ONLY
            # Personal progress on this machine. Never uploaded to OpenStreetMap.
            # Never shared between users. Not map data. Safe to delete to reset.
            """;

    private final Map<Integer, KommuneCompletionRecord> byNummer = new LinkedHashMap<>();

    public Optional<KommuneCompletionRecord> get(int kommuneNummer) {
        return Optional.ofNullable(byNummer.get(kommuneNummer));
    }

    public KommuneCompletionRecord getOrEmpty(int kommuneNummer) {
        return byNummer.getOrDefault(kommuneNummer, KommuneCompletionRecord.empty(kommuneNummer));
    }

    public void put(KommuneCompletionRecord record) {
        byNummer.put(record.kommuneNummer(), record);
    }

    public Collection<KommuneCompletionRecord> all() {
        return Collections.unmodifiableCollection(byNummer.values());
    }

    public void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(FILE_HEADER);
        sb.append("{\n");
        sb.append("  \"local_only\": true,\n");
        sb.append("  \"description\": \"Personal kommune completion for nvdb_incline. Not OSM data.\",\n");
        sb.append("  \"kommuner\": {\n");
        boolean first = true;
        for (KommuneCompletionRecord r : byNummer.values()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    \"").append(r.kommuneNummer()).append("\": {\n");
            sb.append("      \"matchedWays\": ").append(r.matchedWays()).append(",\n");
            sb.append("      \"accepted\": ").append(r.accepted()).append(",\n");
            sb.append("      \"rejected\": ").append(r.rejected()).append(",\n");
            sb.append("      \"pending\": ").append(r.pending()).append(",\n");
            sb.append("      \"unmatched\": ").append(r.unmatched()).append(",\n");
            sb.append("      \"unmatchedDismissed\": ").append(r.unmatchedDismissed()).append(",\n");
            sb.append("      \"lastRunEpochMilli\": ").append(r.lastRunEpochMilli()).append(",\n");
            sb.append("      \"manualOverride\": ");
            if (r.manualOverride() == null) {
                sb.append("null");
            } else {
                sb.append(r.manualOverride());
            }
            sb.append(",\n");
            sb.append("      \"inclineCoveragePct\": ").append(r.inclineCoveragePct()).append(",\n");
            sb.append("      \"pluginInclinePct\": ").append(r.pluginInclinePct()).append(",\n");
            sb.append("      \"otherInclinePct\": ").append(r.otherInclinePct()).append(",\n");
            sb.append("      \"hazardCount\": ").append(r.hazardCount()).append(",\n");
            sb.append("      \"pluginHazardCount\": ").append(r.pluginHazardCount()).append(",\n");
            sb.append("      \"otherHazardCount\": ").append(r.otherHazardCount()).append(",\n");
            sb.append("      \"chainAdvisoryCount\": ").append(r.chainAdvisoryCount()).append("\n");
            sb.append("    }");
        }
        sb.append("\n  }\n}\n");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public static KommuneCompletionStore load(Path file) throws IOException {
        KommuneCompletionStore store = new KommuneCompletionStore();
        if (!Files.isRegularFile(file)) {
            return store;
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        // Strip #-comment lines (header).
        StringBuilder json = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                continue;
            }
            json.append(line).append('\n');
        }
        parseInto(store, json.toString());
        return store;
    }

    static void parseInto(KommuneCompletionStore store, String json) {
        int kommunerKey = json.indexOf("\"kommuner\"");
        if (kommunerKey < 0) {
            return;
        }
        int brace = json.indexOf('{', kommunerKey);
        if (brace < 0) {
            return;
        }
        int depth = 0;
        int end = -1;
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("malformed completion JSON");
        }
        String body = json.substring(brace + 1, end);
        int pos = 0;
        while (pos < body.length()) {
            int q1 = body.indexOf('"', pos);
            if (q1 < 0) {
                break;
            }
            int q2 = body.indexOf('"', q1 + 1);
            if (q2 < 0) {
                break;
            }
            String key = body.substring(q1 + 1, q2);
            int objStart = body.indexOf('{', q2);
            if (objStart < 0) {
                break;
            }
            int objEnd = body.indexOf('}', objStart);
            if (objEnd < 0) {
                throw new IllegalArgumentException("unclosed kommune record");
            }
            String obj = body.substring(objStart, objEnd + 1);
            int nummer = Integer.parseInt(key.trim());
            store.put(parseRecord(nummer, obj));
            pos = objEnd + 1;
        }
    }

    private static KommuneCompletionRecord parseRecord(int nummer, String obj) {
        int matched = intField(obj, "matchedWays", 0);
        int accepted = intField(obj, "accepted", 0);
        int rejected = intField(obj, "rejected", 0);
        int pending = intField(obj, "pending", 0);
        int unmatched = intField(obj, "unmatched", 0);
        boolean dismissed = boolField(obj, "unmatchedDismissed", false);
        long last = longField(obj, "lastRunEpochMilli", 0L);
        Boolean override = nullableBoolField(obj, "manualOverride");
        int inclinePct = intField(obj, "inclineCoveragePct", -1);
        int pluginPct = intField(obj, "pluginInclinePct", -1);
        int otherPct = intField(obj, "otherInclinePct", -1);
        int hazard = intField(obj, "hazardCount", -1);
        int pluginH = intField(obj, "pluginHazardCount", -1);
        int otherH = intField(obj, "otherHazardCount", -1);
        int chain = intField(obj, "chainAdvisoryCount", -1);
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
                inclinePct,
                pluginPct,
                otherPct,
                hazard,
                pluginH,
                otherH,
                chain);
    }

    private static int intField(String obj, String name, int def) {
        Long v = numberField(obj, name);
        return v == null ? def : v.intValue();
    }

    private static long longField(String obj, String name, long def) {
        Long v = numberField(obj, name);
        return v == null ? def : v;
    }

    private static Long numberField(String obj, String name) {
        String key = "\"" + name + "\"";
        int i = obj.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = obj.indexOf(':', i + key.length());
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) {
            p++;
        }
        int start = p;
        if (p < obj.length() && obj.charAt(p) == '-') {
            p++;
        }
        while (p < obj.length() && Character.isDigit(obj.charAt(p))) {
            p++;
        }
        if (start == p) {
            return null;
        }
        return Long.parseLong(obj.substring(start, p));
    }

    private static boolean boolField(String obj, String name, boolean def) {
        Boolean b = nullableBoolField(obj, name);
        return b == null ? def : b;
    }

    private static Boolean nullableBoolField(String obj, String name) {
        String key = "\"" + name + "\"";
        int i = obj.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = obj.indexOf(':', i + key.length());
        if (colon < 0) {
            return null;
        }
        String rest = obj.substring(colon + 1).trim();
        if (rest.startsWith("null")) {
            return null;
        }
        if (rest.startsWith("true")) {
            return true;
        }
        if (rest.startsWith("false")) {
            return false;
        }
        return null;
    }
}
