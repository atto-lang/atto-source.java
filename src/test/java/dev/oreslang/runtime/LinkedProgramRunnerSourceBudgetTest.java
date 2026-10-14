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

    @Test
    void importedUnitCannotBypassInputBudget() throws Exception {
        Path entry = temp.resolve("entry.ores");
        Path imported = temp.resolve("child.ores");
        Files.writeString(entry, """
                import * as imported from "./child.ores";
                pub routine main(): void { return; }
                """);
        Files.write(imported, new byte[BoundedSourceReader.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(entry, Map.of()));
    }

    @Test
    void importedUnitMustBeValidUtf8() throws Exception {
        Path entry = temp.resolve("entry-utf8.ores");
        Path imported = temp.resolve("child-utf8.ores");
        Files.writeString(entry, """
                import * as imported from "./child-utf8.ores";
                pub routine main(): void { return; }
                """);
        Files.write(imported, new byte[] {(byte) 0xC0, (byte) 0xAF});
        assertThrows(MalformedInputException.class, () -> LinkedProgramRunner.validate(entry, Map.of()));
    }

}
