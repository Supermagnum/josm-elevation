package org.openstreetmap.josm.plugins.nvdbincline.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import no.nvdbincline.core.model.NvdbLink;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.plugins.nvdbincline.io.NvdbClient;

/** Locate and load recorded steep-road fixtures (offline; no network). */
public final class SteepRoadFixtures {
    public static final long[] TARGET_WAY_IDS = {764390363L, 757907237L, 330233844L};

    private SteepRoadFixtures() {}

    public static Path root() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates =
                new Path[] {
                    cwd.resolve("tests/fixtures/steep_roads"),
                    cwd.resolve("../tests/fixtures/steep_roads"),
                    cwd.getParent() == null
                            ? cwd.resolve("tests/fixtures/steep_roads")
                            : cwd.getParent().resolve("tests/fixtures/steep_roads")
                };
        for (Path p : candidates) {
            Path n = p.normalize();
            if (Files.isDirectory(n.resolve("osm")) && Files.isRegularFile(n.resolve("nvdb/area.json"))) {
                return n;
            }
        }
        throw new IllegalStateException(
                "Cannot find tests/fixtures/steep_roads from cwd=" + cwd);
    }

    public static DataSet loadOsm(String relativeUnderOsm) throws Exception {
        Path file = root().resolve("osm").resolve(relativeUnderOsm);
        try (InputStream in = Files.newInputStream(file)) {
            return OsmReader.parseDataSet(in, NullProgressMonitor.INSTANCE);
        }
    }

    public static DataSet loadMergedTargetWays() throws Exception {
        DataSet merged = new DataSet();
        for (long id : TARGET_WAY_IDS) {
            DataSet part = loadOsm("way_" + id + ".osm");
            merged.mergeFrom(part);
        }
        return merged;
    }

    public static List<NvdbLink> loadNvdbAreaLinks() throws IOException {
        Path file = root().resolve("nvdb/area.json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(file));
        JsonNode objekter = root.get("objekter");
        List<NvdbLink> links = new ArrayList<>();
        if (objekter == null || !objekter.isArray()) {
            return links;
        }
        for (JsonNode obj : objekter) {
            NvdbLink link = NvdbClient.parseLink(obj);
            if (link != null) {
                links.add(link);
            }
        }
        return links;
    }
}
