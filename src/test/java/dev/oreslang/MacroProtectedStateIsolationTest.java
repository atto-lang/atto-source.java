package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.MacroExpander;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** No macro expansion may erase or reinterpret newer sealed state declarations. */
final class MacroProtectedStateIsolationTest {
    private static final String SOURCE = """
            define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                return value;
            }
            define state container Vault as
                state { let int count: 0; }
            end
            define virtual actor Ghost as
                state { let int count: 0; }
            end
            fnc answer() -> int {
                return identity!(syntax expr 42);
            }
            """;

    @Test
    void macroExpansionRetainsProtectedStateAndVirtualActorNodes() {
        Ast.Program parsed = Parser.parse(SOURCE);
        Ast.StateContainerDecl state = parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.StateContainerDecl.class::isInstance)
                .map(Ast.StateContainerDecl.class::cast).findFirst().orElseThrow();
        Ast.VirtualActorDecl actor = parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.VirtualActorDecl.class::isInstance)
                .map(Ast.VirtualActorDecl.class::cast).findFirst().orElseThrow();

        Ast.Program expanded = MacroExpander.expand(parsed);
        assertTrue(expanded.modules().stream().flatMap(m -> m.declarations().stream())
                .anyMatch(d -> d == state), "protected state AST identity must be preserved");
        assertTrue(expanded.modules().stream().flatMap(m -> m.declarations().stream())
                .anyMatch(d -> d == actor), "virtual actor AST identity must be preserved");
        assertTrue(expanded.modules().stream().flatMap(m -> m.declarations().stream())
                .noneMatch(d -> d instanceof Ast.MacroDecl),
                "supported macro declaration is erased without erasing protected declarations");
    }

    @Test
    void specializedProtectedScalarMirNeverDiscardsCompilerMacroDeclarations() {
        // This entry point intentionally bypasses general TypeChecker only for
        // one exact protected-state MIR slice. It must still reject a source
        // with additional compile-time macro declarations rather than silently
        // discarding them and claiming successful protected lowering.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileProtectedScalarI64(SOURCE));
        assertTrue(failure.getMessage().contains("PROTECTED_MIR_UNSUPPORTED"),
                failure::toString);
    }

    @Test
    void expandedMacroDoesNotBypassProtectedStateMirAdmission() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(SOURCE));
        assertTrue(failure.getMessage().contains("STATE_CONTAINER_MIR_UNSUPPORTED"),
                failure::toString);
    }
}
