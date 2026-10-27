package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FreestandingLibraryImportProvenanceTest {
    @TempDir Path temp;

    @Test
    void canonicalOresCoreCannotFallBackToProjectSources() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("ores.core"));
        Files.writeString(directory.resolve("shadow.ores"), "garbage placeholder");
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import module shadow from "ores.core/shadow";
                pub routine main(): void { return; }
                """);
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("unknown compiler-owned ores.core module"),
                denied::getMessage);
        assertTrue(denied.getMessage().contains("ores.core/shadow"));
    }

    @Test
    void canonicalOresCoreCannotFallBackToAmbientSearchRoots() throws Exception {
        Path ambient = Files.createDirectories(temp.resolve("ambient"));
        Path injected = Files.createDirectories(ambient.resolve("ores.core"));
        Files.writeString(injected.resolve("numeric.ores"), "garbage placeholder");
        Path entry = temp.resolve("entry.ores");
        Files.writeString(entry, """
                import module numeric from "ores.core/numeric";
                pub routine main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry, Map.of(
                "ORESLANG_PATH", ambient.toString())));
    }

    @Test
    void exactlyTwoCanonicalLibraryRootsAreReserved() {
        for (String name : new String[]{"ores.core/math", "ores.std/core",
                "ores.core\\math", "ores.std\\core"}) {
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(name));
        }
        for (String local : new String[]{"./ores.core/math", "../ores.core/math",
                "ores.core", "ores.std", "alloc/vec", "actor/runtime", "otp/supervisor"}) {
            assertFalse(CoreLibrarySources.isReservedOresLibraryImport(local));
        }

        assertTrue(CoreLibrarySources.isOresCoreImport("ores.core/numeric"));
        assertTrue(CoreLibrarySources.isOresCoreImport("ores.core\\numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("ores.core\\numeric"));

        assertTrue(CoreLibrarySources.isStdImport("ores.std/core"));
        assertTrue(CoreLibrarySources.isStdImport("ores.std\\core"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("ores.std\\core"));

        // Temporary compatibility spelling maps to the same hosted std unit.
        assertTrue(CoreLibrarySources.isStdImport("std/core"));
        assertEquals(CoreLibrarySources.unitId("std/core"),
                CoreLibrarySources.unitId("ores.std/core"));
    }
}
