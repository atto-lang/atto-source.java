package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A coarse host capability is not a Deno-style host-I/O permission grant.
 * Exercise the actual CLI -> linked source -> runtime boundary rather than
 * testing only a policy builder in isolation.
 */
final class DenoCliDefaultPermissionsTest {
    @TempDir Path temp;

    @Test
    void legacyCoarseEnvironmentCapabilityAloneDoesNotGrantGuestEnvironment() throws Exception {
        Path source = sourceReadingEnvironment();
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--allow=ENVIRONMENT", source.toString()
        }), "coarse isolate capability must not silently become --allow-env");
    }

    @Test
    void explicitEnvironmentScopeIsRequiredAndDenyWins() throws Exception {
        Path source = sourceReadingEnvironment();
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--allow-env=PATH", source.toString()
        }), "explicitly scoped environment access should remain supported");
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--allow-env=PATH", "--deny-env=PATH", source.toString()
        }), "explicit deny must override environment allow");
    }

    private Path sourceReadingEnvironment() throws Exception {
        Path source = temp.resolve("permission-env.ores");
        Files.writeString(source, """
                pub fnc main(): void {
                    val exists = env.has("PATH");
                    return;
                }
                """);
        return source;
    }
}
