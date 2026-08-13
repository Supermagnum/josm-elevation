package org.openstreetmap.josm.plugins.nvdbincline;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Safety regression: the plugin must never upload, create changesets, or handle OAuth.
 * Keep this test forever.
 */
class NoUploadSafetyTest {

    private static final Pattern[] FORBIDDEN =
            new Pattern[] {
                Pattern.compile("UploadAction"),
                Pattern.compile("OsmApi\\.[a-zA-Z]*[Uu]pload"),
                Pattern.compile("/api/0\\.6/changeset"),
                Pattern.compile("changeset/create"),
                Pattern.compile("OAuth2Session|oauthlib|/oauth/"),
                Pattern.compile("consumer_secret"),
                Pattern.compile("request_token"),
                Pattern.compile("access_token\\s*="),
                Pattern.compile("api\\.openstreetmap\\.org"),
                Pattern.compile("force.?upload", Pattern.CASE_INSENSITIVE),
            };

    @Test
    void sourceTreeHasNoUploadOrOauthPaths() throws IOException {
        Path root = findPluginSourceRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            p -> {
                                try {
                                    String text = Files.readString(p);
                                    for (Pattern pat : FORBIDDEN) {
                                        if (pat.matcher(text).find()) {
                                            offenders.add(
                                                    root.relativize(p) + ": " + pat.pattern());
                                        }
                                    }
                                } catch (IOException e) {
                                    fail(e);
                                }
                            });
        }
        assertTrue(offenders.isEmpty(), "Forbidden upload/OAuth patterns:\n" + String.join("\n", offenders));
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
