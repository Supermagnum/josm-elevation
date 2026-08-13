package no.nvdbincline.core.tag;

/**
 * OSM incline=* formatting. Signed percentage relative to way node order.
 * See https://wiki.openstreetmap.org/wiki/Key:incline
 */
public final class InclineTags {
    public static final String SOURCE = "nvdb_estimate";
    public static final String NOTE =
            "Maskinelt NVDB-estimat, ikke feltverifisert. Kontroller mot skilt/terreng.";
    public static final String FIXME =
            "NVDB-estimated incline; verify in field before keeping. Source is NVDB 3D geometry, not a survey.";
    public static final String CHAIN_NOTE_FIT =
            "Foreslatt kjettingplass (NVDB-basert forslag, verifiser i felt). Ikke et etablert OSM-tagg i Norge.";
    public static final String CHAIN_NOTE_REMOVE =
            "Foreslatt kjettingavtakingspunkt (NVDB-basert forslag, verifiser i felt). Ikke et etablert OSM-tagg i Norge.";

    private InclineTags() {}

    /** Round to nearest integer percent (half away from zero). */
    public static int roundPct(double value) {
        if (value >= 0) {
            return (int) Math.floor(value + 0.5);
        }
        return (int) Math.ceil(value - 0.5);
    }

    public static String formatIncline(double value) {
        return roundPct(value) + "%";
    }
}
