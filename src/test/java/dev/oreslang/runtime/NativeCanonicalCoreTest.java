package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class NativeCanonicalCoreTest {
    @TempDir Path temp;

    @Test
    void packagedCoreIdentityAndClosedRegistry() throws Exception {
        assertEquals(Set.of("numeric.ores"), CoreLibrarySources.packagedFreestandingCorePaths());
        assertEquals("@core/numeric.ores", CoreLibrarySources.freestandingCoreUnitId("core/numeric"));
        String src = CoreLibrarySources.freestandingCoreSource("core/numeric");
        assertTrue(src.contains("define module numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.freestandingCoreSource("core/missing"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.freestandingCoreUnitId("core/../numeric"));
    }

    @Test
    void canonicalCoreCannotBeShadowedByProjectFile() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("core"));
        Files.writeString(dir.resolve("numeric.ores"),
                "define module numeric as pub fnc min_int(int a, int b): int { return 999; } end");
        Path entry = temp.resolve("entry.ores");
        Files.writeString(entry, """
                import module numeric from "core/numeric";
                pub routine main(): void { return; }
                """);
        var build = LinkedProgramRunner.validate(entry, Map.of());
        assertTrue(build.units().containsKey("@core/numeric.ores"));
    }
}
