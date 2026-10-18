package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A normal synchronous callback is NOT a suspension continuation, even
 * when it is passed from async/suspends code. The enclosing effect must
 * be saved and restored across both callback-validation paths.
 */
final class SuspendsCallbackIsolationTest {
    @Test
    void expectedFunctionCallbackCannotBorrowCallerSuspendPermission() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc read_blocking(): int { return 7; }
                        fnc apply(Fnc<int, int> callback, int n): int {
                            return callback(n);
                        }
                        suspends fnc bad(): int {
                            return apply(|int n| -> read_blocking(), 3);
                        }
                        """));
        assertTrue(failure.getMessage().contains("suspends")
                && failure.getMessage().contains("caller"), failure::getMessage);
    }

    @Test
    void mutexCallbackCannotInheritSuspendsPermission() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc read_blocking(): int { return 7; }
                        suspends fnc bad(): int {
                            val mutex = Mutex.new(3);
                            mutex.with_lock(|n| -> {
                                stdio.println(read_blocking());
                                return;
                            });
                            return 1;
                        }
                        """));
        assertTrue(failure.getMessage().contains("suspends")
                && failure.getMessage().contains("caller"), failure::getMessage);
    }

    @Test
    void mutexCallbackCannotAwaitEvenWhenEnclosingFunctionIsAsync() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc answer(): int { return 4; }
                        async fnc bad(): int {
                            val mutex = Mutex.new(1);
                            mutex.with_lock(|n| -> {
                                stdio.println(await answer());
                                return;
                            });
                            return 1;
                        }
                        """));
        assertTrue(failure.getMessage().contains("await")
                && (failure.getMessage().contains("suspends caller")
                    || failure.getMessage().contains("MutexGuard")),
                failure::getMessage);
    }

    @Test
    void typedCallbackDoesNotPreventCallerFromSuspendingAfterReturn() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                suspends fnc read_blocking(): int { return 7; }
                fnc apply(Fnc<int, int> callback, int n): int {
                    return callback(n);
                }
                suspends fnc good(): int {
                    let int a = apply(|int n| -> n + 1, 3);
                    return a + read_blocking();
                }
                """));
    }

    @Test
    void mutexCallbackDoesNotPreventCallerFromSuspendingAfterReturn() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                suspends fnc read_blocking(): int { return 7; }
                suspends fnc good(): int {
                    val mutex = Mutex.new(1);
                    mutex.with_lock(|n| -> {
                        stdio.println(n);
                        return;
                    });
                    return read_blocking();
                }
                """));
    }

    @Test
    void nestedBorrowContainerCannotRemainLiveThroughSuspension() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc read_blocking(): int { return 7; }
                        suspends fnc bad(): int {
                            val int value = 12;
                            val borrow int view = rt borrow value;
                            val nested = (view, 1);
                            return read_blocking();
                        }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"),
                failure::getMessage);
    }
    @Test
    void standaloneSuspendsFunctionCannotBeReifiedAsPlainFnc() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc read_blocking(): int { return 3; }
                        fnc bad(): void {
                            val Fnc<int> callback = read_blocking;
                            return;
                        }
                        """));
        assertTrue(error.getMessage().contains("suspends function")
                && error.getMessage().contains("Fnc"), error::getMessage);
    }

    @Test
    void staticSuspendsFunctionCannotBeReifiedAsPlainFnc() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Holder as
                          pub static suspends fnc read_blocking(): int { return 3; }
                        end
                        fnc bad(): void {
                            val Fnc<int> callback = Holder.read_blocking;
                            return;
                        }
                        """));
        assertTrue(error.getMessage().contains("suspends static function"),
                error::getMessage);
    }

    @Test
    void staticSuspendsFunctionCannotBePassedAsTypedCallback() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Holder as
                          pub static suspends fnc read_blocking(): int { return 3; }
                        end
                        fnc invoke(Fnc<int> callback): int { return callback(); }
                        fnc bad(): int {
                            return invoke(Holder.read_blocking);
                        }
                        """));
        assertTrue(error.getMessage().contains("suspends static function"),
                error::getMessage);
    }

    @Test
    void moduleSuspendsFunctionCannotBeReifiedButDirectCallStillWorks() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define module Operations as
                  pub suspends fnc read_blocking(): int { return 3; }
                end
                suspends fnc good(): int {
                    return Operations.read_blocking();
                }
                """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module Operations as
                          pub suspends fnc read_blocking(): int { return 3; }
                        end
                        fnc bad(): void {
                            val Fnc<int> callback = Operations.read_blocking;
                            return;
                        }
                        """));
        assertTrue(error.getMessage().contains("member")
                || error.getMessage().contains("suspends"), error::getMessage);
    }

}
