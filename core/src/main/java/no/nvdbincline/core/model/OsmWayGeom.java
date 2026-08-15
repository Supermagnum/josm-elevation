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
    private final String existingSourceIncline;
    private final String existingHazard;
    private final String existingSourceHazard;
    private final String existingChainAdvisory;
    private final Integer speedLimitKph;

    public OsmWayGeom(
            long id,
            Polyline line,
            String highway,
            String name,
            String nvdbId,
            String existingIncline) {
        this(id, line, highway, name, nvdbId, existingIncline, null, null, null, null, null);
    }

    public OsmWayGeom(
            long id,
            Polyline line,
            String highway,
            String name,
            String nvdbId,
            String existingIncline,
            Integer speedLimitKph) {
        this(
                id,
                line,
                highway,
                name,
                nvdbId,
                existingIncline,
                null,
                null,
                null,
                null,
                speedLimitKph);
    }

    public OsmWayGeom(
            long id,
            Polyline line,
            String highway,
            String name,
            String nvdbId,
            String existingIncline,
            String existingSourceIncline,
            String existingHazard,
            String existingSourceHazard,
            String existingChainAdvisory,
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
        this.existingSourceIncline = existingSourceIncline;
        this.existingHazard = existingHazard;
        this.existingSourceHazard = existingSourceHazard;
        this.existingChainAdvisory = existingChainAdvisory;
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

    public Optional<String> existingSourceIncline() {
        return Optional.ofNullable(existingSourceIncline).filter(s -> !s.isBlank());
    }

    public Optional<String> existingHazard() {
        return Optional.ofNullable(existingHazard).filter(s -> !s.isBlank());
    }

    public Optional<String> existingSourceHazard() {
        return Optional.ofNullable(existingSourceHazard).filter(s -> !s.isBlank());
    }

    public Optional<String> existingChainAdvisory() {
        return Optional.ofNullable(existingChainAdvisory).filter(s -> !s.isBlank());
    }

    public Integer speedLimitKph() {
        return speedLimitKph;
    }
}
