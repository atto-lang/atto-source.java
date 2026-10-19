package dev.oreslang.launcher;

import dev.oreslang.runtime.OresTestExploration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class OresTestTraceTest {
    @TempDir Path temporary;

    @Test
    void recordedInputTraceReplaysOnlyMatchingSourceAndFingerprint() throws Exception {
        Path source = temporary.resolve("suite.ores");
        Path trace = temporary.resolve("gate.trace");
        Files.writeString(source, "initial source bytes\n");
        var result = new OresTestExploration.Report(21, 4, 19, 0, 0, 1234567);
        OresTestTrace.write(trace, source, 42, 19, true, result);
        var recorded = OresTestTrace.read(trace, source);
        assertEquals(42L, recorded.seed());
        assertEquals(19, recorded.runs());
        assertTrue(recorded.modelCheck());
        assertDoesNotThrow(() -> OresTestTrace.verify(recorded, result));
        assertThrows(IOException.class, () -> OresTestTrace.verify(recorded,
                new OresTestExploration.Report(21, 4, 19, 0, 0, 7654321)));
        assertThrows(IOException.class,
                () -> OresTestTrace.write(trace, source, 42, 19, true, result));
    }

    @Test
    void sourceMutationInvalidatesExistingTrace() throws Exception {
        Path source = temporary.resolve("mutable.ores");
        Path trace = temporary.resolve("mutable.trace");
        Files.writeString(source, "v1\n");
        var result = new OresTestExploration.Report(21, 4, 0, 0, 0, 123);
        OresTestTrace.write(trace, source, 0, 0, true, result);
        Files.writeString(source, "v2\n");
        assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
    }

    @Test
    void rejectMalformedAndOutOfBudgetTraceRecords() throws Exception {
        Path source = temporary.resolve("suite.ores");
        Path trace = temporary.resolve("gate.trace");
        Files.writeString(source, "source\n");
        var result = new OresTestExploration.Report(0, 0, 3, 0, 0, 123);
        OresTestTrace.write(trace, source, 3, 3, false, result);
        String original = Files.readString(trace);
        try {
            Files.writeString(trace, original.replace("runs=3\n", "runs=100001\n"));
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
            Files.writeString(trace, original + "unexpected=1\n");
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
            Files.writeString(trace, original.replace("model_check=false",
                    "model_check=maybe"));
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
            Files.writeString(trace, original.replace("seed=3", "seed=bad"));
            assertThrows(IOException.class, () -> OresTestTrace.read(trace, source));
        } finally {
            Files.writeString(trace, original);
        }
        assertEquals(result.inputFingerprint(),
                OresTestTrace.read(trace, source).inputFingerprint());
    }
}
