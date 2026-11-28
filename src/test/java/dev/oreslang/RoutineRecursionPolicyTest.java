package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Callable recursion is legal for fncs, not for direct-call-only routines. */
final class RoutineRecursionPolicyTest {

    private static void rejectsRoutineCycle(String source) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("cannot be recursive"),
                () -> "expected routine recursion diagnostic, got: " + error.getMessage());
    }

    @Test
    void directRoutineSelfCallIsRejectedEvenInTailPosition() {
        rejectsRoutineCycle("""
                routine loop(int n): int {
                  return n == 0 ? 0 : loop(n - 1);
                }
                """);
    }

    @Test
    void routineMutualRecursionIsRejected() {
        rejectsRoutineCycle("""
                routine left(int n): int {
                  return right(n);
                }
                routine right(int n): int {
                  return left(n);
                }
                """);
    }

    @Test
    void routineReentryThroughOrdinaryFncsIsRejected() {
        rejectsRoutineCycle("""
                routine start(int n): int {
                  return stage1(n);
                }
                fnc stage1(int n): int {
                  return stage2(n);
                }
                fnc stage2(int n): int {
                  return start(n);
                }
                """);
    }

    @Test
    void ordinaryFncsMayRecurseMutually() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc even(int n): bool {
                  return n == 0 ? true : odd(n - 1);
                }
                fnc odd(int n): bool {
                  return n == 0 ? false : even(n - 1);
                }
                """)));
    }

    @Test
    void routineCanTailCallAnOrdinaryRecursiveFnc() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc count_down(int n): int {
                  return n == 0 ? 0 : count_down(n - 1);
                }
                routine run(int n): int {
                  return count_down(n);
                }
                """)));
    }

    @Test
    void overloadArityDistinguishesRoutineFromAnUnrelatedFnc() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc entry(): int {
                  return 1;
                }
                routine entry(int n): int {
                  return entry();
                }
                """)));
    }
}
