package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Default Oreslang signed-int arithmetic must never silently wrap. */
final class DefaultCheckedI64ArithmeticTest {
    private static PolyglotException overflow(String expression) {
        String guest = """
                pub routine main(): void {
                  val int value = %s;
                  stdio.stdout.write(value);
                  return;
                }
                """.formatted(expression);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return assertThrows(PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, guest), expression);
        }
    }

    @Test
    void defaultAddSubtractMultiplyAndNegateTrapOverflow() {
        for (String expression : new String[] {
                "9223372036854775807 + 1",
                "-9223372036854775808 - 1",
                "3037000500 * 3037000500",
                "-(-9223372036854775808)"
        }) {
            PolyglotException failure = overflow(expression);
            assertTrue(failure.getMessage() != null
                            && failure.getMessage().contains("ArithmeticOverflowError"),
                    () -> "missing guest ArithmeticOverflowError identity for " + expression
                            + ": " + failure.getMessage());
        }
    }

    @Test
    void mathAbsAndNegCannotWrapSignedMinimum() {
        for (String expression : new String[] {
                "Math.neg(-9223372036854775808)",
                "Math.abs(-9223372036854775808)"
        }) {
            String guest = """
                    import module Math from "std/math";
                    pub routine main(): void {
                      val int value = %s;
                      stdio.stdout.write(value);
                      return;
                    }
                    """.formatted(expression);
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                PolyglotException failure = assertThrows(PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, guest), expression);
                assertTrue(failure.getMessage() != null
                                && failure.getMessage().contains("ArithmeticOverflowError"),
                        () -> "Math overflow did not expose guest ArithmeticOverflowError: " + failure.getMessage());
            }
        }
    }
}
