package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SourceSupervisorContractTest {
    private static Ast.Program compile(String source) {
        return TypeChecker.check(Parser.parse(source));
    }

    @Test
    void requiresAllGuestEffectHooksWhenAnyIsPresent() {
        var error = assertThrows(IllegalArgumentException.class, () -> compile("""
                actor Partial {
                    on_panic(String summary): void { return; }
                }
                """));
        assertTrue(error.getMessage().contains("on_throw"), error.getMessage());
    }

    @Test
    void acceptsCompleteEffectHooksOrAnExplicitEscalationPolicy() {
        assertDoesNotThrow(() -> compile("""
                actor Supervisor {
                    on_throw(String summary): void { return; }
                    on_raise(String summary): void { return; }
                    on_panic(String summary): void { return; }
                }
                """));
        assertDoesNotThrow(() -> compile("""
                actor Supervisor {
                    supervision_policy(): String {
                        return "escalate";
                    }
                }
                """));
        assertDoesNotThrow(() -> compile("""
                actor Leaf {
                }
                """));
    }

    @Test
    void rejectsDirectNestedSpawnWithoutACompileTimeSupervisionContract() {
        var error = assertThrows(IllegalArgumentException.class, () -> compile("""
                actor Unsupervised {
                    startChild(): void {
                        val child = spawn Unsupervised();
                        return;
                    }
                }
                """));
        assertTrue(error.getMessage().contains("cannot spawn a child"),
                error.getMessage());
    }

    @Test
    void allowsNestedSpawnWhenSupervisorHasCompleteEffectHooks() {
        assertDoesNotThrow(() -> compile("""
                actor Supervised {
                    on_throw(String summary): void { return; }
                    on_raise(String summary): void { return; }
                    on_panic(String summary): void { return; }
                    startChild(): void {
                        val child = spawn Supervised();
                        return;
                    }
                }
                """));
    }

    @Test
    void rejectsUnsafeHookAndPolicySignatures() {
        assertThrows(IllegalArgumentException.class, () -> compile("""
                actor Supervisor {
                    on_throw(String summary): void { return; }
                    on_raise(String summary): void { return; }
                    on_panic(int summary): void { return; }
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("""
                actor Supervisor {
                    supervision_policy(int value): String {
                        return "escalate";
                    }
                }
                """));
    }
}
