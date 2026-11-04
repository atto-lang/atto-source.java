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

    private static Ast.Program exactlyNCalls(int count) {
        StringBuilder source = new StringBuilder("""
                define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                fnc answer() -> int {
                """);
        for (int i = 0; i < count; i++) {
            source.append("val v").append(i)
                    .append(":int=identity!(syntax expr 1);");
        }
        source.append("return 42;}");
        return MacroSourceUnitIdentity.attach(
                Parser.parse(source.toString()), "tests/expansion-quota.atto");
    }

    @Test
    void portableMacroExpansionBudgetAdmitsExactly128() {
        assertEquals(128, MacroExpander.MAX_EXPANSIONS);
        assertDoesNotThrow(() -> MacroExpander.expand(exactlyNCalls(128)));
    }

    @Test
    void portableMacroExpansionBudgetDenies129WithoutRuntimeFallback() {
        assertEquals(128, MacroExpander.MAX_EXPANSIONS);
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(exactlyNCalls(129)));
        assertTrue(denied.getMessage().contains("macro expansion count exceeds 128"),
                denied::toString);
        assertTrue(denied.getMessage().contains("runtime fallback is forbidden"),
                denied::toString);
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
