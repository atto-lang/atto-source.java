package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StdlibMacroAotSourceTest {
    private static String library() {
        try {
            // Deliberately read the compiler-local .atto source, not a Java
            // reimplementation or a copied test fixture.
            return Files.readString(Path.of("stdlib/core/macros.atto"), StandardCharsets.UTF_8)
                    + "\n" + Files.readString(Path.of("stdlib/std/macros.atto"), StandardCharsets.UTF_8)
                    + "\n" + Files.readString(Path.of("stdlib/alloc/macros.atto"), StandardCharsets.UTF_8)
                    + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("missing compiler-owned .atto macros", e);
        }
    }

    private static Ast.Program expand(String source) {
        return OresCompiler.parseAndTypeCheck(library() + source);
    }

    private static void returnsLiteral42(String body) {
        Ast.Program checked = assertDoesNotThrow(() -> expand(
                "pub fnc main() -> int { return " + body + "; }"));
        assertTrue(checked.modules().stream().flatMap(m -> m.declarations().stream())
                .noneMatch(d -> d instanceof Ast.MacroDecl || d instanceof Ast.MacroInvocationDecl),
                "AOT guest AST must contain no executable macro declarations");
        Ast.FunctionDecl main = checked.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> "main".equals(fn.name()))
                .findFirst().orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, main.body().getFirst());
        Ast.LiteralExpr literal = assertInstanceOf(Ast.LiteralExpr.class, returned.value());
        assertEquals(42L, literal.value());
    }

    @Test
    void allSixPublicMacroEntriesExpandToClosedAotScalarCode() {
        for (String expression : List.of(
                "const_int!(const 6 * 7)",
                "splice_expr!(syntax expr 42)",
                "const_retry_count!(const 40 + 2)",
                "const_timeout_ns!(const 21 * 2)",
                "const_capacity!(const 40 + 2)",
                "const_alignment!(const 6 * 7)",
                "splice_expr!(syntax expr const_int!(const 6 * 7))")) {
            returnsLiteral42(expression);
        }
    }

    @Test
    void runtimeValuesDoNotBecomeConstants() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> expand("pub fnc main(int input) -> int { "
                        + "return const_capacity!(const input); }"));
        assertTrue(failure.getMessage().contains("not a closed compile-time expression"),
                failure::toString);
    }

    @Test
    void wrongMacroArgumentKindsCannotFallBackToRuntime() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> expand("pub fnc main() -> int { "
                        + "return splice_expr!(const 42); }"));
        assertTrue(failure.getMessage().contains("requires Syntax<Expr>, got const"),
                failure::toString);
    }

    @Test
    void CompileTimeIntegerOverflowRejectsBeforeCodegen() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> expand("pub fnc main() -> int { "
                        + "return const_retry_count!(const 9223372036854775807 + 1); }"));
        assertTrue(failure.getMessage().contains("compile-time integer + overflow"),
                failure::toString);
    }
}
