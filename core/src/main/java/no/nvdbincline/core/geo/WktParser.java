package no.nvdbincline.core.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.Polyline;

/** Minimal WKT LINESTRING / LINESTRING Z parser for NVDB geometry. */
public final class WktParser {
    private static final Pattern NUM =
            Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?");

    private WktParser() {}

    public static Polyline parseLineString(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            throw new IllegalArgumentException("empty WKT");
        }
        String s = wkt.trim();
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close <= open) {
            throw new IllegalArgumentException("bad WKT: " + wkt);
        }
        String body = s.substring(open + 1, close).trim();
        String[] parts = body.split(",");
        List<Coord> coords = new ArrayList<>();
        for (String part : parts) {
            Matcher m = NUM.matcher(part);
            List<Double> nums = new ArrayList<>();
            while (m.find()) {
                nums.add(Double.parseDouble(m.group()));
            }
            if (nums.size() >= 3) {
                coords.add(new Coord(nums.get(0), nums.get(1), nums.get(2)));
            } else if (nums.size() >= 2) {
                coords.add(new Coord(nums.get(0), nums.get(1)));
            }
        }
        if (coords.size() < 2) {
            throw new IllegalArgumentException("need >=2 points: " + wkt);
        }
        return new Polyline(coords);
    }

    public static String formatKartutsnitt(double minX, double minY, double maxX, double maxY) {
        return String.format(Locale.ROOT, "%s,%s,%s,%s", minX, minY, maxX, maxY);
    }
}
