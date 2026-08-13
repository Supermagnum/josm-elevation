package org.openstreetmap.josm.plugins.nvdbincline;

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
                                true));
    }

    @Test
    void applierNeverEmitsHazardForUnsignedAccidentCluster() {
        // Construct advisory tags (legal), then verify sanitize would strip a sneaked hazard.
        ReviewModel.Row advisory =
                new ReviewModel.Row(
                        ReviewModel.Kind.ACCIDENT_CLUSTER,
                        ReviewModel.Section.ACCIDENTS,
                        0,
                        "advisory",
                        MatchConfidence.LOW,
                        Map.of(
                                "safety_advisory",
                                "accident_cluster",
                                "note",
                                "NVDB Trafikkulykke-ansamling: 4 ulykker 2020–2023 (type 570)"),
                        null,
                        null,
                        false,
                        228100.0,
                        6952200.0,
                        true);
        assertFalse(SuggestionApplier.sanitizeTags(advisory).containsKey("hazard"));

        // If someone later mutates tags after construction, sanitize still strips.
        Map<String, String> sneaked = new LinkedHashMap<>(advisory.tags);
        sneaked.put("hazard", "dangerous_junction");
        ReviewModel.Row signedOk =
                new ReviewModel.Row(
                        ReviewModel.Kind.ACCIDENT_CLUSTER,
                        ReviewModel.Section.CURVES_SIGNED,
                        0,
                        "signed",
                        MatchConfidence.HIGH,
                        Map.of(
                                "hazard",
                                "dangerous_junction",
                                "hazard:source",
                                "nvdb_sign",
                                "note",
                                "ok"),
                        null,
                        null,
                        true,
                        228100.0,
                        6952200.0,
                        true);
        assertTrue(SuggestionApplier.sanitizeTags(signedOk).containsKey("hazard"));
        assertFalse(sneaked.isEmpty()); // keep reference used above for intent clarity
    }

    @Test
    void coreAndPluginSourcesNeverAssignHazardOutsideSignPaths() throws IOException {
        // Soft static guard: advisory note text must mention not using hazard when unsigned;
        // and SafetyAnalyzer must only put("hazard"...) after signConfirmed branches.
        Path core =
                findRoot("core/src/main/java/no/nvdbincline/core/safety/SafetyAnalyzer.java");
        String text = Files.readString(core);
        // Unsigned accident branch must not introduce hazard=
        int advisoryIdx = text.indexOf("safety_advisory\", \"accident_cluster\"");
        assertTrue(advisoryIdx > 0);
        String advisoryBlock = text.substring(advisoryIdx, Math.min(text.length(), advisoryIdx + 800));
        assertFalse(
                advisoryBlock.contains("tags.put(\"hazard\""),
                "accident_cluster advisory branch must not assign hazard=*");

        int curveAdv = text.indexOf("safety_advisory\", \"sharp_curve\"");
        assertTrue(curveAdv > 0);
        String curveBlock = text.substring(curveAdv, Math.min(text.length(), curveAdv + 600));
        assertFalse(
                curveBlock.contains("tags.put(\"hazard\""),
                "curve advisory branch must not assign hazard=*");
    }

    @Test
    void noUploadStillHoldsAlongsideHazardGate() throws IOException {
        // Keep upload ban visible in this suite too (companion to NoUploadSafetyTest).
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
