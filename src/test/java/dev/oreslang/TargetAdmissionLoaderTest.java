package dev.oreslang;

import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TargetAdmissionLoaderTest {
    @TempDir Path temp;

    @Test
    void exactResolvedImportClosureIsAdmittedForBrowser() throws Exception {
        Path main = temp.resolve("main.ores");
        Path dep = temp.resolve("dep.ores");
        Files.writeString(dep, """
                // ores:targets=browser,server
                // ores:requires=clock
                pub fnc answer(): int { return 42; }
                """);
        Files.writeString(main, """
                // ores:targets=browser
                // ores:requires=clock
                import fnc answer from "./dep.ores";
                pub fnc main(): int { return answer(); }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(
                main, Map.of(), IsolatePolicy.developer(),
                PermissionCheckMode.COMPILE, "browser", Set.of("clock")));
    }

    @Test
    void compilerLoaderRejectsTransitiveCapabilityEscalation() throws Exception {
        Path main = temp.resolve("main.ores");
        Path dep = temp.resolve("dep.ores");
        Files.writeString(dep, """
                // ores:targets=browser
                // ores:requires=net.client
                pub fnc answer(): int { return 42; }
                """);
        Files.writeString(main, """
                // ores:targets=browser
                import fnc answer from "./dep.ores";
                pub fnc main(): int { return answer(); }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LinkedProgramRunner.validate(main, Map.of(), IsolatePolicy.developer(),
                        PermissionCheckMode.COMPILE, "browser", Set.of("net.client")));
        assertTrue(error.getMessage().contains("must declare imported capabilities"));
    }

    @Test
    void legacyServerValidationStillPermitsHeaderlessFiles() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, "pub fnc main(): int { return 42; }\n");
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(main, Map.of(),
                IsolatePolicy.developer(), PermissionCheckMode.COMPILE));
        assertThrows(IllegalArgumentException.class, () ->
                LinkedProgramRunner.validate(main, Map.of(), IsolatePolicy.developer(),
                        PermissionCheckMode.COMPILE, "browser", Set.of()));
    }
}
