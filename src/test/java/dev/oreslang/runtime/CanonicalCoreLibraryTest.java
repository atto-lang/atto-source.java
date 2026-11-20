package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalCoreLibraryTest {
    @TempDir Path temp;

    @Test
    void packagedCoreIdentityAndClosedRegistry() throws Exception {
        assertEquals(Set.of("numeric.atto"), CoreLibrarySources.packagedCorePaths());
        assertEquals("@ores.core/numeric.ores",
                CoreLibrarySources.oresCoreUnitId("ores.core/numeric"));
        assertTrue(CoreLibrarySources.oresCoreSource("ores.core/numeric")
                .contains("define module numeric"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/missing"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreUnitId("ores.core/../numeric"));
    }

    @Test
    void hostedJavaMayLinkCoreSourceWithoutClaimingFreestandingExecution() throws Exception {
        Path entry = temp.resolve("run.ores");
        Files.writeString(entry, """
                import module numeric from "ores.core/numeric";
                pub routine main(): void {
                    stdio.println(numeric.min_int(9, 4));
                    stdio.println(numeric.clamp_int(15, 0, 10));
                    return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);

        assertTrue(build.units().containsKey("@ores.core/numeric.ores"));
        String stdout = out.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n").trim();
        assertEquals("4\n10", stdout,
                () -> "stdout=" + stdout + " stderr="
                        + err.toString(StandardCharsets.UTF_8));
    }
}
