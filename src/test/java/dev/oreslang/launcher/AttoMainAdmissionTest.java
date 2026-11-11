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
}
