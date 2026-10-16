package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checked fixed-width signed-i64 semantics used by the .NET and Forge
 * reference implementations. LLVM currently wraps and remains non-conformant.
 * All tests use actual Oreslang guest source, not a host-only arithmetic stub.
 */
final class NumericCheckedI64ParityTest {
    private static long evaluate(String expression) {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(OresLanguage.ID,
                    "pub routine main(): int { return " + expression + "; }")
                    .asLong();
        }
    }

    @Test
    void signedMinMaxAndOrdinaryOperationsRemainExact() {
        assertEquals(Long.MIN_VALUE, evaluate("-9223372036854775808"));
        assertEquals(Long.MAX_VALUE, evaluate("9223372036854775807"));
        assertEquals(Long.MAX_VALUE - 1, evaluate("9223372036854775807-1"));
        assertEquals(Long.MIN_VALUE + 1, evaluate("-9223372036854775808+1"));
        assertEquals(-5, evaluate("3*4-2*5-7"));
        assertEquals(0, evaluate("-9223372036854775808%2"));
    }

    @Test
    void overflowAndDivisionByZeroRejectWithoutModuloWrapping() {
        for (String source : new String[] {
                "9223372036854775807+1",
                "-9223372036854775808-1",
                "9223372036854775807*2",
                "-(-9223372036854775808)",
                "-9223372036854775808%-1",
                "1%0"
        }) {
            assertThrows(PolyglotException.class, () -> evaluate(source),
                    "signed-i64 overflow must be checked, not wrapped: " + source);
        }
    }
}
