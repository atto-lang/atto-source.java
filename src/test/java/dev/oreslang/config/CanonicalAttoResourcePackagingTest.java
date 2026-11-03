package dev.oreslang.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jar/classpath resources must include new AttoLang source and package metadata.
 * This test verifies packaging only, not language compilation or guest execution.
 */
final class CanonicalAttoResourcePackagingTest {
    private static String requireResource(String relativeName) throws Exception {
        String name = "dev/attolang/examples/canonical/" + relativeName;
        try (InputStream input =
                     CanonicalAttoResourcePackagingTest.class.getClassLoader()
                             .getResourceAsStream(name)) {
            assertNotNull(input, "missing packaged canonical AttoLang resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void canonicalManifestAndCrossFileAttoExampleArePackagedTogether() throws Exception {
        String manifest = requireResource(".atto.pkg.toml");
        String entry = requireResource("src/main.atto");
        String dependency = requireResource("src/value.atto");
        assertTrue(manifest.contains("main = \"src/main.atto\""));
        assertTrue(entry.contains("from \"./value\""));
        assertTrue(dependency.contains("return 42;"));
    }
}
