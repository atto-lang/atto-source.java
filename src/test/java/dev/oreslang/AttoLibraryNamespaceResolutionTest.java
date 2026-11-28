package dev.oreslang;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java/Truffle compiler and guest check, not an import-string model. */
final class AttoLibraryNamespaceResolutionTest {
    @TempDir Path temp;

    @Test
    void canonicalAndLegacyNamesShareCompilerOwnedModuleIdentity() throws Exception {
        assertTrue(CoreLibrarySources.isOresCoreImport("atto.core/numeric.atto"));
        assertTrue(CoreLibrarySources.isOresCoreImport("ores.core/numeric.ores"));
        assertEquals(CoreLibrarySources.oresCoreUnitId("atto.core/numeric.atto"),
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric.ores"));
        assertEquals(CoreLibrarySources.oresCoreSource("atto.core/numeric"),
                CoreLibrarySources.oresCoreSource("ores.core/numeric"));
        assertEquals(CoreLibrarySources.unitId("atto.std/testing"),
                CoreLibrarySources.unitId("std/testing.ores"));
        assertEquals(CoreLibrarySources.unitId("atto.std/core.atto"),
                CoreLibrarySources.unitId("std/core.ores"));
        assertEquals(CoreLibrarySources.source("atto.std/testing"),
                CoreLibrarySources.source("ores.std/testing.ores"));
    }

    @Test
    void canonicalAttoCoreFixtureCompilesLinksAndExecutesGuest() throws Exception {
        Path source = temp.resolve("numeric.atto");
        Files.writeString(source, """
                import module numeric from "atto.core/numeric";
                pub fnc main(): void {
                  stdio.stdout.write(numeric.max_int(40, 42));
                  return;
                }
                """, StandardCharsets.UTF_8);
        assertEquals(2, LinkedProgramRunner.validate(source, Map.of()).units().size());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var program = LinkedProgramRunner.run(source, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), stdout, stderr);
        assertEquals(2, program.units().size());
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unsupportedNamespacesAndTraversalAreNeverAmbientFilesystemImports() {
        for (String path : new String[] {
                "atto.core", "atto.alloc", "atto.alloc/int_buffer",
                "atto.std", "atto.runtime.actor", "atto.runtime.actor/scheduler"
        }) {
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(path), path);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("atto.core/missing.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("atto.core/../numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("atto.core//numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("atto.std/../testing"));
        for (String malformed : new String[]{"atto.core/numeric/", "atto.core/numeric/.atto", "ores.core/numeric/"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreUnitId(malformed), malformed);
        }
        for (String malformed : new String[]{"atto.std/testing/", "atto.std/testing/.ores", "std/testing/"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.unitId(malformed), malformed);
        }
        assertFalse(CoreLibrarySources.isReservedOresLibraryImport("./atto.core/numeric"));
    }
}
