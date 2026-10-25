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
    void rejectsMalformedUtf8BeforeParsingOrExecuting() throws Exception {
        Path source = temp.resolve("invalid-utf8.ores");
        Files.write(source, new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFF});
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }
}
