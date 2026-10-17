package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keep flags-2-env migration from reviving ambient legacy host-I/O grants. */
final class Flags2EnvDefaultDenyTest {
    @TempDir Path temp;

    @Test
    void standaloneCliNeverInheritsDeveloperEnvironmentPermission() throws Exception {
        Path source = temp.resolve("env-scope.ores");
        Files.writeString(source, """
                pub fnc main(): void {
                    val present = env.has("PATH");
                    return;
                }
                """);
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{source.toString()}));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--allow=ENVIRONMENT", source.toString()
        }), "coarse capability is not an ambient scoped grant");
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--allow-env=PATH", source.toString()
        }));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--allow-env=PATH", "--deny-env=PATH", source.toString()
        }), "explicit deny must override scoped grant");
    }

    @Test
    void emptyPermissionCheckValueCannotSilentlyDefer() throws Exception {
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[]{"--permission-check=", "missing.ores"}));
    }
}
