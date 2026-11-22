package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-owned Atto core imports: static integrity and real guest execution. */
final class AttoCoreSliceIterCmpGuestTest {
    @TempDir Path temp;

    @Test
    void canonicalAndLegacyImportsResolveExactlyOnePackagedResource() throws Exception {
        for (String stem : new String[]{"slice", "iter", "cmp"}) {
            String canonical = "atto.core/" + stem;
            String legacy = "ores.core/" + stem;
            assertTrue(CoreLibrarySources.isOresCoreImport(canonical));
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(canonical));
            assertEquals(CoreLibrarySources.oresCoreUnitId(canonical),
                    CoreLibrarySources.oresCoreUnitId(legacy));
            assertEquals(CoreLibrarySources.oresCoreSource(canonical),
                    CoreLibrarySources.oresCoreSource(legacy));
            assertEquals(CoreLibrarySources.oresCoreSource(canonical),
                    CoreLibrarySources.oresCoreSource(canonical + ".atto"));
            byte[] expected = Files.readAllBytes(
                    Path.of("core", stem + ".atto"));
            assertArrayEquals(expected, CoreLibrarySources.oresCoreSource(canonical)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void reservedRootsCannotFallThroughToProjectPaths() {
        for (String bad : new String[]{
                "atto.core", "atto.core/missing", "atto.core/../slice",
                "atto.core//slice", "atto.core/cmp.ores", "atto.core/cmp.java",
                "atto.core/slice.atto.ores", "atto.core/iter%2eatto"
        }) {
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(bad), bad);
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource(bad), bad);
        }
    }

    @Test
    void importedComparatorExecutesRealGuest() throws Exception {
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import * as cmp from "atto.core/cmp";
                pub routine main(): void {
                  stdio.println(cmp.compare_int(9223372036854775807, -9223372036854775807));
                  stdio.println(cmp.compare_bool(false, true));
                  stdio.println(cmp.min_int(9, 4));
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), output, errors);
        assertEquals("1\n-1\n4", output.toString(StandardCharsets.UTF_8)
                .trim().replace("\r", ""), () -> errors.toString(StandardCharsets.UTF_8));
        assertEquals(2, result.units().size());
    }
}
