package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.CheckedI64DivisionByZeroException;
import dev.oreslang.runtime.CheckedI64OverflowException;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Link-and-execute shared mathematical checked-i64-v1 guest source fixtures.
 * Contract fixture Git blob (in the same private organization):
 * 44224caf6f6c76dedff429a47d51edc926830fdc
 *
 * All 38 source-mapped fixtures are explicitly enumerated; the five
 * abstract div_trunc vectors are NOT valid Oreslang source operations.
 * Results are NOT cross-backend parity attestation. Exit status is only a
 * 43/1 in-guest equality witness, never a lossy i64 transport.
 */
final class ExperimentalCheckedI64SharedVectorTest {
    private static final String HEADER = "// oreslang:checked-i64-v1\n";

    private record Vector(String id, String expression,
            String kind, String expected, String phase) {}

    private static final List<Vector> CASES = List.of(
        new Vector("literal-zero", "0", "value", "0", "none"),
        new Vector("literal-max", "9223372036854775807", "value", "9223372036854775807", "none"),
        new Vector("literal-min", "-9223372036854775808", "value", "-9223372036854775808", "none"),
        new Vector("literal-over-max", "9223372036854775808", "error", "integer_overflow", "compile"),
        new Vector("literal-under-min", "-9223372036854775809", "error", "integer_overflow", "compile"),
        new Vector("add-basic", "(40 + 2)", "value", "42", "none"),
        new Vector("add-max-zero", "(9223372036854775807 + 0)", "value", "9223372036854775807", "none"),
        new Vector("add-max-one", "(9223372036854775807 + 1)", "error", "integer_overflow", "runtime"),
        new Vector("add-min-neg-one", "(-9223372036854775808 + -1)", "error", "integer_overflow", "runtime"),
        new Vector("add-min-one", "(-9223372036854775808 + 1)", "value", "-9223372036854775807", "none"),
        new Vector("add-cancel", "(1 + -1)", "value", "0", "none"),
        new Vector("sub-basic", "(50 - 8)", "value", "42", "none"),
        new Vector("sub-min-zero", "(-9223372036854775808 - 0)", "value", "-9223372036854775808", "none"),
        new Vector("sub-min-one", "(-9223372036854775808 - 1)", "error", "integer_overflow", "runtime"),
        new Vector("sub-max-negative-one", "(9223372036854775807 - -1)", "error", "integer_overflow", "runtime"),
        new Vector("sub-zero-negative-one", "(0 - -1)", "value", "1", "none"),
        new Vector("mul-basic", "(6 * 7)", "value", "42", "none"),
        new Vector("mul-max-one", "(9223372036854775807 * 1)", "value", "9223372036854775807", "none"),
        new Vector("mul-max-two", "(9223372036854775807 * 2)", "error", "integer_overflow", "runtime"),
        new Vector("mul-min-negative-one", "(-9223372036854775808 * -1)", "error", "integer_overflow", "runtime"),
        new Vector("mul-min-zero", "(-9223372036854775808 * 0)", "value", "0", "none"),
        new Vector("mul-neg-seven-three", "(-7 * 3)", "value", "-21", "none"),
        new Vector("mul-root-square-valid", "(3037000499 * 3037000499)", "value", "9223372030926249001", "none"),
        new Vector("mul-root-square-overflow", "(3037000500 * 3037000500)", "error", "integer_overflow", "runtime"),
        new Vector("neg-zero", "(-0)", "value", "0", "none"),
        new Vector("neg-max", "(-9223372036854775807)", "value", "-9223372036854775807", "none"),
        new Vector("neg-min", "(--9223372036854775808)", "error", "integer_overflow", "runtime"),
        new Vector("rem-positive", "(7 % 3)", "value", "1", "none"),
        new Vector("rem-negative", "(-7 % 3)", "value", "-1", "none"),
        new Vector("rem-negative-divisor", "(7 % -3)", "value", "1", "none"),
        new Vector("rem-both-negative", "(-7 % -3)", "value", "-1", "none"),
        new Vector("rem-min-minus-one", "(-9223372036854775808 % -1)", "value", "0", "none"),
        new Vector("rem-zero-divisor", "(7 % 0)", "error", "division_by_zero", "runtime"),
        new Vector("rem-zero-zero", "(0 % 0)", "error", "division_by_zero", "runtime"),
        new Vector("intermediate-add-overflow", "((9223372036854775807 + 1) - 1)", "error", "integer_overflow", "runtime"),
        new Vector("intermediate-sub-overflow", "((-9223372036854775808 - 1) + 1)", "error", "integer_overflow", "runtime"),
        new Vector("intermediate-mul-overflow", "((9223372036854775807 * 2) - 9223372036854775807)", "error", "integer_overflow", "runtime"),
        new Vector("precedence-tree", "(1 + (2 * 3))", "value", "7", "none")
    );
    @TempDir Path directory;

    private int execute(String source) throws Exception {
        Path input = Files.createTempFile(directory, "checked-shared-", ".ores");
        Files.writeString(input, source);
        return LinkedProgramRunner.runWithExitStatus(input,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()).exitStatus();
    }

    private static String program(String expression) {
        return HEADER + "pub fnc main(): int { return " + expression + "; }\n";
    }

    private static boolean hasMarker(Throwable error, String marker) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains(marker)) return true;
        }
        return false;
    }

    @Test
    void manifestIsCompleteAndPhasesAreExplicit() {
        assertEquals(38, CASES.size(), "only mapped Oreslang source vectors");
        assertEquals(23, CASES.stream().filter(c -> c.kind().equals("value")).count());
        assertEquals(15, CASES.stream().filter(c -> c.kind().equals("error")).count());
        assertEquals(2, CASES.stream().filter(c -> c.phase().equals("compile")).count());
        assertEquals(13, CASES.stream().filter(c -> c.phase().equals("runtime")).count());
        assertEquals(38, CASES.stream().map(Vector::id).distinct().count());
    }

    @Test
    void realGuestReturnsExactExpectedSigned64Results() throws Exception {
        for (Vector row : CASES) {
            if (!row.kind().equals("value")) continue;
            // A process exit code cannot carry the full signed-64-bit result.
            // Make the *guest* compare exact i64 values and exit 43 or 1.
            String source = program("((" + row.expression()
                    + ") == (" + row.expected() + ")) ? 43 : 1");
            assertEquals(43, execute(source), row.id() + ": exact guest i64 value");
        }
    }

    @Test
    void literalRangeFailuresHappenDuringCompilation() {
        for (Vector row : CASES) {
            if (!row.phase().equals("compile")) continue;
            assertEquals("integer_overflow", row.expected(), row.id());
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> OresCompiler.parseAndTypeCheck(program(row.expression())), row.id());
            assertTrue(hasMarker(error, "integer literal overflows signed 64-bit"),
                    row.id() + ": must be an integer literal diagnostic, not runtime overflow");
        }
    }

    @Test
    void checkedArithmeticAndZeroDivisorHaveDistinctRuntimeCategories() {
        for (Vector row : CASES) {
            if (!row.phase().equals("runtime")) continue;
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> execute(program(row.expression())), row.id());
            final boolean overflow = hasMarker(error, CheckedI64OverflowException.ERROR_CODE);
            final boolean zero = hasMarker(error, CheckedI64DivisionByZeroException.ERROR_CODE);
            assertNotEquals(overflow, zero,
                    row.id() + ": exactly one checked arithmetic category required");
            assertEquals(row.expected().equals("integer_overflow"), overflow, row.id());
            assertEquals(row.expected().equals("division_by_zero"), zero, row.id());
        }
    }
}
