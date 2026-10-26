package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MacroExpansionTraversalBudgetTest {
    @Test
    void deepRuntimeAstFailsClosedWhenMacroRewriteIsActive() {
        Ast.Program parsed = Parser.parse("""
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                """);
        Ast.MacroDecl macro = assertInstanceOf(
                Ast.MacroDecl.class,
                parsed.modules().getFirst().declarations().getFirst());

        Ast.Expr nested = new Ast.LiteralExpr(1L);
        for (int i = 0; i < MacroExpander.MAX_REWRITE_TRAVERSAL_DEPTH + 32; i++) {
            nested = new Ast.UnaryExpr("+", nested);
        }
        Ast.FunctionDecl deep = new Ast.FunctionDecl(
                "deep",
                Ast.Visibility.PRIVATE,
                false,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("int"),
                List.of(),
                List.of(new Ast.ReturnStmt(nested)));

        Ast.ModuleDecl original = parsed.modules().getFirst();
        Ast.Program program = new Ast.Program(
                parsed.namespace(),
                parsed.imports(),
                List.of(new Ast.ModuleDecl(
                        original.name(),
                        original.annotations(),
                        original.contracts(),
                        List.of(macro, deep))));
        MacroSourceUnitIdentity.attach(program, "tests/deep.ores");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroExpander.expand(program));
        assertTrue(failure.getMessage().contains("rewrite traversal depth"),
                failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"),
                failure::toString);
    }

    @Test
    void boundedRuntimeAstStillRewritesNormally() {
        Ast.Program program = MacroSourceUnitIdentity.attach(
                Parser.parse("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return identity!(syntax expr 40 + 2);
                        }
                        """),
                "tests/bounded.ores");

        Ast.Program expanded = assertDoesNotThrow(() -> MacroExpander.expand(program));
        assertTrue(expanded.modules().getFirst().declarations().stream()
                .noneMatch(Ast.MacroDecl.class::isInstance));
    }
}
