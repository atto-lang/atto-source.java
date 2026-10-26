package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for aggregate values crossing return, destructure and call boundaries. */
final class AggregateCallBoundaryHardeningTest {
    private static void accepts(String source) {
        var typed = TypeChecker.check(Parser.parse(source));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void tupleReturnedByFunctionCanBeSpreadIntoFixedArityCall() {
        accepts("""
                fnc pair(): (int, int) { return (19, 23); }
                fnc add(int left, int right): int { return left + right; }
                fnc use(): int { return add(...pair()); }
                """);
    }

    @Test
    void tupleResultCanBeDestructuredAndForwarded() {
        accepts("""
                fnc pair(): [int, string] { return [7, "ok"]; }
                fnc join(int number, string label): string { return label; }
                fnc use(): string {
                  const [number, label] = pair();
                  return join(number, label);
                }
                """);
    }

    @Test
    void recordResultCanBeDestructuredAndForwardedByExplicitFields() {
        accepts("""
                fnc pair(): {left: int, right: int} {
                  return record{left: 19, right: 23};
                }
                fnc add(int left, int right): int { return left + right; }
                fnc use(): int {
                  const {left, right} = pair();
                  return add(left, right);
                }
                """);
    }

    @Test
    void tupleReturnSpreadStillEnforcesArity() {
        rejects("""
                fnc pair(): (int, int) { return (19, 23); }
                fnc unary(int only): int { return only; }
                fnc bad(): int { return unary(...pair()); }
                """);
    }

    @Test
    void tupleReturnSpreadStillEnforcesTypes() {
        rejects("""
                fnc pair(): (int, string) { return (19, "wrong"); }
                fnc add(int left, int right): int { return left + right; }
                fnc bad(): int { return add(...pair()); }
                """);
    }

    @Test
    void destructuringReturnedRecordCannotInventAField() {
        rejects("""
                fnc pair(): {left: int, right: int} {
                  return record{left: 19, right: 23};
                }
                fnc bad(): int {
                  const {left, missing} = pair();
                  return left;
                }
                """);
    }

    @Test
    void runtimeLengthListCannotMasqueradeAsTupleAtCallBoundary() {
        rejects("""
                fnc numbers(): List<int> { return [19, 23]; }
                fnc add(int left, int right): int { return left + right; }
                fnc bad(): int { return add(...numbers()); }
                """);
    }
}
