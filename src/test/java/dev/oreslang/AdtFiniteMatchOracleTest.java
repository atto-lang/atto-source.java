package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive finite-domain oracle: the independent model is a four-element
 * cartesian product, not the compiler's witness enumeration.
 */
final class AdtFiniteMatchOracleTest {
    private static final boolean[][] INPUTS = {
            {false, false},
            {false, true},
            {true, false},
            {true, true}
    };

    private static String program(int mask, boolean reversed, boolean fallback,
                                  boolean explicitDo) {
        StringBuilder source = new StringBuilder("""
                fnc classify(Tuple[bool, bool] pair): int {
                """);
        source.append(explicitDo ? "do match pair over\n" : "match pair over\n");
        for (int step = 0; step < INPUTS.length; step++) {
            int index = reversed ? INPUTS.length - 1 - step : step;
            if ((mask & (1 << index)) == 0) continue;
            source.append("on (")
                    .append(INPUTS[index][0])
                    .append(", ")
                    .append(INPUTS[index][1])
                    .append(") -> { return ")
                    .append(index)
                    .append("; }\n");
        }
        if (fallback) source.append("on _ -> { return 9; }\n");
        source.append("end\n}\n");
        return source.toString();
    }

    @Test
    void tupleBooleanPowerSetAgreesWithFiniteExhaustivenessOracle() {
        // For all 2^4 subsets of the (bool,bool) domain, a match is
        // exhaustive iff every inhabitant is covered, or a fallback exists.
        // Reordering the arms and toggling 'do' must not alter the proof.
        for (int mask = 0; mask < 16; mask++) {
            for (boolean fallback : new boolean[]{false, true}) {
                for (boolean reversed : new boolean[]{false, true}) {
                    for (boolean explicitDo : new boolean[]{false, true}) {
                        final int currentMask = mask;
                        String source = program(mask, reversed, fallback, explicitDo);
                        boolean admits = fallback || mask == 15;
                        if (admits) {
                            assertDoesNotThrow(
                                    () -> TypeChecker.check(Parser.parse(source)),
                                    "oracle must accept mask=" + currentMask
                                            + " fallback=" + fallback
                                            + " reversed=" + reversed
                                            + " explicitDo=" + explicitDo);
                        } else {
                            // An empty match has an independent parser
                            // rejection; all others need an exhaustiveness
                            // diagnostic from semantic analysis.
                            IllegalArgumentException rejection = assertThrows(
                                    IllegalArgumentException.class,
                                    () -> TypeChecker.check(Parser.parse(source)),
                                    "oracle must reject mask=" + currentMask);
                            if (mask != 0) {
                                assertTrue(
                                        rejection.getMessage().contains("non-exhaustive match"),
                                        rejection.toString());
                            }
                        }
                    }
                }
            }
        }
    }
}
