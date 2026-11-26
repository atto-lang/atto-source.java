package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.StrictIntegerArithmetic;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import java.math.BigInteger;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class CheckedQuotientProfileForwardPortTest {
    private static final String CHECKED = """
            fnc divide(int a, int b): int { return a / b; }
            pub fnc main(): int { return 44 + divide(-7, 3); }
            """;
    private static final String LEGACY = """
            pub fnc main(): float { return 7 / 2; }
            """;

    private static double run(String source, String name) throws Exception {
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID, source, name)
                    .mimeType(OresLanguage.MIME_TYPE).build()).asDouble();
        }
    }

    @Test void checkedAttoAndLegacyOresKeepSeparateQuotientTypes() throws Exception {
        assertEquals(42.0, run(CHECKED, "quotient.atto"));
        assertEquals(3.5, run(LEGACY, "quotient.ores"));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(CHECKED));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(CHECKED));
    }

    @Test void checkedDirectiveSelectsSameSemanticsWithoutAttoExtension() throws Exception {
        String source = "// oreslang:checked-i64-v1\n" + CHECKED;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(42.0, run(source, "quotient.ores"));
        assertEquals(3.5, run("pub fnc main(): float { return 7.0 / 2.0; }", "floating.atto"));
    }

    @Test void linkedAdmissionSelectsProfileByUnitWithoutCacheCrossContamination() {
        var compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(Map.of("quotient.atto", CHECKED)));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of("quotient.ores", CHECKED)));
        assertDoesNotThrow(() -> compiler.compile(Map.of("quotient.ores", LEGACY)));
        assertDoesNotThrow(() -> compiler.compile(Map.of("quotient.atto", CHECKED)));
    }

    @Test void signedBoundaryAndZeroAreDistinctFailures() throws Exception {
        assertEquals(-2L, StrictIntegerArithmetic.div(-7, 3));
        assertEquals(-2L, StrictIntegerArithmetic.div(7, -3));
        assertEquals(2L, StrictIntegerArithmetic.div(-7, -3));
        assertEquals(Long.MIN_VALUE, StrictIntegerArithmetic.div(Long.MIN_VALUE, 1));
        assertEquals(0L, StrictIntegerArithmetic.div(1, Long.MIN_VALUE));
        assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(7, 0))
                .getMessage().contains("ORES_I64_DIVISION_BY_ZERO"));
        assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(Long.MIN_VALUE, -1))
                .getMessage().contains("ORES_I64_OVERFLOW"));
        // Deterministic independent mathematical oracle, not a second
        // implementation of Java long division. Include nonrepresentable
        // quotients and values beyond the binary64 exact integer range.
        long[] edges = { Long.MIN_VALUE, Long.MIN_VALUE + 1, -9007199254740993L,
                -7, -3, -2, -1, 0, 1, 2, 3, 7, 9007199254740993L,
                Long.MAX_VALUE - 1, Long.MAX_VALUE };
        for (long a : edges) for (long b : edges) checkAgainstOracle(a, b);
        Random random = new Random(518231);
        for (int i = 0; i < 100000; i++) checkAgainstOracle(random.nextLong(), random.nextLong());
        for (String[] failure : new String[][] {
                {"7", "0", "ORES_I64_DIVISION_BY_ZERO"},
                {"-9223372036854775808", "-1", "ORES_I64_OVERFLOW"}
        }) {
            String guest = "fnc divide(int a, int b): int { return a / b; } "
                    + "pub fnc main(): int { return divide(" + failure[0] + ", " + failure[1] + "); }";
            var error = assertThrows(PolyglotException.class, () -> run(guest, "fault.atto"));
            assertTrue(error.getMessage().contains(failure[2]), error::getMessage);
        }
    }

    private static void checkAgainstOracle(long a, long b) {
        if (b == 0) {
            assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(a, b))
                    .getMessage().contains("ORES_I64_DIVISION_BY_ZERO"));
            return;
        }
        BigInteger quotient = BigInteger.valueOf(a).divide(BigInteger.valueOf(b));
        if (quotient.bitLength() > 63) {
            assertTrue(assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.div(a, b))
                    .getMessage().contains("ORES_I64_OVERFLOW"));
        } else {
            assertEquals(quotient.longValueExact(), StrictIntegerArithmetic.div(a, b));
        }
    }
}
