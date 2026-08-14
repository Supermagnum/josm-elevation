package no.nvdbincline.core.area;

/**
 * How the plugin chooses the NVDB / OSM working area for one Suggest run.
 *
 * <p>Existing current-layer behaviour is preserved as {@link Mode#CURRENT_LAYER}.
 * {@link Mode#BBOX} and {@link Mode#KOMMUNE} are additive.
 */
public final class AreaSelection {
    public enum Mode {
        CURRENT_LAYER,
        BBOX,
        KOMMUNE
    }

    private final Mode mode;
    /** WGS84 minLon,minLat,maxLon,maxLat when known up-front (bbox mode). */
    private final double[] bboxLonLat;
    private final Integer kommuneNummer;
    private final String kommuneNavn;

    private AreaSelection(Mode mode, double[] bboxLonLat, Integer kommuneNummer, String kommuneNavn) {
        this.mode = mode;
        this.bboxLonLat = bboxLonLat == null ? null : bboxLonLat.clone();
        this.kommuneNummer = kommuneNummer;
        this.kommuneNavn = kommuneNavn;
    }

    public static AreaSelection currentLayer() {
        return new AreaSelection(Mode.CURRENT_LAYER, null, null, null);
    }

    public static AreaSelection bbox(double minLon, double minLat, double maxLon, double maxLat) {
        if (!(minLon < maxLon) || !(minLat < maxLat)) {
            throw new IllegalArgumentException("invalid bbox");
        }
        return new AreaSelection(Mode.BBOX, new double[] {minLon, minLat, maxLon, maxLat}, null, null);
    }

    public static AreaSelection kommune(int nummer, String navn) {
        if (nummer <= 0) {
            throw new IllegalArgumentException("kommunenummer");
        }
        return new AreaSelection(Mode.KOMMUNE, null, nummer, navn);
    }

    public Mode mode() {
        return mode;
    }

    public double[] bboxLonLat() {
        return bboxLonLat == null ? null : bboxLonLat.clone();
    }

    public Integer kommuneNummer() {
        return kommuneNummer;
    }

    public String kommuneNavn() {
        return kommuneNavn;
    }

    public boolean isKommune() {
        return mode == Mode.KOMMUNE;
    }
}
