package no.nvdbincline.core.model;

import java.util.Optional;

/** OSM highway way geometry in projected metres (node order defines incline sign). */
public final class OsmWayGeom {
    private final long id;
    private final Polyline line;
    private final String highway;
    private final String name;
    private final String nvdbId;
    private final String existingIncline;
    private final Integer speedLimitKph;

    public OsmWayGeom(
            long id,
            Polyline line,
            String highway,
            String name,
            String nvdbId,
            String existingIncline) {
        this(id, line, highway, name, nvdbId, existingIncline, null);
    }

    public OsmWayGeom(
            long id,
            Polyline line,
            String highway,
            String name,
            String nvdbId,
            String existingIncline,
            Integer speedLimitKph) {
        if (line == null) {
            throw new IllegalArgumentException("line is null");
        }
        this.id = id;
        this.line = line;
        this.highway = highway;
        this.name = name;
        this.nvdbId = nvdbId;
        this.existingIncline = existingIncline;
        this.speedLimitKph = speedLimitKph;
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

    public Integer speedLimitKph() {
        return speedLimitKph;
    }
}
