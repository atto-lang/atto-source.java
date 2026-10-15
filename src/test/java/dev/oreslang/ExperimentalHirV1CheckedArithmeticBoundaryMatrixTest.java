package dev.oreslang;

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
 * Executed guest regressions, not merely exporter/AST serialization checks.
 * Marked sources must never return an intermediate wrapped result.
 */
final class ExperimentalHirV1CheckedArithmeticBoundaryMatrixTest {
    private static final String HEADER = "// oreslang:checked-i64-v1\n";
    @TempDir Path directory;

    private int run(String source) throws Exception {
        Path path = Files.createTempFile(directory, "checked-boundary-", ".ores");
        Files.writeString(path, source);
        return LinkedProgramRunner.runWithExitStatus(path, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).exitStatus();
    }

    private void overflow(String body) {
        String source = HEADER + "pub fnc main(): int { return " + body + "; }\n";
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> run(source), source);
        boolean classified = false;
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (String.valueOf(cursor.getMessage()).contains("ORES_I64_OVERFLOW")) {
                classified = true;
                break;
            }
        }
        assertTrue(classified, "Guest checked arithmetic must report ORES_I64_OVERFLOW: " + error);
    }

    @Test
    void checkedArithmeticPreservesSuccessfulI64BoundaryOperations() throws Exception {
        assertEquals(43, run(HEADER + "pub fnc main(): int { return 9223372036854775807 * 0 + 43; }"));
        assertEquals(43, run(HEADER + "pub fnc main(): int { return (-9223372036854775807 - 1) * 0 + 43; }"));
        assertEquals(43, run(HEADER + "pub fnc main(): int { return 40 + 3; }"));
        assertEquals(43, run(HEADER + "pub fnc main(): int { return 44 - 1; }"));
        assertEquals(43, run(HEADER + "pub fnc main(): int { return 1 * 43; }"));
    }

    @Test
    void intermediateOverflowCannotBeOptimizedAwayByZeroMultiplication() {
        overflow("(9223372036854775807 + 1) * 0");
        overflow("0 * (9223372036854775807 + 1)");
        overflow("43 + (9223372036854775807 + 1)");
        overflow("(9223372036854775807 + 1) + 43");
        overflow("(9223372036854775807 * 2) - (9223372036854775807 * 2)");
        overflow("(-9223372036854775807 - 1) * -1");
        overflow("(-9223372036854775807 - 1) - 1");
    }

    @Test
    void checkedCallArgumentsCannotHideOverflowEvenWhenUnused() {
        String source = HEADER + """
                pub fnc take_first(int first, int second): int { return first; }
                pub fnc main(): int {
                  return take_first(43, 9223372036854775807 + 1);
                }
                """;
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(source));
        boolean classified = false;
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            classified |= String.valueOf(cursor.getMessage()).contains("ORES_I64_OVERFLOW");
        }
        assertTrue(classified, "Unused checked call argument must still report overflow");
    }

    @Test
    void checkedArithmeticFailsThroughNestedCalls() {
        String source = HEADER + """
                pub fnc explode(int value): int { return value * 2; }
                pub fnc forward(int value): int { return explode(value); }
                pub fnc main(): int { return forward(9223372036854775807); }
                """;
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(source));
        boolean classified = false;
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (String.valueOf(cursor.getMessage()).contains("ORES_I64_OVERFLOW")) {
                classified = true;
                break;
            }
        }
        assertTrue(classified, "Nested calls must propagate checked overflow");
    }

    @Test
    void unmarkedSourceStillWrapsRatherThanTraps() throws Exception {
        assertEquals(43, run("""
                pub fnc main(): int {
                  return (9223372036854775807 + 1) < 0 ? 43 : 1;
                }
                """));
    }
}
