package dev.oreslang;

import dev.oreslang.compiler.ExperimentalHirV1Exporter;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-scalar checked-i64 boundaries. These tests execute guest source through
 * the linked evaluator; an HIR serialization-only test would be insufficient.
 */
final class ExperimentalHirV1CheckedArithmeticBoundaryTest {
    private static final String OPT_IN = "// oreslang:checked-i64-v1\n";
    @TempDir Path directory;

    private int run(String source) throws Exception {
        Path input = directory.resolve("boundary-" + Integer.toHexString(source.hashCode()) + ".ores");
        Files.writeString(input, source);
        return LinkedProgramRunner.runWithExitStatus(input, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).exitStatus();
    }

    private static void requireOverflow(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains("ORES_I64_OVERFLOW")) return;
        }
        fail("Expected the Oreslang checked-i64 diagnostic in the guest failure cause chain", failure);
    }

    @Test
    void multiplicationByZeroAtMaximumDoesNotOverflow() throws Exception {
        assertEquals(43, run(OPT_IN + """
                pub fnc main(): int {
                  return 9223372036854775807 * 0 == 0 ? 43 : 1;
                }
                """));
    }

    @Test
    void minimumTimesNegativeOneMustOverflow() {
        String source = OPT_IN + """
                pub fnc main(): int {
                  return (-9223372036854775807 - 1) * -1;
                }
                """;
        requireOverflow(assertThrows(RuntimeException.class, () -> run(source)));
    }

    @Test
    void unreachableOverflowBranchIsNeverEvaluated() throws Exception {
        assertEquals(43, run(OPT_IN + """
                pub fnc main(): int {
                  return 1 < 0 ? 9223372036854775807 + 1 : 43;
                }
                """));
    }

    @Test
    void checkedDirectiveMustStartAtByteZeroAndEndWithLf() throws Exception {
        String potentialOverflow = """
                pub fnc scale(int count): int { return count * 43; }
                pub fnc main(): int { return scale(1); }
                """;
        assertDoesNotThrow(() -> ExperimentalHirV1Exporter.fromSource(OPT_IN + potentialOverflow));
        for (String prefixed : new String[] {
                "\n" + OPT_IN,
                "// introductory comment\n" + OPT_IN,
                " " + OPT_IN,
                "// oreslang:checked-i64-v1\r\n"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> ExperimentalHirV1Exporter.fromSource(prefixed + potentialOverflow),
                    "Non-exact opt-in must not advertise checked-i64 guest execution");
        }
        // A directive on line two is a comment, not a silent language-wide switch.
        assertEquals(43, run("\n" + OPT_IN + """
                pub fnc main(): int {
                  return 9223372036854775807 + 1 < 0 ? 43 : 1;
                }
                """));
    }
}
