package dev.oreslang;

import dev.oreslang.runtime.CheckedI64DivisionByZeroException;
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
 * Source-level execution probes for the transitional checked-i64 profile.
 * This does not prove cross-backend parity, guest Error ABI, or a checked
 * default for unmarked Oreslang programs.
 */
final class ExperimentalCheckedI64RemainderParityTest {
    private static final String HEADER = "// oreslang:checked-i64-v1\n";
    @TempDir Path directory;

    private int run(String program) throws Exception {
        Path input = Files.createTempFile(directory, "checked-rem-", ".ores");
        Files.writeString(input, program);
        return LinkedProgramRunner.runWithExitStatus(
                input, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()).exitStatus();
    }

    private int expression(String expr) throws Exception {
        return run(HEADER + "pub fnc main(): int { return " + expr + "; }\n");
    }

    private static void requireClassifiedZero(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CheckedI64DivisionByZeroException
                    || String.valueOf(cause.getMessage())
                        .contains(CheckedI64DivisionByZeroException.ERROR_CODE)) return;
        }
        fail("Guest remainder-by-zero must be classified without host JVM wording", failure);
    }

    @Test
    void signedRemaindersUseTruncationTowardZero() throws Exception {
        assertEquals(43, expression("((-7 % 3) == -1) ? 43 : 1"));
        assertEquals(43, expression("((7 % -3) == 1) ? 43 : 1"));
        assertEquals(43, expression("((-7 % -3) == -1) ? 43 : 1"));
        assertEquals(43, expression("((7 % 3) == 1) ? 43 : 1"));
        assertEquals(43, expression("((0 % -1) == 0) ? 43 : 1"));
    }

    @Test
    void minimumRemainderMinusOneIsZeroNotOverflow() throws Exception {
        assertEquals(43, expression(
            "(((-9223372036854775807 - 1) % -1) == 0) ? 43 : 1"));
    }

    @Test
    void directZeroDivisorProducesClassifiedCheckedFailure() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> expression("43 % 0"));
        requireClassifiedZero(failure);
    }

    @Test
    void dynamicZeroDivisorRemainsClassifiedAcrossFunctionCalls() {
        String code = HEADER + """
            pub fnc remainder(int a, int b): int { return a % b; }
            pub fnc delegate(int x): int { return remainder(x, 0); }
            pub fnc main(): int { return delegate(43); }
            """;
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(code));
        requireClassifiedZero(failure);
    }

    @Test
    void nonEvaluatedZeroDivisorDoesNotFail() throws Exception {
        assertEquals(43, expression("1 < 0 ? (1 % 0) : 43"));
        assertEquals(43, expression("1 == 1 ? 43 : (1 % 0)"));
    }

    @Test
    void operandOverflowPrecedesRemainderEvaluation() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> expression("(9223372036854775807 + 1) % 0"));
        boolean found = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            found |= String.valueOf(cause.getMessage()).contains("ORES_I64_OVERFLOW");
        }
        assertTrue(found, "left-to-right operand overflow must win over later zero divisor");
    }
}
