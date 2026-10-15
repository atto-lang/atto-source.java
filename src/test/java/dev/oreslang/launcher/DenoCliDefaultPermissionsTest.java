package dev.oreslang.launcher;

import dev.oreslang.runtime.RuntimePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void invalidPathScopeCannotPartiallyCommitEarlierGrantsOrDenials() {
        String invalidPath = "/invalid" + (char) 0 + "path";
        RuntimePermissions.Builder allow = new RuntimePermissions.Builder();
        assertThrows(IllegalArgumentException.class,
                () -> allow.allowCsv(RuntimePermissions.Permission.READ,
                        temp.resolve("safe").toString() + "," + invalidPath));
        assertFalse(allow.build().allows(RuntimePermissions.Permission.READ,
                temp.resolve("safe").toString()));

        RuntimePermissions.Builder deny = new RuntimePermissions.Builder()
                .allowAll(RuntimePermissions.Permission.READ);
        assertThrows(IllegalArgumentException.class,
                () -> deny.denyCsv(RuntimePermissions.Permission.READ,
                        temp.resolve("safe").toString() + "," + invalidPath));
        assertTrue(deny.build().allows(RuntimePermissions.Permission.READ,
                temp.resolve("safe").toString()));
    }

    @Test
    void missingPermissionFlagsCannotInheritDeveloperIo() throws Exception {
        Path source = sourceReadingEnvironment();
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                source.toString()
        }), "an omitted permission policy cannot inherit developer I/O grants");
    }

    @Test
    void invalidSingleDenyScopesRejectControlCharactersAndMalformedUtf16() {
        for (RuntimePermissions.Permission permission : new RuntimePermissions.Permission[]{
                RuntimePermissions.Permission.ENV,
                RuntimePermissions.Permission.RUN,
                RuntimePermissions.Permission.SYS}) {
            for (String malformed : new String[]{
                    "SAFE" + (char) 0 + "KEY", "SAFE\nKEY",
                    String.valueOf((char) 0xd800),
                    String.valueOf((char) 0xdc00)}) {
                RuntimePermissions.Builder builder = new RuntimePermissions.Builder()
                        .allowAll(permission);
                assertThrows(IllegalArgumentException.class,
                        () -> builder.deny(permission, malformed),
                        "invalid single denial was accepted for " + permission);
                assertTrue(builder.build().allows(permission, "SAFE"),
                        "a rejected single denial must leave the builder unchanged");
            }
        }
    }

    @Test
    void invalidSingleDenyCliOptionIsAUsageError() throws Exception {
        Path source = sourceReadingEnvironment();
        for (String malformed : new String[]{
                "--deny-env=SAFE" + (char) 0 + "KEY",
                "--deny-env=SAFE\nKEY",
                "--deny-env=" + String.valueOf((char) 0xd800)}) {
            assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                    "--allow-env=PATH", malformed, source.toString()
            }), "malformed single-deny CLI argument must reject the invocation");
        }
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
