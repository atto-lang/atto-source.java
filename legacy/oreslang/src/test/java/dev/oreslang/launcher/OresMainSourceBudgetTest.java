package dev.oreslang.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class OresMainSourceBudgetTest {
    @TempDir Path temp;

    @Test
    void rejectsOversizedOresInputBeforeParsingOrExecuting() throws Exception {
        Path source = temp.resolve("oversized.ores");
        Files.write(source, new byte[16 * 1024 * 1024 + 1]);
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }

    @Test
    void rejectsOversizedAttoInputBeforeParsingOrExecuting() throws Exception {
        Path source = temp.resolve("oversized.atto");
        Files.write(source, new byte[16 * 1024 * 1024 + 1]);
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }

    @Test
    void rejectsMalformedAttoUtf8BeforeParsingOrExecuting() throws Exception {
        Path source = temp.resolve("invalid-utf8.atto");
        Files.write(source, new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFF});
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }

    @Test
    void rejectsMalformedUtf8BeforeParsingOrExecuting() throws Exception {
        Path source = temp.resolve("invalid-utf8.ores");
        Files.write(source, new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFF});
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }

    @Test
    void checkRejectsAttoSourceThroughSymlinkedDirectory() throws Exception {
        Path real = temp.resolve("real-cli-source");
        Files.createDirectories(real);
        Files.writeString(real.resolve("main.atto"), "pub fnc main(): void { return; }");
        Path alias = temp.resolve("alias-cli-source");
        try {
            Files.createSymbolicLink(alias, real.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not available");
            return;
        }
        // This is intentionally a direct CLI admission probe, not a linked
        // guest test. The reader must deny the path before parsing.
        assertEquals(1, OresMain.exitStatusForArgs(
                new String[] {"--check", alias.resolve("main.atto").toString()}));
    }

}
