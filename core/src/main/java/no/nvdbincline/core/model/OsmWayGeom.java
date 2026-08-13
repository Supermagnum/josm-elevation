package no.nvdbincline.core.model;

import java.util.List;
import java.util.Optional;

/** OSM highway way geometry in projected metres (node order defines incline sign). */
public final class OsmWayGeom {
    private final long id;
    private final Polyline line;
    private final String highway;
    private final String name;
    private final String nvdbId;
    private final String existingIncline;

    public OsmWayGeom(
            long id,
            Polyline line,
            String highway,
            String name,
            String nvdbId,
            String existingIncline) {
        this.id = id;
        this.line = ObjectsRequire.nonNull(line, "line");
        this.highway = highway;
        this.name = name;
        this.nvdbId = nvdbId;
        this.existingIncline = existingIncline;
    }

    public long id() {
        return id;
    }

    public Polyline line() {
        return line;
    }

    public String highway() {
        return highway;
    }

    public String name() {
        return name;
    }

    public Optional<String> nvdbId() {
        return Optional.ofNullable(nvdbId).filter(s -> !s.isBlank());
    }

    public Optional<String> existingIncline() {
        return Optional.ofNullable(existingIncline).filter(s -> !s.isBlank());
    }

    private static final class ObjectsRequire {
        static <T> T nonNull(T v, String name) {
            if (v == null) {
                throw new IllegalArgumentException(name + " is null");
            }
            return v;
        }
    }
}
