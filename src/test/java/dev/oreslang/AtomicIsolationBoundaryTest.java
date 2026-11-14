package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Atomic<int> has an actual local guest primitive, not yet a proven shared
 * allocation domain. Typechecker must reject every actor transport escape.
 */
final class AtomicIsolationBoundaryTest {
    private static String reject(String code) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        return failure.getMessage();
    }

    @Test
    void atomicCellCannotCrossActorCallableBoundary() {
        String message = reject("""
                actor fnc invalid(Atomic<int> counter): void { return; }
                """);
        assertTrue(message.contains("cannot use Atomic across an actor boundary"), message);
    }

    @Test
    void nestedAtomicCellCannotHideInsideOption() {
        String message = reject("""
                actor fnc invalid(Option<Atomic<int>> counter): void { return; }
                """);
        assertTrue(message.contains("Atomic"), message);
    }

    @Test
    void sharedMutexCannotBecomeImplicitAtomicTransport() {
        String message = reject("""
                pub fnc main(): void {
                    val c: Atomic<int> = Atomic.new(0);
                    val shared = SharedMutex.new(c);
                    return;
                }
                """);
        assertTrue(message.contains("SharedMutex<T> requires shared-safe owned data"), message);
    }

    @Test
    void unsupportedAtomicTypesFailClosed() {
        String message = reject("""
                pub fnc main(): void {
                    val c: Atomic<String> = Atomic.new(0);
                    return;
                }
                """);
        assertTrue(message.contains("Atomic<T> currently supports exactly Atomic<int>"), message);
    }
}
