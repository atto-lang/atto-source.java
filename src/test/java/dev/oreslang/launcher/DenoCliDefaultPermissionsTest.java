package dev.oreslang.launcher;

import dev.oreslang.runtime.RuntimePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void malformedCommaSeparatedAllowAndDenyScopesReturnUsageError() throws Exception {
        Path source = sourceReadingEnvironment();
        for (String option : new String[]{
                "--allow-env=PATH,",
                "--allow-env=,PATH",
                "--allow-env=PATH,,HOME",
                "--deny-env=PATH,",
                "--deny-env=PATH, ,HOME",
                "--allow-net=localhost:443,,example.com"
        }) {
            assertEquals(2, OresMain.exitStatusForArgs(
                    new String[]{option, source.toString()}),
                    "malformed permission flag must not be silently normalized: " + option);
        }
    }

    @Test
    void malformedCsvIsRejectedBeforeApplyingAnyEarlierScopes() {
        RuntimePermissions.Builder allow = new RuntimePermissions.Builder();
        assertThrows(IllegalArgumentException.class,
                () -> allow.allowCsv(RuntimePermissions.Permission.ENV, "PATH,,HOME"));
        assertFalse(allow.build().allows(RuntimePermissions.Permission.ENV, "PATH"));

        RuntimePermissions.Builder deny = new RuntimePermissions.Builder();
        assertThrows(IllegalArgumentException.class,
                () -> deny.denyCsv(RuntimePermissions.Permission.ENV, "PATH,"));
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
