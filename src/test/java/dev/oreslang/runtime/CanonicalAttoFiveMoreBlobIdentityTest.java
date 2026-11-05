package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Five guest libraries have one canonical AttoLang source, not a Java semantic replica. */
final class CanonicalAttoFiveMoreBlobIdentityTest {
    private static final Map<String, String> EXPECTED = Map.of(
        "compute", "b8e11db5f8701fbceb1e2afd39cad06662d0e8be",
        "trace", "f4ff35b10683880e42e3151b34fc140d52704dba",
        "db_pool", "95396a3cee45a18d7d2707224ef2f23957ba8e46",
        "http_wire", "21169e5c262cd79997637c97d448e05a835e2e44",
        "actor_persistence", "fec71c8ae3e9c0e8828b712a1a9d78a26948e0ea");

    private static String gitBlobSha(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(data);
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void allFiveSourcesMatchIndependentExactGitBlobIdentities() throws Exception {
        for (var entry : EXPECTED.entrySet()) {
            String stem = entry.getKey();
            Path sourcePath = Path.of("stdlib", stem + ".atto");
            assertFalse(Files.isSymbolicLink(sourcePath), stem);
            assertTrue(Files.isRegularFile(sourcePath), stem);
            byte[] bytes = Files.readAllBytes(sourcePath);
            assertTrue(bytes.length > 0 && bytes.length <= 1024 * 1024, stem);
            assertEquals(entry.getValue(), gitBlobSha(bytes), stem);
            String expectedSource = new String(bytes, StandardCharsets.UTF_8);
            assertEquals(expectedSource, CoreLibrarySources.source("std/" + stem), stem);
            assertEquals(expectedSource, CoreLibrarySources.source("ores.std/" + stem + ".atto"), stem);
            assertEquals(expectedSource, CoreLibrarySources.source("std/" + stem + ".ores"), stem);
            assertEquals("@std/" + stem + ".ores",
                CoreLibrarySources.unitId("std/" + stem + ".atto"), stem);
            assertEquals(CoreLibrarySources.unitId("std/" + stem + ".atto"),
                CoreLibrarySources.unitId("ores.std/" + stem + ".ores"), stem);
            assertFalse(Files.exists(sourcePath.resolveSibling(stem + ".ores")), stem);
        }
    }

    @Test
    void malformedAndHostSuffixesNeverResolveToCompilerOwnedGuests() {
        for (String module : EXPECTED.keySet()) {
            for (String suffix : new String[]{".java", ".atto.ores", ".ores.atto"}) {
                String badPath = "std/" + module + suffix;
                assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source(badPath), badPath);
            }
        }
    }
}
