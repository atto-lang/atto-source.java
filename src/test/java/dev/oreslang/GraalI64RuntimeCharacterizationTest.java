package dev.oreslang;

import java.util.List;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the ACTUAL Graal/Truffle guest numeric evaluator.
 *
 * This is deliberately NOT a language specification or a Java/JS parity claim.
 * Java's current OresEvalRootNode integral arithmetic uses unchecked long
 * operators; JS Program IR uses checked signed-i64 operations. Do not silently
 * promote these expected wrapping values to a canonical Oreslang policy.
 *
 * Each fixture calls parameterized Oreslang functions so a constant-fold-only
 * oracle cannot substitute for executing numeric operators in the guest.
 * Results use Graal Value.asLong(), never a truncated OS process exit status.
 */
final class GraalI64RuntimeCharacterizationTest {
    private static final String MAX = "9223372036854775807";
    private static final String MIN = "(0-9223372036854775807-1)";

    private record Probe(String id, String expression, long expected) {}

    private static final List<Probe> OBSERVED_LONG_MODEL = List.of(
            new Probe("max", "identity(" + MAX + ")", Long.MAX_VALUE),
            new Probe("min", "identity(" + MIN + ")", Long.MIN_VALUE),
            new Probe("max-plus-one-wraps", "sum(" + MAX + ", 1)", Long.MIN_VALUE),
            new Probe("min-minus-one-wraps", "subtract(" + MIN + ", 1)", Long.MAX_VALUE),
            new Probe("max-times-two-wraps", "multiply(" + MAX + ", 2)", -2L),
            new Probe("negate-min-wraps", "negate(" + MIN + ")", Long.MIN_VALUE),
            new Probe("min-times-negative-one-wraps", "multiply(" + MIN + ", -1)", Long.MIN_VALUE),
            new Probe("overflow-then-cancel", "subtract(sum(" + MAX + ", 1), 1)", Long.MAX_VALUE),
            new Probe("negative-remainder", "remainder(-5, 3)", -2L),
            new Probe("min-remainder-negative-one", "remainder(" + MIN + ", -1)", 0L),
            new Probe("max-minus-one", "subtract(" + MAX + ", 1)", Long.MAX_VALUE - 1),
            new Probe("min-plus-one", "sum(" + MIN + ", 1)", Long.MIN_VALUE + 1)
    );

    private static String source(String expression) {
        return """
                fnc identity(int value): int { return value; }
                fnc sum(int a, int b): int { return a + b; }
                fnc subtract(int a, int b): int { return a - b; }
                fnc multiply(int a, int b): int { return a * b; }
                fnc negate(int a): int { return -a; }
                fnc remainder(int a, int b): int { return Integer.rem(a, b); }
                pub fnc main(): int { return %s; }
                """.formatted(expression);
    }

    @TestFactory
    Stream<DynamicTest> executesTypedGuestI64Observations() {
        return OBSERVED_LONG_MODEL.stream().map(probe -> DynamicTest.dynamicTest(probe.id(), () -> {
            try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                Value value = context.eval("ores", source(probe.expression()));
                assertTrue(value.fitsInLong(), probe.id() + " must produce a typed i64 value");
                assertEquals(probe.expected(), value.asLong(), probe.id());
            }
        }));
    }

    @Test
    void zeroDivisorThrowsFromActualGuestEvaluation() {
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            assertThrows(PolyglotException.class, () ->
                    context.eval("ores", source("remainder(1, 0)")));
        }
    }
}
