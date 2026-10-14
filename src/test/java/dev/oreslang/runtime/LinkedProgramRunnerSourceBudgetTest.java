package dev.oreslang.runtime;

import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LinkedProgramRunnerSourceBudgetTest {
    @TempDir Path temp;

    @Test
    void linkedCompilerRejectsOversizedEntryBeforeParsing() throws Exception {
        Path source = temp.resolve("oversized.ores");
        Files.write(source, new byte[BoundedSourceReader.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(source, Map.of()));
    }

    @Test
    void linkedCompilerRejectsMalformedUtf8Entry() throws Exception {
        Path source = temp.resolve("invalid.ores");
        Files.write(source, new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertThrows(MalformedInputException.class, () -> LinkedProgramRunner.validate(source, Map.of()));
    }

    @Test
    void mixedJavaSourceIsAlsoBounded() throws Exception {
        Path source = temp.resolve("oversized.java");
        Files.write(source, new byte[BoundedSourceReader.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(source, Map.of()));
    }
}
