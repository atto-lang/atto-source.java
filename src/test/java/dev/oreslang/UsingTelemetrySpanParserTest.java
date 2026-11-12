package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real parser/compiler admission for the required telemetry.span lexical using
 * syntax. A parsed AST is NOT permission to execute until compiler-verified
 * scoped cleanup, ownership and suspension lowering have landed.
 */
final class UsingTelemetrySpanParserTest {
    private static String method(String body) {
        return "fnc run(): void {\n" + body + "\nreturn;\n}\n";
    }

    private static Ast.FunctionDecl onlyFunction(String source) {
        return (Ast.FunctionDecl) Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
    }

    private static void rejectsParse(String source, String diagnostic) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(method(source)));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test
    void parsesZeroAndNamedSpansIntoDedicatedStatements() {
        Ast.FunctionDecl fn = onlyFunction(method("""
                using telemetry.span() as unnamed {
                  unnamed.event("start");
                }
                using telemetry.span("checkout") as span {
                  span.attribute("operation", "checkout");
                  return;
                }
                """));
        assertEquals(3, fn.body().size()); // two using blocks and final return
        Ast.UsingTelemetrySpanStmt unnamed = assertInstanceOf(
                Ast.UsingTelemetrySpanStmt.class, fn.body().get(0));
        Ast.UsingTelemetrySpanStmt named = assertInstanceOf(
                Ast.UsingTelemetrySpanStmt.class, fn.body().get(1));
        assertEquals("unnamed", unnamed.binding());
        assertEquals("span", named.binding());
        assertEquals(1, unnamed.body().size());
        assertEquals(2, named.body().size());
        assertEquals(0, assertInstanceOf(Ast.CallExpr.class, unnamed.resource()).arguments().size());
        assertEquals(1, assertInstanceOf(Ast.CallExpr.class, named.resource()).arguments().size());
        assertInstanceOf(Ast.ReturnStmt.class, named.body().get(1));
    }

    @Test
    void nestedScopesDoNotAlterExistingBorrowGrammar() {
        Ast.FunctionDecl fn = onlyFunction(method("""
                using telemetry.span("outer") as outer {
                  using telemetry.span("inner") as inner {
                    inner.event("nested");
                  }
                }
                using request.borrow() as view {
                  const p = view.path();
                }
                """));
        Ast.UsingTelemetrySpanStmt outer = assertInstanceOf(
                Ast.UsingTelemetrySpanStmt.class, fn.body().getFirst());
        assertInstanceOf(Ast.UsingTelemetrySpanStmt.class, outer.body().getFirst());
        assertInstanceOf(Ast.UsingBorrowStmt.class, fn.body().get(1));
    }

    @Test
    void malformedBindingsAndResourceShapesFailClosed() {
        rejectsParse("using telemetry.span() as _ {}", "named, usable binding");
        rejectsParse("using telemetry.span() as span;", "expected '{'");
        rejectsParse("using telemetry.span(1, 2, 3) as span {}", "at most two arguments");
        rejectsParse("using telemetry.start() as span {}", "resource.borrow()");
        rejectsParse("using request.borrow(1) as view {}", "zero-argument resource.borrow()");
    }

    @Test
    void aMethodNamedSpanCannotBypassCompilerAuthorization() {
        String forged = method("""
                using arbitrary.span("forged") as span {
                  span.event("value");
                }
                """);
        assertInstanceOf(Ast.UsingTelemetrySpanStmt.class,
                onlyFunction(forged).body().getFirst());
        // Later implementations MUST check the trusted std.telemetry symbol
        // identity rather than admit any member named "span".
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(forged)));
        assertTrue(error.getMessage().contains(
                "using telemetry.span(...) as span requires verified"), error::getMessage);
    }

    @Test
    void typeCheckerRejectsUntilCleanupAndContinuationAreImplemented() {
        String source = method("""
                using telemetry.span("checkout") as span {
                  span.attribute("kind", "test");
                  return;
                }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(
                "continuation lowering; feature is not enabled"), error::getMessage);
    }

    @Test
    void ordinaryUsingFunctionAndAsCastKeepTheirExpressionSemantics() {
        Ast.FunctionDecl fn = onlyFunction(method("""
                using(1);
                const copied = source as String;
                """));
        assertInstanceOf(Ast.ExprStmt.class, fn.body().get(0));
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, fn.body().get(1));
        assertInstanceOf(Ast.CastExpr.class, binding.initializer());
    }
}
