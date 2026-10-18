package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class FailureAnalysisCliTest {
    @Test void analyzeReportsSuggestionsWithoutExecutingPanic() throws Exception {
        var source = Files.createTempFile("ores-failure-analysis-", ".ores");
        var stderr = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            Files.writeString(source, "pub fnc main(): void { panic \"must-not-run\"; }");
            System.setErr(new PrintStream(stderr));
            assertEquals(0, OresMain.exitStatusForArgs(new String[]{"--analyze", source.toString()}));
            assertTrue(stderr.toString().contains("failure-suggestion"));
            assertTrue(stderr.toString().contains("panics"));
        } finally {
            System.setErr(original);
            Files.deleteIfExists(source);
        }
    }
}
