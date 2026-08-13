package no.nvdbincline.core.model;

/** One NVDB segmented road-link with optional 3D geometry. */
public final class NvdbLink {
    private final long veglenkesekvensId;
    private final String kortform;
    private final String type;
    private final String typeVeg;
    private final String medium;
    private final double startposisjon;
    private final double sluttposisjon;
    private final Polyline line;

    public NvdbLink(
            long veglenkesekvensId,
            String kortform,
            String type,
            String typeVeg,
            String medium,
            double startposisjon,
            double sluttposisjon,
            Polyline line) {
        this.veglenkesekvensId = veglenkesekvensId;
        this.kortform = kortform == null ? "" : kortform;
        this.type = type == null ? "" : type;
        this.typeVeg = typeVeg == null ? "" : typeVeg;
        this.medium = medium;
        this.startposisjon = startposisjon;
        this.sluttposisjon = sluttposisjon;
        this.line = line;
    }

    public long veglenkesekvensId() {
        return veglenkesekvensId;
    }

    public String kortform() {
        return kortform;
    }

    public String type() {
        return type;
    }

    public String typeVeg() {
        return typeVeg;
    }

    public String medium() {
        return medium;
    }

    public double startposisjon() {
        return startposisjon;
    }

    public double sluttposisjon() {
        return sluttposisjon;
    }

    public Polyline line() {
        return line;
    }

    public String key() {
        return veglenkesekvensId + ":" + kortform;
    }

    public boolean isConnector() {
        return "KONNEKTERING".equalsIgnoreCase(type);
    }

    public boolean isTunnel() {
        if (medium != null) {
            String m = medium.toUpperCase();
            if ("T".equals(m) || "TUNNEL".equals(m)) {
                return true;
            }
        }
        return typeVeg.toLowerCase().contains("tunnel");
    }
}
