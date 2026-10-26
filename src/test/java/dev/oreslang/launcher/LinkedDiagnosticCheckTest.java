package dev.oreslang.launcher;

import dev.oreslang.compiler.CompilationDiagnosticsException;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedDiagnosticCheckTest {
    @TempDir Path temp;

    private Path badEntry() throws Exception {
        Path entry = temp.resolve("broken.ores");
        StringBuilder body = new StringBuilder("define module broken as\n");
        for (int n = 0; n < 6; n++)
            body.append("  pub fnc bad").append(n).append("(): int { return 1 + ; }\n");
        Files.writeString(entry, body.append("end\n").toString());
        return entry;
    }

    @Test
    void realFileLoaderRejectsSixIndependentParserErrors() throws Exception {
        Path entry = badEntry();
        for (int limit : new int[]{1, 5, 10, 0}) {
            var failure = assertThrows(CompilationDiagnosticsException.class,
                    () -> LinkedProgramRunner.validateWithDiagnostics(
                            entry, Map.of(), null, null, limit));
            assertEquals(limit == 1 ? 1 : limit == 5 ? 5 : 6,
                    failure.diagnostics().size());
            assertTrue(failure.diagnostics().stream()
                    .allMatch(d -> d.primary().unitId()
                            .endsWith("broken.ores")));
        }
    }

    @Test
    void cliRejectsBadFlagsAndNeverReportsInvalidSourceAsSuccess() throws Exception {
        Path entry = badEntry();
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                "--check", "--max-errors=-1", entry.toString()}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                "--check", "--max-errors=1001", entry.toString()}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                "--max-errors=5", entry.toString()}));
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--check", "--max-errors=5", entry.toString()}));
    }
}
