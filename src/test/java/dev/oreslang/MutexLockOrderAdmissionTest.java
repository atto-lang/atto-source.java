package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nested lock admission is conservative until Oreslang can prove a total
 * lock order over the underlying (possibly aliased) mutex identities.
 * These are compiler checks, not runtime or deadlock-freedom proofs.
 */
final class MutexLockOrderAdmissionTest {
    private static final String TWO_MUTEXES = """
            fnc exercise(): void {
              const left = Mutex.new(1);
              const right = Mutex.new(2);
            """;

    private static void rejects(String statements) {
        String source = TWO_MUTEXES + statements + "\n}\n";
        var parsed = assertDoesNotThrow(() -> dev.oreslang.parser.Parser.parse(source),
                "a syntax failure is not an ownership safety pass");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(parsed));
        assertTrue(failure.getMessage().contains("nested mutex acquisition"),
                failure::getMessage);
    }

    @Test
    void two_live_guards_cannot_acquire_independent_mutexes_without_order_proof() {
        rejects("""
                  const first = left.lock();
                  const second = right.lock();
                  second.release();
                  first.release();
                """);
    }

    @Test
    void same_mutex_cannot_be_reacquired_through_the_same_handle() {
        rejects("""
                  const first = left.lock();
                  const second = left.lock();
                  second.release();
                  first.release();
                """);
    }

    @Test
    void nonblocking_try_lock_does_not_bypass_live_guard_requirement() {
        rejects("""
                  const first = left.lock();
                  const second = right.try_lock();
                  first.release();
                """);
    }

    @Test
    void async_lock_registration_does_not_bypass_live_guard_requirement() {
        rejects("""
                  const first = left.lock();
                  const pending = right.lock_async();
                  first.release();
                """);
    }

    @Test
    void inline_mutex_factory_cannot_bypass_live_guard_admission() {
        rejects("""
                  const first = left.lock();
                  const second = Mutex.new(3).lock();
                  first.release();
                """);
    }

    @Test
    void inline_shared_mutex_factory_cannot_bypass_live_guard_admission() {
        rejects("""
                  const first = left.lock();
                  const second = SharedMutex.new(3).try_lock();
                  first.release();
                """);
    }

    @Test
    void inline_mutex_with_lock_must_validate_the_lexical_critical_callback() {
        rejects("""
                  Mutex.new(3).with_lock(|value| -> {
                    const second = Mutex.new(4).lock();
                    second.release();
                    return;
                  });
                """);
    }

    @Test
    void inline_shared_mutex_recover_must_validate_the_lexical_critical_callback() {
        rejects("""
                  SharedMutex.new(3).recover(|value| -> {
                    const second = Mutex.new(4).lock();
                    second.release();
                    return;
                  });
                """);
    }

    @Test
    void inline_mutex_callback_without_nested_acquisition_remains_admissible() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(TWO_MUTEXES + """
                  Mutex.new(3).with_lock(|value| -> {
                    return;
                  });
                  return;
                }
                """));
    }

    @Test
    void guards_released_between_acquisitions_remain_valid() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(TWO_MUTEXES + """
                  const first = left.lock();
                  first.release();
                  const second = right.lock();
                  second.release();
                  return;
                }
                """));
    }
}
