package no.nvdbincline.core.kommune;

import java.util.ArrayList;
import java.util.List;

/** Pure filtering for the kommune combo box (no Swing). */
public final class KommuneSearch {
    private KommuneSearch() {}

    /**
     * Partial, case-insensitive match on kommunenavn or kommunenummer string.
     * Empty / blank query returns all entries in input order.
     */
    public static List<Kommune> filter(List<Kommune> all, String query) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        String q = KommuneCatalog.normalize(query == null ? "" : query.trim());
        if (q.isEmpty()) {
            return List.copyOf(all);
        }
        List<Kommune> out = new ArrayList<>();
        for (Kommune k : all) {
            String name = KommuneCatalog.normalize(k.navn());
            String num = Integer.toString(k.nummer());
            if (name.contains(q) || num.contains(q)) {
                out.add(k);
            }
        }
        return out;
    }
}
