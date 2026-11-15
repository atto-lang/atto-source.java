package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ProtectedDeclarationAstAdmissionTest {
    private static final String STATE = """
            define pub state container Counter as
              state { let int count: 0; }
              increment(int amount) -> int ::
                return handle_state(critical-write |s| -> {
                  s.count = s.count + amount;
                  s.count;
                });
              end
            end
            """;
    private static final String VIRTUAL = """
            define virtual actor ShoppingCart as
              state { let int quantity: 0; }
              add(int n) -> void ::
                handle_state(critical-write |s| -> {
                  s.quantity = s.quantity + n;
                });
              end
            end
            """;

    private static Ast.Decl declaration(String source) {
        var parsed = Parser.parse(source);
        assertEquals(1, parsed.modules().size());
        var declarations = parsed.modules().getFirst().declarations();
        assertEquals(1, declarations.size());
        return declarations.getFirst();
    }

    @Test void stateContainerHasDedicatedAstAndGuardedTokens() {
        var c = assertInstanceOf(Ast.StateContainerDecl.class, declaration(STATE));
        assertEquals("Counter", c.name());
        assertEquals(Ast.Visibility.PUBLIC, c.visibility());
        assertEquals(1, c.stateFields().size());
        assertEquals("count", c.stateFields().getFirst().name());
        assertEquals("int", c.stateFields().getFirst().type().name());
        assertEquals("increment", c.methods().getFirst().name());
        assertEquals(1, c.methods().getFirst().parameters().size());
        assertTrue(c.methods().getFirst().bodyTokens().contains("handle_state"));
        assertTrue(c.methods().getFirst().bodyTokens().contains("critical"));
        assertThrows(UnsupportedOperationException.class,
                () -> c.methods().getFirst().bodyTokens().clear());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(STATE)));
        assertTrue(failure.getMessage().contains("STATE_CONTAINER_MIR_UNSUPPORTED"));
    }

    @Test void virtualActorIsNotNormalActorAndCannotBypassMIR() {
        var a = assertInstanceOf(Ast.VirtualActorDecl.class, declaration(VIRTUAL));
        assertEquals("ShoppingCart", a.name());
        assertEquals("quantity", a.stateFields().getFirst().name());
        assertEquals("add", a.methods().getFirst().name());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(VIRTUAL)));
        assertTrue(failure.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }

    @Test void moduleScopeAndVisibilityVariantsAreAccepted() {
        assertInstanceOf(Ast.StateContainerDecl.class,
                declaration(STATE.replace("define pub state container", "pub define state container")));
        var parsed = Parser.parse("""
                define module Inventory as
                  define virtual actor Cart as
                    state { let int quantity: 0; }
                  end
                end
                """);
        assertEquals(1, parsed.modules().size());
        assertInstanceOf(Ast.VirtualActorDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
    }

    @Test void duplicateStateFieldsAndMethodsFailBeforeTypeChecker() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                STATE.replace("state { let int count: 0; }",
                              "state { let int count: 0; let int count: 1; }")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(STATE.replace("  end\nend",
                        "  end\n  increment(int amount) -> int :: return 0; end\nend")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(STATE.replace("let int count: 0", "val int count: 0")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(STATE.replace("s.count = s.count + amount", "s.count = s.count + amount; define class Evil as end")));
    }
}
