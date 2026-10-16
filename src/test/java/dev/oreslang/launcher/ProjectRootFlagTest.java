package dev.oreslang.launcher;

import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProjectRootFlagTest {
    @TempDir Path temp;

    @Test
    void checkModeAcceptsExplicitRootAndNestedRootImports() throws Exception {
        Path root = temp.resolve("app");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["src"]
                """);
        Files.writeString(root.resolve("lib/tool.ores"), """
                pub fnc value(): int { return 42; }
                """);
        Path entry = root.resolve("src/main.ores");
        Files.writeString(entry, """
                import fnc value from "+/lib/tool";
                pub fnc main(): void { stdio.println(value()); return; }
                """);

        assertEquals(0, OresMain.exitStatusForArgs(new String[] {
                "--check", "--project-root=" + root, entry.toString()
        }));
        // Simulates invocation from a different working directory: src/main.ores
        // must be interpreted relative to the explicitly selected project root.
        assertEquals(0, OresMain.exitStatusForArgs(new String[] {
                "--check", "--project-root=" + root, "src/main.ores"
        }));
    }
}
