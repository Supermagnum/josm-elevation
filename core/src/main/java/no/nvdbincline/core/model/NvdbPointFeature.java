package no.nvdbincline.core.model;

/** Point feature from NVDB vegobjekter (sign or accident). */
public final class NvdbPointFeature {
    private final long typeId;
    private final long objectId;
    private final double x;
    private final double y;
    private final String label;
    private final String dateIso;
    private final String skiltnummer;

    public NvdbPointFeature(
            long typeId,
            long objectId,
            double x,
            double y,
            String label,
            String dateIso,
            String skiltnummer) {
        this.typeId = typeId;
        this.objectId = objectId;
        this.x = x;
        this.y = y;
        this.label = label == null ? "" : label;
        this.dateIso = dateIso;
        this.skiltnummer = skiltnummer;
    }

    public long typeId() {
        return typeId;
    }

    public long objectId() {
        return objectId;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public String label() {
        return label;
    }

    public String dateIso() {
        return dateIso;
    }

    public String skiltnummer() {
        return skiltnummer;
    }
}
