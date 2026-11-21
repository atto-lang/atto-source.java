package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A lexical Copy made before Take remains an independent scalar value.
 * Reading/copying the original source after Take must remain rejected:
 * candidate NLL work may shorten a loan, but must not resurrect moved data.
 * Production parser/checker tests; not compiler guest or source-runner proof.
 */
final class OwnershipMoveUseOrderingTest {
    private static void check(String source, boolean admitted) {
        var ast = assertDoesNotThrow(() -> Parser.parse(source),
                "parser-only failure is not ownership checking");
        if (admitted) {
            assertDoesNotThrow(() -> OwnershipChecker.check(ast));
            return;
        }
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class, () -> OwnershipChecker.check(ast));
        assertTrue(denied.getMessage().contains("moved") ||
                        denied.getMessage().contains("ownership") ||
                        denied.getMessage().contains("rt copy"),
                "expected semantic use-after-move rejection: " + denied.getMessage());
    }

    @TestFactory
    Stream<DynamicTest> copyBeforeMovePreservesDetachedScalar() {
        return IntStream.range(0, 5).mapToObj(i ->
                DynamicTest.dynamicTest("copy-before-take-" + i, () -> check("""
                        pub fnc main(): int {
                          val int owner = %d;
                          val int snapshot = rt copy owner;
                          val int moved = rt take owner;
                          return moved + snapshot;
                        }
                        """.formatted(38 + i), true)));
    }

    @TestFactory
    Stream<DynamicTest> copyAfterMoveCannotResurrectSource() {
        return IntStream.range(0, 5).mapToObj(i ->
                DynamicTest.dynamicTest("copy-after-take-" + i, () -> check("""
                        pub fnc main(): int {
                          val int owner = %d;
                          val int moved = rt take owner;
                          val int invalid = rt copy owner;
                          return moved + invalid;
                        }
                        """.formatted(38 + i), false)));
    }
}
