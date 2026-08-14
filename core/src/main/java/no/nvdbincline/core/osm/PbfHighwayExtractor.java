package no.nvdbincline.core.osm;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.InflaterInputStream;
import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import no.nvdbincline.core.ProgressCallback;
import no.nvdbincline.core.geo.LonLatMultiPolygon;
import no.nvdbincline.core.geo.Utm33;
import no.nvdbincline.core.model.Coord;
import no.nvdbincline.core.model.OsmWayGeom;
import no.nvdbincline.core.model.Polyline;

/**
 * Pure-JVM extraction of highway=* ways from a local {@code .osm.pbf} clipped by
 * a lon/lat polygon (no osmium, no Overpass, no OSM API).
 *
 * <p>Uses {@code osmosis-osm-binary} (protobuf schema) only. Inclusion rule:
 * {@link WayPolygonClipper} — any node inside the polygon.
 */
public final class PbfHighwayExtractor {
    private static final Set<String> EXCLUDE_HIGHWAY =
            Set.of("footway", "path", "steps");
    /** Keep nodes slightly outside the bbox so border-straddling ways stay complete. */
    private static final double NODE_PAD_DEG = 0.05;

    private PbfHighwayExtractor() {}

    public static final class LonLatHighway {
        public final long id;
        public final String highway;
        public final String name;
        public final String nvdbId;
        public final String incline;
        public final Integer maxspeed;
        /** Each entry is {@code [lon, lat]}. */
        public final List<double[]> nodes;

        public LonLatHighway(
                long id,
                String highway,
                String name,
                String nvdbId,
                String incline,
                Integer maxspeed,
                List<double[]> nodes) {
            this.id = id;
            this.highway = highway;
            this.name = name;
            this.nvdbId = nvdbId;
            this.incline = incline;
            this.maxspeed = maxspeed;
            this.nodes = List.copyOf(nodes);
        }

        public OsmWayGeom toOsmWayGeom() {
            List<Coord> utm = new ArrayList<>(nodes.size());
            for (double[] ll : nodes) {
                double[] xy = Utm33.lonLatToUtm(ll[0], ll[1]);
                utm.add(new Coord(xy[0], xy[1]));
            }
            return new OsmWayGeom(
                    id, new Polyline(utm), highway, name, nvdbId, incline, maxspeed);
        }
    }

    public static final class Result {
        public final List<LonLatHighway> highways;
        public final int nodesKept;
        public final int waysScanned;

        public Result(List<LonLatHighway> highways, int nodesKept, int waysScanned) {
            this.highways = List.copyOf(highways);
            this.nodesKept = nodesKept;
            this.waysScanned = waysScanned;
        }

        public List<OsmWayGeom> ways() {
            List<OsmWayGeom> out = new ArrayList<>(highways.size());
            for (LonLatHighway h : highways) {
                out.add(h.toOsmWayGeom());
            }
            return out;
        }
    }

    public static Result extract(Path pbf, LonLatMultiPolygon polygon) throws IOException {
        return extract(pbf, polygon, ProgressCallback.NONE);
    }

    public static Result extract(Path pbf, LonLatMultiPolygon polygon, ProgressCallback progress)
            throws IOException {
        Objects.requireNonNull(pbf, "pbf");
        Objects.requireNonNull(polygon, "polygon");
        ProgressCallback cb = progress == null ? ProgressCallback.NONE : progress;
        long size = Files.size(pbf);
        try (InputStream raw = Files.newInputStream(pbf);
                BufferedInputStream buffered = new BufferedInputStream(raw, 1 << 20);
                DataInputStream in = new DataInputStream(buffered)) {
            return extract(in, size, polygon, cb);
        }
    }

