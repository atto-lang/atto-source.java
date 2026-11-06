package dev.oreslang;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-byte integrity for the *active* canonical .atto library resource.
 * The existing AttoLibraryNamespaceResolutionTest separately executes
 * compiler-linked Java/Truffle guests; this test is source evidence only.
 */
final class AttoCanonicalCoreResourceTest {
    private static byte[] readResource(String path) throws IOException {
        try (InputStream stream = AttoCanonicalCoreResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing packaged Atto Core resource: " + path);
            return stream.readAllBytes();
        }
    }

    @Test
    void canonicalAttoCoreSourceMatchesPinnedCrossBackendGitBlob() throws Exception {
        byte[] canonical = readResource("/dev/atto/core/numeric.atto");
        byte[] legacy = readResource("/dev/oreslang/core/numeric.ores");
        assertArrayEquals(legacy, canonical, "legacy compatibility bytes drifted");
        assertEquals(657, canonical.length);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + canonical.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(canonical);
        assertEquals("5ced991f1d49b3c2a60fdd72b9003c0a4d18cd72",
                HexFormat.of().formatHex(digest.digest()));
        assertEquals(new String(canonical, StandardCharsets.UTF_8),
                CoreLibrarySources.oresCoreSource("atto.core/numeric.atto"));
        assertEquals(CoreLibrarySources.oresCoreSource("atto.core/numeric"),
                CoreLibrarySources.oresCoreSource("ores.core/numeric.ores"));
        assertTrue(CoreLibrarySources.packagedCorePaths().contains("numeric.atto"));
        assertEquals("@ores.core/numeric.ores",
                CoreLibrarySources.oresCoreUnitId("atto.core/numeric.atto"));
    }

    @Test
    void generatedSuffixAndLegacyAliasNeverSelectDifferentSource() throws Exception {
        for (String path : new String[] {
                "atto.core/numeric", "atto.core/numeric.atto",
                "ores.core/numeric", "ores.core/numeric.ores"
        }) {
            assertEquals("numeric.atto", CoreLibrarySources.oresCoreFileName(path));
            assertEquals(CoreLibrarySources.oresCoreSource("atto.core/numeric"),
                    CoreLibrarySources.oresCoreSource(path));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("atto.core/../numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("atto.core/missing.atto"));
    }
}
