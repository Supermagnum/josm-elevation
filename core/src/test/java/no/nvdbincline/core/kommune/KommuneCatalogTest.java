package no.nvdbincline.core.kommune;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KommuneCatalogTest {
    @Test
    void loadsBundledSnapshot() {
        KommuneCatalog cat = KommuneCatalog.loadDefault();
        assertEquals("2024-01-01", cat.effectiveDate());
        assertTrue(cat.all().size() > 300);
        assertEquals("Oslo", cat.byNummer(301).orElseThrow().navn());
    }

    @Test
    void parseJsonReadsOptionalFylke() {
        String json =
                """
                {
                  "effective_date": "2024-01-01",
                  "source": "test",
                  "kommuner": [
                    {"nummer": 301, "navn": "Oslo", "fylkesnummer": 3, "fylkesnavn": "Oslo"}
                  ]
                }
                """;
        KommuneCatalog cat = KommuneCatalog.parseJson(json);
        Kommune k = cat.byNummer(301).orElseThrow();
        assertEquals(3, k.fylkesnummer());
        assertEquals("Oslo", k.fylkesnavn());
    }

    @Test
    void parseJsonFailsWhenKommunerMissing() {
        assertThrows(IllegalArgumentException.class, () -> KommuneCatalog.parseJson("{\"x\":1}"));
    }
}

class KommuneSearchTest {
    private static final List<Kommune> SAMPLE =
            List.of(
                    new Kommune(301, "Oslo"),
                    new Kommune(5001, "Trondheim"),
                    new Kommune(5601, "Tromsø"),
                    new Kommune(5636, "Unjárga-Nesseby"),
                    new Kommune(3420, "Nord-Odal"),
                    new Kommune(4204, "Åmli"));

    @Test
    void emptyQueryReturnsAll() {
        assertEquals(SAMPLE.size(), KommuneSearch.filter(SAMPLE, "").size());
        assertEquals(SAMPLE.size(), KommuneSearch.filter(SAMPLE, "  ").size());
    }

    @Test
    void partialCaseInsensitiveName() {
        List<Kommune> hits = KommuneSearch.filter(SAMPLE, "trond");
        assertEquals(1, hits.size());
        assertEquals(5001, hits.get(0).nummer());
    }

    @Test
    void norwegianLetters() {
        assertEquals(1, KommuneSearch.filter(SAMPLE, "tromsø").size());
        assertEquals(1, KommuneSearch.filter(SAMPLE, "TROMSØ").size());
        assertEquals(1, KommuneSearch.filter(SAMPLE, "åmli").size());
        assertEquals(1, KommuneSearch.filter(SAMPLE, "Åmli").size());
    }

    @Test
    void matchByNummerSubstring() {
        List<Kommune> hits = KommuneSearch.filter(SAMPLE, "5636");
        assertEquals(1, hits.size());
        assertEquals("Unjárga-Nesseby", hits.get(0).navn());
    }

    @Test
    void noMatch() {
        assertTrue(KommuneSearch.filter(SAMPLE, "xyzzy").isEmpty());
    }
}
