package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
    void hostedCompilerExecutesNumericCoreWithoutJavaSourceInterop() throws Exception {
        Path entry = temp.resolve("run.ores");
        Files.writeString(entry, """
                import module numeric from "core/numeric";
                pub routine main(): void {
                    stdio.println(numeric.min_int(9, 4));
                    stdio.println(numeric.clamp_int(15, 0, 10));
                    return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, err);
        assertTrue(build.units().containsKey("@core/numeric.ores"));
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals("4\\n10",
                stdout.replace("\\r\\n", "\\n").trim(),
                () -> "stdout=" + stdout + " stderr="
                        + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void canonicalCoreCannotBeShadowedByProjectFile() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("core"));
        Files.writeString(dir.resolve("numeric.ores"),
                "define module numeric as pub fnc min_int(int a, int b): int { return 999; } end");
        Path entry = temp.resolve("entry.ores");
        Files.writeString(entry, """
                import module numeric from "core/numeric";
                pub routine main(): void {
                    stdio.println(numeric.min_int(9, 4));
                    return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
        assertTrue(build.units().containsKey("@core/numeric.ores"));
        assertEquals("4", out.toString(StandardCharsets.UTF_8).trim(),
                "project-local core/numeric.ores must never shadow trusted core");
    }
}
