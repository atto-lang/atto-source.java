package dev.oreslang;

import dev.oreslang.compiler.ExperimentalHirV1Exporter;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only, source-unit opt-in checked signed-64-bit arithmetic.
 * No actor, AOT native-image or arbitrary cross-backend error ABI is claimed.
 */
final class ExperimentalHirV1CheckedArithmeticRuntimeTest {
    @TempDir Path directory;
    private static final String HEADER = "// oreslang:checked-i64-v1\n";

    private int exit(String source) throws Exception {
        Path path = directory.resolve("checked-" + Integer.toHexString(source.hashCode()) + ".ores");
        Files.writeString(path, source);
        return LinkedProgramRunner.runWithExitStatus(path, IsolatePolicy.developer(),
            ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
            new ByteArrayOutputStream()).exitStatus();
    }

    private static String scale(int value) {
        return """
            pub fnc scale(int count): int { return count * 43; }
            pub fnc main(): int { return scale(%d); }
            """.formatted(value);
    }

    @Test
    void checkedModeAcceptsDynamicMultiplicationAndProduces43() throws Exception {
        String source = HEADER + scale(1);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        String hir = ExperimentalHirV1Exporter.fromSource(source);
        assertTrue(hir.contains("\"checked-i64.v1\""));
        assertTrue(hir.contains("\"op\":\"mul\""));
        assertEquals(43, exit(source));
    }

    @Test
    void defaultWrappingRuntimeIsNotRedefined() throws Exception {
        String legacy = """
            pub fnc main(): int {
              return 9223372036854775807 + 1 < 0 ? 43 : 1;
            }
            """;
        assertEquals(43, exit(legacy));
        assertEquals(43, exit(HEADER + """
            pub fnc main(): int { return 40 + 3; }
            """));
    }

    @Test
    void overflowFailsInsteadOfReturningWrappedValue() {
        String[] bad = {
            HEADER + """
                pub fnc main(): int { return 9223372036854775807 + 1; }
                """,
            HEADER + """
                pub fnc main(): int { return 9223372036854775807 * 2; }
                """,
            HEADER + """
                pub fnc main(): int { return (-9223372036854775807 - 1) - 1; }
                """,
            HEADER + """
                pub fnc main(): int { return -(-9223372036854775807 - 1); }
                """
        };
        for (String source : bad) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> exit(source), source);
            boolean found = false;
            for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
                if (String.valueOf(cursor.getMessage()).contains("ORES_I64_OVERFLOW")) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, () -> "Guest must expose checked overflow diagnostic: " + failure);
        }
    }


    @Test
    void checkedArithmeticCoversSubtractionAndCrossFunctionPropagation() throws Exception {
        assertEquals(43, exit(HEADER + """
            pub fnc subtract(int left, int right): int { return left - right; }
            pub fnc main(): int { return subtract(44, 1); }
            """));
        assertEquals(43, exit(HEADER + """
            pub fnc multiply(int left, int right): int { return left * right; }
            pub fnc main(): int { return multiply(43, 1); }
            """));
        String[] bad = {
            HEADER + """
                pub fnc overflow(int value): int { return value * 2; }
                pub fnc main(): int { return overflow(9223372036854775807); }
                """,
            HEADER + """
                pub fnc overflow(int value): int { return value + 1; }
                pub fnc main(): int { return overflow(9223372036854775807); }
                """
        };
        for (String source : bad) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> exit(source), source);
            boolean expected = false;
            for (Throwable error = failure; error != null; error = error.getCause()) {
                if (String.valueOf(error.getMessage()).contains("ORES_I64_OVERFLOW")) {
                    expected = true;
                    break;
                }
            }
            assertTrue(expected, "checked operations across calls must trap instead of wrap");
        }
    }

    @Test
    void unmarkedPotentialOverflowStillFailsHIRAdmission() {
        String legacy = scale(1);
        assertThrows(IllegalArgumentException.class,
            () -> ExperimentalHirV1Exporter.fromSource(legacy));
        assertDoesNotThrow(() -> ExperimentalHirV1Exporter.fromSource(HEADER + legacy));
    }

    @Test
    void overflowUsesTypedSignalAndGuestBoundaryPreservesCode() {
        var typed = dev.oreslang.runtime.CheckedI64OverflowException.forOperator('+');
        assertEquals("ORES_I64_OVERFLOW", typed.errorCode());
        assertEquals("ArithmeticOverflowError", typed.guestErrorName());
        assertEquals("add", typed.operation());
        assertNull(typed.getCause(), "Host ArithmeticException must not escape the checked signal");

        String source = HEADER + "pub fnc main(): int { return 9223372036854775807 + 1; }";
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> exit(source));
        boolean classified = false;
        for (Throwable cursor = thrown; cursor != null; cursor = cursor.getCause()) {
            classified |= String.valueOf(cursor.getMessage()).contains("ORES_I64_OVERFLOW");
            assertFalse(cursor instanceof ArithmeticException,
                    "Host arithmetic throwable cannot cross the guest boundary");
        }
        // LinkedProgramRunner currently preserves the code, not a stable
        // typed guest Error object. A typed guest ABI needs its own end-to-end
        // conformance suite before declaring exception-class parity.
        assertTrue(classified, "Guest must preserve ORES_I64_OVERFLOW");
    }

}
