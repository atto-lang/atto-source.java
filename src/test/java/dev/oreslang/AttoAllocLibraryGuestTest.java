package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Java/Graal guest tests for backend-owned Atto allocation policies.
 * These verify the source interpreter/linker, not physical heap ownership,
 * automatic compiler Drop, native AOT, or zero-tracing-GC.
 */
final class AttoAllocLibraryGuestTest {
    @TempDir Path temp;

    private void expectGuest42(Path entry, int expectedUnits) throws Exception {
        var program = LinkedProgramRunner.validate(entry, Map.of());
        assertEquals(expectedUnits, program.units().size(),
                "compiler-owned imports must resolve without extra units");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var status = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertEquals(42, status.exitStatus(), "must execute actual .atto guest");
        assertEquals("", out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("", err.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void allocModulesAreClosedAndPackaged() throws Exception {
        assertEquals(java.util.Set.of("heap.atto", "arena.atto", "region.atto"),
                CoreLibrarySources.packagedAllocPaths());
        for (String name : new String[] {"heap", "arena", "region"}) {
            String canonical = "atto.alloc/" + name;
            String legacy = "alloc/" + name;
            assertEquals(CoreLibrarySources.allocUnitId(canonical),
                    CoreLibrarySources.allocUnitId(legacy + ".atto"));
            assertEquals(name + ".atto", CoreLibrarySources.allocFileName(canonical));
            assertEquals(CoreLibrarySources.allocSource(canonical),
                    Files.readString(Path.of("stdlib", "alloc", name + ".atto")));
            assertTrue(CoreLibrarySources.allocSource(canonical)
                    .contains("define module " + name + " as"));
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.allocUnitId(canonical + ".ores"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.allocSource("atto.alloc/../heap"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.allocSource("atto.alloc/missing"));
    }

    @Test
    void purePolicyFixtureExecutesRealImportedAtto() throws Exception {
        expectGuest42(Path.of("tests/fixtures/alloc-heap-arena-region-policy.atto"), 4);
    }

    @Test
    void lifecycleStateMachinesExecuteInAtto() throws Exception {
        expectGuest42(Path.of("tests/fixtures/alloc-heap-arena-region-stateful.atto"), 4);
    }

    @Test
    void aliasIdentityAndUntrustedShadowNeverOverridePackagedSource() throws Exception {
        Files.createDirectory(temp.resolve("alloc"));
        Files.writeString(temp.resolve("alloc/heap.atto"),
                "define module heap as pub fnc charged_bytes(int a,int b,int c,int d):int{return 99;} end\n");
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import module heap as H from "atto.alloc/heap";
                import module heap as L from "alloc/heap.atto";
                pub fnc main(): int {
                  if H.charged_bytes(2, 3, 4, 10) != 9 then return 1; fi
                  if L.charged_bytes(2, 3, 4, 10) != 9 then return 2; fi
                  return 42;
                }
                """);
        expectGuest42(entry, 2);
    }

    @Test
    void invalidPackagePathCannotFallBackToAmbientModule() throws Exception {
        Path entry = temp.resolve("invalid.atto");
        Files.writeString(entry, """
                import module heap from "atto.alloc/../heap";
                pub fnc main(): int { return 42; }
                """);
        assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
    }
}
