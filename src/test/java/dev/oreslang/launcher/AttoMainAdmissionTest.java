package dev.oreslang.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AttoMainAdmissionTest {
    @TempDir Path temp;

    @Test
    void checkAcceptsAttoGuestSource() throws Exception {
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, "pub fnc main(): void { return; }");
        assertEquals(0, OresMain.exitStatusForArgs(
                new String[] {"--check", entry.toString()}));
    }

    @Test
    void buildAnalysisAcceptsAttoGuestSource() throws Exception {
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, "pub fnc main(): void { return; }");
        assertEquals(0, OresMain.exitStatusForArgs(
                new String[] {"--build-analysis", entry.toString()}));
    }
    @Test
    void checkUsesCanonicalAttoPackageManifest() throws Exception {
        Path project = temp.resolve("attolang-project");
        Path source = project.resolve("src");
        Files.createDirectories(source);
        Path entry = source.resolve("main.atto");
        Files.writeString(source.resolve("answer.atto"), "pub fnc answer(): int { return 42; }");
        Files.writeString(entry, """
                import fnc answer from "./answer";
                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """);
        Files.writeString(project.resolve(".atto.pkg.toml"), """
                schema_version = "1"
                [project]
                name = "attolang-test"
                [source]
                roots = ["src"]
                [entrypoints]
                main = "src/main.atto"
                """);
        assertEquals(0, OresMain.exitStatusForArgs(
                new String[] {"--check", entry.toString()}));
    }

}
