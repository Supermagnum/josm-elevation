package no.nvdbincline.core.kommune;

import java.util.Objects;
import no.nvdbincline.core.geo.LonLatMultiPolygon;

/** One kommune's administrative boundary (Kartverket). */
public final class KommuneBoundary {
    private final int nummer;
    private final String navn;
    private final LonLatMultiPolygon polygon;

    public KommuneBoundary(int nummer, String navn, LonLatMultiPolygon polygon) {
        if (nummer <= 0) {
            throw new IllegalArgumentException("nummer");
        }
        this.nummer = nummer;
        this.navn = navn == null ? "" : navn;
        this.polygon = Objects.requireNonNull(polygon, "polygon");
    }

    public int nummer() {
        return nummer;
    }

    public String navn() {
        return navn;
    }

    public LonLatMultiPolygon polygon() {
        return polygon;
    }
}
