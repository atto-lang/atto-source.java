package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 25 parse-first production OwnershipChecker regressions for compiler-local
 * source-place roots. This is not an alias-complete MIR proof or guest oracle.
 */
final class OwnershipResolvedPlaceOriginTest {
    private static final String BOX = """
            define class Box as
              pub let int f0 = 40;
              pub let int f1 = 41;
              pub let int f2 = 42;
              pub let int f3 = 43;
              pub let int f4 = 44;
            end
            """;

    private static String field(int n) {
        assertTrue(n >= 0 && n <= 4);
        return "f" + n;
    }

    private static void semanticNegative(String source, String diagnostic) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                "parser-only rejection is not an ownership result");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OwnershipChecker.check(parsed),
                "ownership checker must run and reject");
        assertTrue(failure.getMessage().contains(diagnostic),
                "expected " + diagnostic + " but observed " + failure.getMessage());
    }

    private static void semanticPositive(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                "the case must reach the production ownership checker");
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed));
    }

    @TestFactory
    Stream<DynamicTest> namedOwnedClassFieldsCanShareDetachedScalarValues() {
        return IntStream.range(0, 5).mapToObj(n -> DynamicTest.dynamicTest(
                "owned-root-field-" + n, () -> semanticPositive(BOX + """
                        fnc allowed(): int {
                          let Box box = new Box();
                          return rt share box.%s;
                        }
                        """.formatted(field(n)))));
    }

    @TestFactory
    Stream<DynamicTest> borrowedClassFieldCannotUpgradeItsOwner() {
        return IntStream.range(0, 5).mapToObj(n -> DynamicTest.dynamicTest(
                "borrowed-root-field-" + n, () -> semanticNegative(BOX + """
                        fnc forbidden(borrow Box box): int {
                          return rt share box.%s;
                        }
                        """.formatted(field(n)), "E_SHARE_BORROWED_PROJECTION")));
    }

    @TestFactory
    Stream<DynamicTest> undeclaredNamesCannotForgeProjectedOwnership() {
        return IntStream.range(0, 5).mapToObj(n -> DynamicTest.dynamicTest(
                "untracked-root-field-" + n, () -> semanticNegative(BOX + """
                        fnc forbidden(): int {
                          return rt share foreign.%s;
                        }
                        """.formatted(field(n)), "E_SHARE_UNTRACKED_PROJECTION")));
    }

    @TestFactory
    Stream<DynamicTest> namedScalarTakeHasACompilerTrackedSource() {
        return IntStream.range(0, 5).mapToObj(n -> DynamicTest.dynamicTest(
                "tracked-take-" + n, () -> semanticPositive("""
                        fnc allowed(): int {
                          val int owner = %d;
                          val int moved = rt take owner;
                          return moved;
                        }
                        """.formatted(n + 40))));
    }

    @TestFactory
    Stream<DynamicTest> borrowedScalarTakeCannotReclaimCallerStorage() {
        return IntStream.range(0, 5).mapToObj(n -> DynamicTest.dynamicTest(
                "borrowed-take-" + n, () -> semanticNegative("""
                        fnc forbidden(borrow int incoming): int {
                          val int detached = %d;
                          val int stolen = rt take incoming;
                          return stolen + detached;
                        }
                        """.formatted(n + 1), "rt take requires owned data")));
    }
}
