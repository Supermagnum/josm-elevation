package org.openstreetmap.josm.plugins.nvdbincline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import no.nvdbincline.core.model.MatchConfidence;
import no.nvdbincline.core.model.SafetyFinding;
import no.nvdbincline.core.review.ReviewModel;
import no.nvdbincline.core.tag.AppliedTags;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.nvdbincline.command.SuggestionApplier;

/**
 * Tag-safety regression: {@code hazard=*} must never leave the apply path without
 * sign confirmation. Accident-cluster-only findings can only produce advisory tags.
 */
class HazardTagSafetyTest {

    @Test
    void safetyFindingRejectsHazardWithoutSign() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("hazard", "curve");
        tags.put("note", "should fail");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SafetyFinding(
                                SafetyFinding.Kind.CURVE_ADVISORY,
                                false,
                                0,
                                0,
                                1L,
                                "bad",
                                MatchConfidence.LOW,
                                tags,
                                0,
                                null,
                                20));
    }

    @Test
    void reviewRowRejectsHazardWithoutSign() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ReviewModel.Row(
                                ReviewModel.Kind.ACCIDENT_CLUSTER,
                                ReviewModel.Section.ACCIDENTS,
                                0,
                                "bad",
                                MatchConfidence.LOW,
                                Map.of("hazard", "dangerous_junction", "note", "x"),
                                null,
                                null,
                                false,
                                1.0,
                                2.0,
                                false,
                                true));
    }

    @Test
    void applierNeverEmitsHazardForUnsignedAccidentCluster() {
        ReviewModel.Row advisory =
                new ReviewModel.Row(
                        ReviewModel.Kind.ACCIDENT_CLUSTER,
                        ReviewModel.Section.ACCIDENTS,
                        0,
                        "advisory",
                        MatchConfidence.LOW,
                        AppliedTags.safetyAdvisory(
                                "accident_cluster",
                                "NVDB Trafikkulykke-ansamling: 4 ulykker 2020–2023 (type 570)"),
                        null,
                        null,
                        false,
                        228100.0,
                        6952200.0,
                        false,
                        true);
        assertFalse(SuggestionApplier.sanitizeTags(advisory).containsKey("hazard"));

        ReviewModel.Row signedOk =
                new ReviewModel.Row(
                        ReviewModel.Kind.ACCIDENT_CLUSTER,
                        ReviewModel.Section.CURVES_SIGNED,
                        0,
                        "signed",
                        MatchConfidence.HIGH,
                        AppliedTags.hazard("dangerous_junction", "ok", "verify"),
                        null,
                        null,
                        true,
                        228100.0,
                        6952200.0,
                        false,
                        true);
        Map<String, String> kept = SuggestionApplier.sanitizeTags(signedOk);
        assertTrue(kept.containsKey("hazard"));
        assertEquals("nvdb_sign", kept.get(AppliedTags.SOURCE_HAZARD));
        assertFalse(kept.containsKey("hazard:source"));
        assertEquals(AppliedTags.HAZARD_KEYS, kept.keySet());
    }

    @Test
    void coreAndPluginSourcesNeverAssignHazardOutsideSignPaths() throws IOException {
        Path core =
                findRoot("core/src/main/java/no/nvdbincline/core/safety/SafetyAnalyzer.java");
        String text = Files.readString(core);
        assertFalse(
                text.contains("\"hazard:source\""),
                "must use source:hazard, not hazard:source");
        assertTrue(text.contains("AppliedTags.hazard("));
        assertTrue(text.contains("AppliedTags.safetyAdvisory(\"accident_cluster\""));
        assertTrue(text.contains("AppliedTags.safetyAdvisory(\"sharp_curve\""));
        // Unsigned advisory helpers must not be mixed with hazard in the same call site block:
        // ensure safetyAdvisory is used for both advisory cases.
        assertTrue(text.indexOf("safetyAdvisory(\"sharp_curve\"") > 0);
        assertTrue(text.indexOf("safetyAdvisory(\"accident_cluster\"") > 0);
    }

    @Test
    void noUploadStillHoldsAlongsideHazardGate() throws IOException {
        Path root = findPluginSourceRoot();
        List<String> offenders = new ArrayList<>();
        Pattern upload = Pattern.compile("UploadAction");
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            p -> {
                                try {
                                    if (upload.matcher(Files.readString(p)).find()) {
                                        offenders.add(p.toString());
                                    }
                                } catch (IOException e) {
                                    fail(e);
                                }
                            });
        }
        assertTrue(offenders.isEmpty());
    }

    private static Path findRoot(String relative) {
        Path cwd = Path.of("").toAbsolutePath();
        Path c = cwd.resolve(relative);
        if (Files.isRegularFile(c)) {
            return c;
        }
        c = cwd.getParent().resolve(relative);
        if (Files.isRegularFile(c)) {
            return c;
        }
        throw new IllegalStateException("Cannot find " + relative + " from " + cwd);
    }

    private static Path findPluginSourceRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.resolve("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = cwd.resolve("plugin/src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException("Cannot locate plugin sources from " + cwd);
    }
}
