package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Admission proof only: canonical freestanding library units do not exist yet. */
final class FreestandingLibraryImportProvenanceTest {
    @TempDir Path temp;

    @Test
    void reservedLibraryPathsCannotFallBackToProjectSources() throws Exception {
        for (String prefix : new String[]{"core", "alloc", "actor", "otp"}) {
            Path directory = Files.createDirectories(temp.resolve(prefix));
            Files.writeString(directory.resolve("shadow.ores"), "garbage placeholder");
            Path entry = temp.resolve("main.ores");
            Files.writeString(entry,
                    "import module shadow from \"" + prefix + "/shadow\";\n"
                    + "pub routine main(): void { return; }\n");
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry, Map.of()), prefix);
            assertTrue(denied.getMessage().contains(prefix.equals("core")
                            ? "unknown compiler-owned core module"
                            : "reserved freestanding library import"),
                    () -> prefix + ": " + denied.getMessage());
            assertTrue(denied.getMessage().contains(prefix + "/shadow"));
        }
    }

    @Test
    void reservedLibraryPathsCannotFallBackToAmbientSearchRoots() throws Exception {
        Path ambient = Files.createDirectories(temp.resolve("ambient"));
        Path injected = Files.createDirectories(ambient.resolve("core"));
        Files.writeString(injected.resolve("intrinsic.ores"), "garbage placeholder");
        Path entry = temp.resolve("entry.ores");
        Files.writeString(entry, """
                import module intrinsic from "core/intrinsic";
                pub routine main(): void { return; }
                """);
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of(
                        "ORESLANG_PATH", ambient.toString())));
        assertTrue(denied.getMessage().contains("unknown compiler-owned core module"));
    }

    @Test
    void onlyBareCanonicalImportsAreReserved() {
        for (String name : new String[]{"core/math","alloc/vec",
                "actor/runtime","otp/supervisor","core\\math",
                "alloc\\vec","actor\\runtime","otp\\supervisor"}) {
            assertTrue(CoreLibrarySources.isReservedFreestandingImport(name));
        }
        for (String local : new String[]{"./core/math","../core/math",
                "core", "core_local/math", "std/core", "std/otp/supervisor"}) {
            assertFalse(CoreLibrarySources.isReservedFreestandingImport(local));
        }
        // Neither slash variant can cause a packaged stdlib name to resolve
        // from the application search path instead of the trusted source.
        assertTrue(CoreLibrarySources.isFreestandingCoreImport("core/numeric"));
        assertTrue(CoreLibrarySources.isFreestandingCoreImport("core\\\\numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.freestandingCoreUnitId("core\\\\numeric"));
        assertTrue(CoreLibrarySources.isCoreImport("std/core"));
        assertTrue(CoreLibrarySources.isCoreImport("std\\core"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std\\core"));
    }
}
