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

/** Independent Git blob identity pins; source-only boundary, not compiler AOT proof. */
final class CanonicalAttoCoreBlobIdentityTest {
    private static final String NUMERIC_SHA = "5ced991f1d49b3c2a60fdd72b9003c0a4d18cd72";
    private static final Map<String, String> EXPECTED_STD = Map.of(
        "core", "d2cd9b8e0cd3e3bb3ee6a5f9cebf9ab3312c44f4",
        "assert", "dd5e64e09e9a4b2606e4a685a60ad0bc00f45aea",
        "bounds", "b241e1c323506edc26a0485b3f02e0f5ab96bed9",
        "text", "5f982e8b5afde90389642c7f32a877ea68cd15c8");

    private static String blobSha(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void canonicalCoreAttoSourceIsPinnedAndLegacyAliasIsNotAPhysicalFile() throws Exception {
        Path path = Path.of("src/main/resources/dev/oreslang/core/numeric.atto");
        assertFalse(Files.isSymbolicLink(path));
        assertTrue(Files.isRegularFile(path));
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(NUMERIC_SHA, blobSha(bytes));
        assertEquals(new String(bytes, StandardCharsets.UTF_8),
            CoreLibrarySources.oresCoreSource("ores.core/numeric.atto"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("ores.core/numeric.atto"),
            CoreLibrarySources.oresCoreUnitId("ores.core/numeric.ores"));
        assertFalse(Files.exists(path.resolveSibling("numeric.ores")));
    }

    @Test
    void hostedAttoSourcePinsAreIndependentOfManifestAndPreserveLegacyIdentity() throws Exception {
        for (var item : EXPECTED_STD.entrySet()) {
            String name = item.getKey();
            Path path = Path.of("stdlib", name + ".atto");
            assertFalse(Files.isSymbolicLink(path), name);
            assertTrue(Files.isRegularFile(path), name);
            byte[] bytes = Files.readAllBytes(path);
            assertEquals(item.getValue(), blobSha(bytes), name);
            assertEquals(new String(bytes, StandardCharsets.UTF_8),
                CoreLibrarySources.source("std/" + name + ".atto"), name);
            assertEquals(CoreLibrarySources.source("std/" + name + ".atto"),
                CoreLibrarySources.source("ores.std/" + name), name);
            if (name.equals("core") || name.equals("assert")) {
                assertEquals(CoreLibrarySources.unitId("std/" + name + ".atto"),
                    CoreLibrarySources.unitId("std/" + name + ".ores"), name);
                assertFalse(Files.exists(path.resolveSibling(name + ".ores")), name);
            } else {
                assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source("std/" + name + ".ores"), name);
            }
        }
    }
}
