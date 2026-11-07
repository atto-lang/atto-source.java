package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `define inverted class|struct` (docs/INVERTED_CLASS_DOP_V1.md) has no lowering yet.
 * It must fail closed with a precise diagnostic, never fall back to row-wise AoS
 * storage, and `inverted` must remain an ordinary identifier outside `define`.
 */
final class InvertedAggregateAdmissionTest {

    private static String rejection(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        return error.getMessage();
    }

    @Test
    void topLevelInvertedClassIsRejectedAsNotYetExecutable() {
        String message = rejection("""
                define inverted class Particle as
                  pub let float x = 0.0;
                end
                """);
        assertTrue(message.contains("'define inverted class' is specified"), message);
        assertTrue(message.contains("not yet executable"), message);
        assertTrue(message.contains("1:8"), "diagnostic points at the keyword: " + message);
    }

    @Test
    void invertedStructIsRejectedAsNotYetExecutable() {
        String message = rejection("""
                define inverted struct Particle as
                  pub let float x = 0.0;
                end
                """);
        assertTrue(message.contains("'define inverted struct' is specified"), message);
    }

    @Test
    void visibilityAndAbstractModifiersDoNotBypassTheGate() {
        assertTrue(rejection("""
                pub define inverted class Particle as
                  pub let float x = 0.0;
                end
                """).contains("'define inverted class'"));
        assertTrue(rejection("""
                define abstract inverted class Particle as
                  pub let float x = 0.0;
                end
                """).contains("inverted"));
    }

    @Test
    void invertedInsideAModuleIsRejectedTheSameWay() {
        String message = rejection("""
                define module physics as
                  pub define inverted class Particle as
                    pub let float x = 0.0;
                  end
                end
                """);
        assertTrue(message.contains("'define inverted class' is specified"), message);
    }

    @Test
    void danglingInvertedModifierNamesTheExpectedFollowers() {
        String message = rejection("""
                define inverted actor Particle as
                end
                """);
        assertTrue(message.contains("'inverted' must be followed by 'class' or 'struct'"), message);
    }

    @Test
    void invertedRemainsAContextualKeywordAndLegalIdentifier() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class inverted as
                end
                pub fnc flip(int inverted): int {
                  return inverted;
                }
                """)));
    }
}
