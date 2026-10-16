package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** End-to-end CLI selection must reach the same compiler import resolver. */
final class OresMainProjectRootCliTest {
    @TempDir Path temp;

    @Test
    void cliManifestAndRootFlagsResolveRootedImports() throws Exception {
        Path root = temp.resolve("app");
        Files.createDirectories(root.resolve("src"));
        Path manifest = root.resolve(".ores.pkg.toml");
        Files.writeString(manifest, """
                schema_version = "1"
                [package]
                name = "root-cli"
                [source]
                roots = []
                [entrypoints]
                main = "src/main.ores"
                """);
        Files.writeString(root.resolve("src/helper.ores"), """
                pub fnc helper(): int { return 42; }
                """);
        Path main = root.resolve("src/main.ores");
        Files.writeString(main, """
                import fnc helper from "+/src/helper";
                pub routine main(): void {
                    stdio.println(helper());
                    return;
                }
                """);

        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--check", "--manifest-path=" + manifest, main.toString()
        }));
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--check", "--project-root=" + root, main.toString()
        }));
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--check", "--manifest-path=" + manifest,
                "--project-root=" + root, main.toString()
        }));
        // Selecting a manifest must work even when the working directory is
        // outside the package and the source filename is omitted.
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--check", "--manifest-path=" + manifest
        }));
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--check", "--project-root=" + root
        }));
    }

    @Test
    void cliRejectsInvalidRootFlagsAndOutsideSource() throws Exception {
        Path root = temp.resolve("app");
        Files.createDirectories(root);
        Path manifest = root.resolve(".ores.pkg.toml");
        Files.writeString(manifest, "schema_version = \"1\"\n");
        Path outside = Files.writeString(temp.resolve("outside.ores"),
                "pub routine main(): void { return; }");

        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"--manifest-path="}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"--project-root="}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                "--manifest-path=" + manifest, "--manifest-path=" + manifest
        }));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                "--project-root=" + root, "--project-root=" + root
        }));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--check", "--manifest-path=" + manifest, outside.toString()
        }));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--check", "--project-root=" + root,
                "--manifest-path=" + temp.resolve("different/.ores.pkg.toml"), outside.toString()
        }));
    }
}
