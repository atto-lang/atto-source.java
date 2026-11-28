package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative parser guard for atto-spec #192 and atto-source.rs #467.
 * Function expressions are always explicitly pipe-delimited in Atto v1/v2.
 */
final class AttoExplicitPipeLambdaHeadParityTest {
    private static String assignment(String head) {
        return "pub fnc main(): int { val callable = " + head + "; return 0; }";
    }

    @Test
    void explicitPipesRemainAdmittedInBothCanonicalEditions() {
        for (int edition : new int[] {1, 2}) {
            assertDoesNotThrow(() -> Parser.parseVersionedAtto(
                    assignment("|int x| -> x + 1"), edition));
            assertDoesNotThrow(() -> Parser.parseVersionedAtto(
                    assignment("|x: int| -> x + 1"), edition));
            assertDoesNotThrow(() -> Parser.parseVersionedAtto(
                    assignment("|| -> 5"), edition));
        }
    }

    @Test
    void barePlaceholderAndBareNamedHeadsNeverBecomeLambdaExpressions() {
        for (int edition : new int[] {1, 2}) {
            for (String head : new String[] {
                    "_ : int -> 5",
                    "_ -> 5",
                    "x -> x + 1",
                    "x: int -> x + 1",
                    "fnc(int x) -> x + 1",
                    "(int x) -> x + 1"
            }) {
                assertThrows(IllegalArgumentException.class,
                        () -> Parser.parseVersionedAtto(assignment(head), edition),
                        "un-delimited lambda head must fail: " + head);
            }
        }
    }

    @Test
    void bareHeadsInCallArgumentsCannotEvadeTheExpressionGate() {
        for (int edition : new int[] {1, 2}) {
            for (String argument : new String[] {
                    "_ : int -> 5",
                    "_ -> 5",
                    "item -> item + 1"
            }) {
                String source = "pub fnc main(): int { val result = call(" + argument
                        + "); return 0; }";
                assertThrows(IllegalArgumentException.class,
                        () -> Parser.parseVersionedAtto(source, edition),
                        "call arguments must not admit bare lambda syntax");
            }
        }
    }
}
