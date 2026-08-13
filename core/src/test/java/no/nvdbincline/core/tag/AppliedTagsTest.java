package no.nvdbincline.core.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppliedTagsTest {

    @Test
    void sourceKeysUsePrefixForm() {
        assertEquals("source:incline", AppliedTags.SOURCE_INCLINE);
        assertEquals("source:hazard", AppliedTags.SOURCE_HAZARD);
        assertFalse(AppliedTags.SOURCE_INCLINE.contains("incline:source"));
        assertTrue(AppliedTags.FORBIDDEN_LEGACY_KEYS.contains("incline:source"));
        assertTrue(AppliedTags.FORBIDDEN_LEGACY_KEYS.contains("hazard:source"));
    }

    @Test
    void inclineMapIsExactlyFourKeys() {
        var tags = AppliedTags.incline("7%");
        assertEquals(AppliedTags.WAY_INCLINE_KEYS, tags.keySet());
        assertEquals("nvdb_estimate", tags.get(AppliedTags.SOURCE_INCLINE));
    }

    @Test
    void hazardMapIsExactlyFourKeys() {
        var tags = AppliedTags.hazard("curve", "n", "f");
        assertEquals(AppliedTags.HAZARD_KEYS, tags.keySet());
        assertEquals("nvdb_sign", tags.get(AppliedTags.SOURCE_HAZARD));
    }
}
