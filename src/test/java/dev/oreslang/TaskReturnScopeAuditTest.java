package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ReturnCleanupChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task bodies must never inherit a surrounding lambda's return/cleanup scope. */
final class TaskReturnScopeAuditTest {
    @Test
    void validTaskReturnHasOwnLexicalExit() {
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(Parser.parse("""
                pub fnc main(): void {
                    let task = (|int a| -> nb {
                        return a;
                    })(5);
                    return;
                }
                """)));
    }

    @Test
    void illegalFinallyReturnInsideIifeTaskIsNotSkipped() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            let task = (|int a| -> nb {
                                try {
                                    return a;
                                } catch (err) {
                                    return 0;
                                } finally {
                                    return 3;
                                }
                            })(5);
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("return is not allowed inside finally"), failure::getMessage);
    }

    @Test
    void illegalFinallyReturnInsideDiscardedTaskIsNotSkipped() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            do nb {
                                try {
                                    return;
                                } catch (err) {
                                    return;
                                } finally {
                                    return;
                                }
                            };
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("return is not allowed inside finally"), failure::getMessage);
    }
}
