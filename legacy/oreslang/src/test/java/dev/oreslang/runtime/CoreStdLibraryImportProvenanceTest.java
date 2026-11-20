package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CoreStdLibraryImportProvenanceTest {
    @TempDir Path temp;

    @Test
    void canonicalCoreCannotFallBackToProjectSource() throws Exception {
        Path injected = Files.createDirectories(temp.resolve("ores.core"));
        Files.writeString(injected.resolve("missing.ores"), "garbage placeholder");
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import module missing from "ores.core/missing";
                pub routine main(): void { return; }
                """);

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("unknown compiler-owned ores.core module"),
                denied::getMessage);
    }

    @Test
    void canonicalCoreIgnoresAmbientOreslangPathShadow() throws Exception {
        Path ambient = Files.createDirectories(temp.resolve("ambient"));
        Path injected = Files.createDirectories(ambient.resolve("ores.core"));
        Files.writeString(injected.resolve("numeric.ores"), "garbage placeholder");

        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import module numeric from "ores.core/numeric";
                pub routine main(): void { return; }
                """);

        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry, Map.of(
                "ORESLANG_PATH", ambient.toString())));
    }

    @Test
    void onlyTwoCanonicalLibraryRootsAreReserved() {
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("ores.core/math"));
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("ores.std/core"));
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("ores.core"));
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("ores.std"));

        assertFalse(CoreLibrarySources.isReservedOresLibraryImport("./ores.core/math"));
        assertFalse(CoreLibrarySources.isReservedOresLibraryImport("alloc/vector"));
        assertFalse(CoreLibrarySources.isReservedOresLibraryImport("actor/runtime"));
        assertFalse(CoreLibrarySources.isReservedOresLibraryImport("otp/supervisor"));

        assertTrue(CoreLibrarySources.isOresCoreImport("ores.core/numeric"));
        assertTrue(CoreLibrarySources.isStdImport("ores.std/core"));
        assertTrue(CoreLibrarySources.isStdImport("std/core"));
        assertEquals(CoreLibrarySources.unitId("std/core"),
                CoreLibrarySources.unitId("ores.std/core"));
    }

    @Test
    void malformedCanonicalSeparatorsFailBeforeFilesystemFallback() {
        assertTrue(CoreLibrarySources.isOresCoreImport("ores.core\\numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("ores.core\\numeric"));

        assertTrue(CoreLibrarySources.isStdImport("ores.std\\core"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("ores.std\\core"));
    }
}
