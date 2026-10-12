package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OwnershipEffectAuditTest {
    private static void reject(String source, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(source)));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }
    @Test void criticalStateLoanCannotCooperate() {
        for (String operation : new String[]{"rt cooperate;", "readch ch;", "writech ch, 1;", "select { case readch ch: val item { s.count = item; } }"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(Parser.parse("""
                            define actor Counter as
                              state { count: int = 0; }
                              pub probe(): void {
                                self.handle_state(critical |s| -> {
                                  val Channel<int> ch = Channel.new<int>(1);
                                  %s
                                  s.count = 1;
                                });
                                return;
                              }
                            end
                            """.formatted(operation))));
            assertTrue(error.getMessage().contains("state loan"), error.getMessage());
        }
    }

    @Test void criticalStateLoanEndsBeforeLaterCooperation() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                define actor Counter as
                  state { count: int = 0; }
                  pub probe(): void {
                    self.handle_state(critical |s| -> { s.count = 1; });
                    rt cooperate;
                    return;
                  }
                end
                """)));
    }

    @Test void requirementsAreCheckedWithoutAUsingClause() {
        reject("fnc bad(): void requires no_alloc { val data = [1, 2]; return; }", "no_alloc");
        reject("fnc bad(): void requires no_alloc { val f = || -> 1; return; }", "no_alloc");
        reject("fnc bad(): void requires no_block { unknown(); return; }", "trusted callee");
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse(
                "fnc good(): int requires no_alloc, no_block { return 42; }")));
    }
    @Test void aggregateAndClosureConstructionRequireAllocEffect() {
        for (String expression : new String[]{"[1, 2]", "(1, 2)", "struct{n: int}{n: 1}", "|| -> 1"}) {
            reject("fnc bad(): void using io { val value = " + expression + "; return; }", "alloc");
            assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse(
                    "fnc good(): void using alloc { val value = " + expression + "; return; }")));
        }
    }
    @Test void deferredCleanupMustContributeItsExecutedBodyEffects() {
        reject("""
                fnc bad(Channel<int> ch): void requires no_block {
                  defer || -> { val int n = readch ch; return; };
                  return;
                }
                """, "no_block");
        reject("""
                fnc bad(Channel<int> ch): void using alloc, suspend {
                  defer || -> { val int n = readch ch; return; };
                  return;
                }
                """, "effect block");
        reject("""
                fnc bad(): void requires no_block {
                  defer || -> { unknown(); return; };
                  return;
                }
                """, "trusted callee");
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse("""
                fnc good(Channel<int> ch): void using alloc, suspend, block {
                  defer || -> { val int n = readch ch; return; };
                  return;
                }
                """)));
        // An ordinary stored lambda is not executed merely by construction.
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse("""
                fnc good(Channel<int> ch): void requires no_block {
                  val callback = || -> { val int n = readch ch; return; };
                  return;
                }
                """)));
    }

    @Test void allocatingCallableBodyIsNotExecutedByClosureCreation() {
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(Parser.parse(
                "fnc good(): void using alloc { val f = || -> { unknown(); return; }; return; }")));
    }
}
