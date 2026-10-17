package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class Flags2EnvLauncherTest {
    @TempDir Path temp;

    @Test
    void guestCliBooleanNegationHasLastFlagPrecedence() throws Exception {
        Path source = temp.resolve("flags2env-main.ores");
        Files.writeString(source, "pub routine main(): int { return 7; }");
        assertEquals(0, OresMain.exitStatusForArgs(new String[] {
                "--no-check", "--check", source.toString()
        }));
        assertEquals(7, OresMain.exitStatusForArgs(new String[] {
                "--check", "--no-check", source.toString()
        }));
        assertEquals(7, OresMain.exitStatusForArgs(new String[] {
                "--build-analysis", "--no-build-analysis", source.toString()
        }));
        assertEquals(0, OresMain.exitStatusForArgs(new String[] {
                "--strict-isolate", "--no-strict-isolate", "--check", source.toString()
        }));
    }
}
