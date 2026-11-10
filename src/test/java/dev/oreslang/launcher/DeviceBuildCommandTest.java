package dev.oreslang.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

final class DeviceBuildCommandTest {
    @TempDir Path temp;

    @Test void rejectsAmbiguousOrMissingInputBeforeLaunchingTools() throws Exception {
        Path goodSource = temp.resolve("main.ores");
        Files.writeString(goodSource, "pub routine main(): int { return 42; }");
        Path output = temp.resolve("app");
        Files.writeString(output, "existing output cannot be replaced");
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"build"}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {"build", "a", "b", "extra"}));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"build", goodSource.toString(), output.toString()}));
        assertEquals("existing output cannot be replaced", Files.readString(output));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"build", temp.resolve("missing.ores").toString(), temp.resolve("other").toString()}));
    }

    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void localBuildWithMockDriverIsExplicitlyUntrustedAndScrubsTokenEnvironment() throws Exception {
        Path source = temp.resolve("main.ores");
        Files.writeString(source, "pub routine main(): int { return 42; }");
        Path driver = temp.resolve("fake-driver.sh");
        Files.writeString(driver, """
                #!/bin/sh
                set -eu
                test -z "$(printenv ORESLANG_API_TOKEN)" || exit 97
                printf '#!/bin/sh\\nexit 42\\n' > "$2"
                chmod +x "$2"
                """);
        assertTrue(driver.toFile().setExecutable(true));
        Path output = temp.resolve("app");
        String javaExec = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaExec, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "dev.oreslang.launcher.OresMain", "build", source.toString(), output.toString());
        pb.redirectErrorStream(true);
        pb.environment().put("ORESLANG_LLVM_AOT_DRIVER", driver.toAbsolutePath().toString());
        pb.environment().put("ORESLANG_LLVMC", driver.toAbsolutePath().toString());
        pb.environment().put("CLANG", driver.toAbsolutePath().toString());
        pb.environment().put("ORESLANG_API_TOKEN", "must-not-be-inherited");
        Process proc = pb.start();
        proc.getOutputStream().close();
        String stdout = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "mock local wrapper timed out");
        assertEquals(0, proc.exitValue(), stdout);
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.size(output) > 0);
        assertTrue(stdout.contains("\"trust\":\"untrusted-self-report\""), stdout);
        assertTrue(stdout.contains("\"artifactSha256\":"), stdout);
        assertFalse(stdout.contains("must-not-be-inherited"), stdout);
        // Fake shell driver is NOT evidence of actual LLVM compilation.
    }
}
