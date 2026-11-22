package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real imported Atto alloc source, safety checks and guest execution. */
final class AttoAllocGuestTest {
    @TempDir Path temp;

    @Test
    void closedAllocSourcesResolveToOneCompilerOwnedUnitPerModule() throws Exception {
        assertEquals(6, CoreLibrarySources.packagedAttoAllocPaths().size());
        for (String name : new String[]{"allocator", "set", "drop", "arc", "pool", "weak"}) {
            String canonical = "atto.alloc/" + name;
            String source = CoreLibrarySources.attoAllocSource(canonical);
            assertTrue(source.contains("define module "), canonical);
            assertEquals(source, CoreLibrarySources.attoAllocSource(canonical + ".atto"));
            assertEquals(CoreLibrarySources.attoAllocUnitId(canonical),
                    CoreLibrarySources.attoAllocUnitId(canonical + ".atto"));
            assertEquals(name + ".atto", CoreLibrarySources.attoAllocFileName(canonical));
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(canonical));
            assertDoesNotThrow(() -> Parser.parse(source), canonical);
        }
    }

    @Test
    void rejectTraversalUnlistedSuffixesAndNamespaceShadowing() throws Exception {
        for (String invalid : new String[]{
                "atto.alloc/../set",
                "atto.alloc/./set",
                "atto.alloc/set/../arc",
                "atto.alloc/set//arc",
                "atto.alloc/set.ores",
                "atto.alloc/unknown",
                "atto.alloc/set.atto/anything",
                "atto.alloc/%2e%2e/set",
                "atto.alloc\\set",
                "atto.alloc/",
                "atto.alloc/set" + (char) 0
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.attoAllocSource(invalid), invalid);
        }
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("atto.alloc"));
        assertTrue(CoreLibrarySources.isReservedOresLibraryImport("atto.alloc/unknown"));
        Path forged = temp.resolve("shadow.atto");
        Files.writeString(forged, """
                import fnc self_test from "atto.alloc/unknown";
                pub fnc main(): int { return self_test() ? 42 : 1; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(forged, Map.of()));
    }

    @Test
    void importedAttoAllocSelfTestsExecuteAsGuestPrograms() throws Exception {
        for (String module : new String[]{"allocator", "set", "drop", "arc", "pool", "weak"}) {
            Path guest = Path.of("tests", "fixtures", "alloc-java-guest",
                    module + "-positive.atto");
            assertTrue(Files.isRegularFile(guest), "missing native .atto fixture " + guest);
            assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest, Map.of()), module);
            var result = LinkedProgramRunner.runWithExitStatus(
                    guest, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(),
                    "must execute compiled Atto policy code: " + module);
        }
    }

    @Test
    void missingOrForgedAllocExportsRejectAtLink() throws Exception {
        Path bad = Path.of("tests", "fixtures", "alloc-java-guest",
                "missing-export-negative.atto");
        assertTrue(Files.isRegularFile(bad));
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(bad, Map.of()),
                "only real declarations may be imported from closed alloc units");
    }
}
