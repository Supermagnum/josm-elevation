package no.nvdbincline.core.kommune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KommuneBoundaryCatalogTest {
    @Test
    void parsesFixtureAndRequiresLoudly() {
        String json =
                """
                {"source":"test","reference_date":"2026-01-01","crs":"EPSG:4258","kommuner":{
                "0301":{"navn":"Oslo","polygons":[[[[10.0,59.0],[11.0,59.0],[11.0,60.0],[10.0,60.0],[10.0,59.0]]]]},
                "5001":{"navn":"Trondheim","polygons":[[[[10.0,63.0],[11.0,63.0],[11.0,64.0],[10.0,64.0],[10.0,63.0]]]]}
                }}
                """;
        KommuneBoundaryCatalog cat = KommuneBoundaryCatalog.parseJson(json);
        assertEquals(2, cat.size());
        assertEquals("Oslo", cat.require(301).navn());
        assertTrue(cat.require(301).polygon().contains(10.5, 59.5));
        assertThrows(IllegalArgumentException.class, () -> cat.require(9999));
    }

    @Test
    void loadBundledCatalog() throws Exception {
        KommuneBoundaryCatalog cat = KommuneBoundaryCatalog.loadDefault();
        assertTrue(cat.size() >= 350);
        assertTrue(cat.find(301).isPresent());
        assertTrue(cat.require(301).polygon().contains(10.75, 59.91));
    }
}
