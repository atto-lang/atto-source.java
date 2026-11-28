package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The production parser now retains distinct protected nodes, not executable aliases. */
final class StateContainerVirtualActorAdmissionTest {
    @Test
    void declarationsParseAndStillFailClosedBeforeUncheckedLowering() {
        assertProtected("""
                define state container Counter as
                  state { let int n: 0; }
                end
                """, Ast.StateContainerDecl.class, "STATE_CONTAINER_MIR_UNSUPPORTED");
        assertProtected("""
                define virtual actor Cart as
                  state { let int n: 0; }
                end
                """, Ast.VirtualActorDecl.class, "VIRTUAL_ACTOR_MIR_UNSUPPORTED");
    }

    @Test
    void moduleAndVisibilityCannotBypassTypeChecking() {
        assertProtected("""
                define module sample as
                  define pub state container Counter as
                    state { let int n: 0; }
                  end
                end
                """, Ast.StateContainerDecl.class, "STATE_CONTAINER_MIR_UNSUPPORTED");
        assertProtected("""
                define module sample as
                  define pub virtual actor Cart as
                    state { let int n: 0; }
                  end
                end
                """, Ast.VirtualActorDecl.class, "VIRTUAL_ACTOR_MIR_UNSUPPORTED");
    }

    @Test
    void ordinaryActorIsUnchangedAndGrainIsNotAnAlias() {
        assertInstanceOf(Ast.ClassDecl.class, first(Parser.parse(
                "define actor Worker as\nend\n")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define grain ShoppingCart as\nend\n"));
    }

    @Test
    void tokenSeparatedHeadsCannotBypassSemanticGates() {
        assertProtected("""
                define virtual /* trivia */ actor Cart as
                  state { let int n: 0; }
                end
                """, Ast.VirtualActorDecl.class, "VIRTUAL_ACTOR_MIR_UNSUPPORTED");
        assertProtected("""
                define state /* trivia */ container Counter as
                  state { let int n: 0; }
                end
                """, Ast.StateContainerDecl.class, "STATE_CONTAINER_MIR_UNSUPPORTED");
    }

    private static Ast.Decl first(Ast.Program program) {
        return program.modules().getFirst().declarations().getFirst();
    }

    private static void assertProtected(String source, Class<? extends Ast.Decl> expected,
                                        String errorTag) {
        assertTrue(expected.isInstance(first(Parser.parse(source))));
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(error.getMessage().contains(errorTag), error::getMessage);
    }
}