    static Result extract(
            DataInputStream in,
            long fileSizeHint,
            LonLatMultiPolygon polygon,
            ProgressCallback cb)
            throws IOException {
        Map<Long, double[]> nodesInEnvelope = new HashMap<>();
        List<PendingWay> pending = new ArrayList<>();
        long bytesRead = 0;
        int waysScanned = 0;

        if (!cb.onProgress("Reading local Norway OSM extract…", 0, 100)) {
            throw new CancelledException();
        }

        while (true) {
            Fileformat.BlobHeader header;
            Fileformat.Blob blob;
            try {
                int headerSize = in.readInt();
                bytesRead += 4;
                byte[] headerBytes = in.readNBytes(headerSize);
                if (headerBytes.length != headerSize) {
                    break;
                }
                bytesRead += headerSize;
                header = Fileformat.BlobHeader.parseFrom(headerBytes);
                byte[] blobBytes = in.readNBytes(header.getDatasize());
                if (blobBytes.length != header.getDatasize()) {
                    break;
                }
                bytesRead += header.getDatasize();
                blob = Fileformat.Blob.parseFrom(blobBytes);
            } catch (EOFException eof) {
                break;
            }

            if (fileSizeHint > 0) {
                int pct = (int) Math.min(99, (bytesRead * 100) / fileSizeHint);
                if (!cb.onProgress("Reading local Norway OSM extract…", pct, 100)) {
                    throw new CancelledException();
                }
            }

            if (!"OSMData".equals(header.getType())) {
                continue;
            }
            byte[] data = blobData(blob);
            Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.parseFrom(data);
            double latOffset = block.getLatOffset();
            double lonOffset = block.getLonOffset();
            int granularity = block.getGranularity();

            for (Osmformat.PrimitiveGroup group : block.getPrimitivegroupList()) {
                ingestNodes(group, latOffset, lonOffset, granularity, polygon, nodesInEnvelope);
                if (group.hasDense()) {
                    ingestDense(
                            group.getDense(),
                            latOffset,
                            lonOffset,
                            granularity,
                            polygon,
                            nodesInEnvelope);
                }
                for (Osmformat.Way way : group.getWaysList()) {
                    waysScanned++;
                    PendingWay pw = pendingWay(way, block, nodesInEnvelope);
                    if (pw != null) {
                        pending.add(pw);
                    }
                }
            }
        }

        if (!cb.onProgress("Clipping highways to kommune boundary…", 0, Math.max(1, pending.size()))) {
            throw new CancelledException();
        }

        List<LonLatHighway> out = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            if (i % 200 == 0
                    && !cb.onProgress(
                            "Clipping highways to kommune boundary…", i, pending.size())) {
                throw new CancelledException();
            }
            PendingWay pw = pending.get(i);
            List<double[]> coords = new ArrayList<>(pw.nodeIds.size());
            boolean anyInside = false;
            boolean missing = false;
            for (long nid : pw.nodeIds) {
                double[] ll = nodesInEnvelope.get(nid);
                if (ll == null) {
                    missing = true;
                    break;
                }
                coords.add(ll);
                if (!anyInside && polygon.contains(ll[0], ll[1])) {
                    anyInside = true;
                }
            }
            if (missing || !anyInside || coords.size() < 2) {
                continue;
            }
            out.add(
                    new LonLatHighway(
                            pw.id,
                            pw.highway,
                            pw.name,
                            pw.nvdbId,
                            pw.incline,
                            pw.maxspeed,
                            coords));
        }
        cb.onProgress("Clipping highways to kommune boundary…", pending.size(), Math.max(1, pending.size()));
        return new Result(out, nodesInEnvelope.size(), waysScanned);
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("PBF extract cancelled");
        }
    }

    private static void ingestNodes(
            Osmformat.PrimitiveGroup group,
            double latOffset,
            double lonOffset,
            int granularity,
            LonLatMultiPolygon polygon,
            Map<Long, double[]> into) {
        for (Osmformat.Node n : group.getNodesList()) {
            double lat = .000000001 * (latOffset + (granularity * (double) n.getLat()));
            double lon = .000000001 * (lonOffset + (granularity * (double) n.getLon()));
            if (polygon.envelopeContains(lon, lat)
                    || nearEnvelope(polygon, lon, lat, NODE_PAD_DEG)) {
                into.put(n.getId(), new double[] {lon, lat});
            }
        }
    }

    private static void ingestDense(
            Osmformat.DenseNodes dense,
            double latOffset,
            double lonOffset,
            int granularity,
            LonLatMultiPolygon polygon,
            Map<Long, double[]> into) {
        long id = 0;
        long lat = 0;
        long lon = 0;
        int keyIndex = 0;
        for (int i = 0; i < dense.getIdCount(); i++) {
            id += dense.getId(i);
            lat += dense.getLat(i);
            lon += dense.getLon(i);
            double latD = .000000001 * (latOffset + (granularity * (double) lat));
            double lonD = .000000001 * (lonOffset + (granularity * (double) lon));
            if (polygon.envelopeContains(lonD, latD)
                    || nearEnvelope(polygon, lonD, latD, NODE_PAD_DEG)) {
                into.put(id, new double[] {lonD, latD});
            }
            if (dense.getKeysValsCount() > 0) {
                while (keyIndex < dense.getKeysValsCount()) {
                    int k = dense.getKeysVals(keyIndex++);
                    if (k == 0) {
                        break;
                    }
                    if (keyIndex < dense.getKeysValsCount()) {
                        keyIndex++;
                    }
                }
            }
        }
    }

    private static boolean nearEnvelope(
            LonLatMultiPolygon polygon, double lon, double lat, double padDeg) {
        return lon >= polygon.minLon() - padDeg
                && lon <= polygon.maxLon() + padDeg
                && lat >= polygon.minLat() - padDeg
                && lat <= polygon.maxLat() + padDeg;
    }

    private static PendingWay pendingWay(
            Osmformat.Way way,
            Osmformat.PrimitiveBlock block,
            Map<Long, double[]> nodesInEnvelope) {
        String highway = null;
        String name = null;
        String nvdb = null;
        String incline = null;
        Integer maxspeed = null;
        Osmformat.StringTable st = block.getStringtable();
        for (int i = 0; i < way.getKeysCount(); i++) {
            String k = st.getS(way.getKeys(i)).toStringUtf8();
            String v = st.getS(way.getVals(i)).toStringUtf8();
            switch (k) {
                case "highway" -> highway = v;
                case "name" -> name = v;
                case "nvdb:id", "nvdb:veglenkesekvensid" -> {
                    if (nvdb == null) {
                        nvdb = v;
                    }
                }
                case "incline" -> incline = v;
                case "maxspeed" -> maxspeed = parseMaxspeed(v);
                default -> {
                    // ignore
                }
            }
        }
        if (highway == null || EXCLUDE_HIGHWAY.contains(highway)) {
            return null;
        }
        List<Long> refs = new ArrayList<>(way.getRefsCount());
        long ref = 0;
        boolean anyKnown = false;
        for (int i = 0; i < way.getRefsCount(); i++) {
            ref += way.getRefs(i);
            refs.add(ref);
            if (!anyKnown && nodesInEnvelope.containsKey(ref)) {
                anyKnown = true;
            }
        }
        if (!anyKnown || refs.size() < 2) {
            return null;
        }
        return new PendingWay(way.getId(), highway, name, nvdb, incline, maxspeed, refs);
    }

    private static Integer parseMaxspeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        if (s.contains("mph")) {
            return null;
        }
        // Values like "signals", "none", "walk" have no digits — split()[0] would NPE/AIOOBE.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            int v = Integer.parseInt(m.group(1));
            return v > 0 && v < 200 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] blobData(Fileformat.Blob blob) throws IOException {
        if (blob.hasRaw()) {
            return blob.getRaw().toByteArray();
        }
        if (blob.hasZlibData()) {
            try (InputStream zin =
                            new InflaterInputStream(blob.getZlibData().newInput());
                    BufferedInputStream bin = new BufferedInputStream(zin)) {
                return bin.readAllBytes();
            }
        }
        throw new IOException("Unsupported PBF blob compression");
    }

    private static final class PendingWay {
        final long id;
        final String highway;
        final String name;
        final String nvdbId;
        final String incline;
        final Integer maxspeed;
        final List<Long> nodeIds;

        PendingWay(
                long id,
                String highway,
                String name,
                String nvdbId,
                String incline,
                Integer maxspeed,
                List<Long> nodeIds) {
            this.id = id;
            this.highway = highway;
            this.name = name;
            this.nvdbId = nvdbId;
            this.incline = incline;
            this.maxspeed = maxspeed;
            this.nodeIds = nodeIds;
        }
    }
}
