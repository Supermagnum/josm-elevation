package org.openstreetmap.josm.plugins.nvdbincline.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import no.nvdbincline.core.model.NvdbLink;
import org.junit.jupiter.api.Test;

/** Offline parse of recorded NVDB fixture JSON (no network). */
class NvdbClientFixtureTest {

    @Test
    void parsesSteepFlatFixture() throws Exception {
        Path fixture =
                Path.of("..", "prototype", "tests", "fixtures", "area_steep_flat", "nvdb_segmentert.json")
                        .toAbsolutePath()
                        .normalize();
        if (!Files.isRegularFile(fixture)) {
            fixture =
                    Path.of("prototype", "tests", "fixtures", "area_steep_flat", "nvdb_segmentert.json")
                            .toAbsolutePath()
                            .normalize();
        }
        assertTrue(Files.isRegularFile(fixture), "missing fixture: " + fixture);
        JsonNode root = new ObjectMapper().readTree(Files.readString(fixture));
        int parsed = 0;
        for (JsonNode obj : root.get("objekter")) {
            NvdbLink link = NvdbClient.parseLink(obj);
            assertNotNull(link);
            assertTrue(link.line().hasZ());
            assertTrue(link.line().lengthM() > 0);
            parsed++;
        }
        assertEquals(2, parsed);
    }
}
