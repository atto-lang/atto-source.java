package dev.oreslang.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real CLI boundary, no guest execution: do not conflate unknown with pass. */
final class CompilerAnalysisV0CliTest {
    @TempDir Path temp;

    private int analyze(String name, String source) throws Exception {
        Path entry = temp.resolve(name);
        Files.writeString(entry, source);
        byte[] before = Files.readAllBytes(entry);
        int status = OresMain.exitStatusForArgs(
                new String[] {"--analyze", entry.toString()});
        assertArrayEquals(before, Files.readAllBytes(entry),
                "analysis must not mutate the guest source");
        try (var stream = Files.list(temp)) {
            assertEquals(1L, stream.count(), "analysis must not create build artifacts");
        }
        return status;
    }

    @Test
    void validScalarSourceCompletesNarrowAnalysis() throws Exception {
        assertEquals(0, analyze("main.atto",
                "pub fnc main(): int { return 42; }"));
    }

    @Test
    void checkedArithmeticCannotReceiveAnEffectFreeCertificate() throws Exception {
        assertEquals(2, analyze("main.atto",
                "pub fnc main(): int { return 1 + 2; }"));
    }

    @Test
    void malformedSourceIsNotInconclusiveButAnError() throws Exception {
        assertEquals(1, analyze("main.atto",
                "pub fnc main(): int { return ; }"));
    }

    @Test
    void analyzeCannotBeCombinedWithExecutionOrBuildModes() throws Exception {
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, "pub fnc main(): int { return 42; }");
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--analyze", "--check", entry.toString()}));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--analyze", "--build-analysis", entry.toString()}));
    }
}
