package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler admission for the proposed stack-value, transport-owned request
 * borrow scope. Parsing is enabled; runtime/type lowering intentionally fails
 * closed until generation-authenticated loan/unwind/suspend proofs are complete.
 */
final class UsingRequestBorrowScopeParserTest {
    private static String method(String body) {
        return "fnc run(): void {\n" + body + "\nreturn;\n}\n";
    }

    private static Ast.FunctionDecl onlyFunction(String source) {
        return (Ast.FunctionDecl) Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
    }

    private static void rejectsParse(String body, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(method(body)));
        assertTrue(error.getMessage().contains(message), error::getMessage);
    }

    @Test
    void producesDedicatedLexicallyScopedAst() {
        Ast.FunctionDecl function = onlyFunction(method("""
                using request.borrow() as view {
                    const method = view.method();
                    const path = view.path();
                }
                """));
        Ast.UsingBorrowStmt using = assertInstanceOf(
                Ast.UsingBorrowStmt.class, function.body().getFirst());
        assertEquals("view", using.binding());
        assertInstanceOf(Ast.CallExpr.class, using.resource());
        assertEquals(2, using.body().size());
        assertInstanceOf(Ast.BindingStmt.class, using.body().get(0));
    }

    @Test
    void acceptsNestedSyntaxButDoesNotEnableUnsafeLowering() {
        String source = method("""
                using request.borrow() as outer {
                    using other.borrow() as inner {
                        inner.method();
                    }
                }
                """);
        Ast.FunctionDecl function = onlyFunction(source);
        Ast.UsingBorrowStmt outer = assertInstanceOf(
                Ast.UsingBorrowStmt.class, function.body().getFirst());
        assertInstanceOf(Ast.UsingBorrowStmt.class, outer.body().getFirst());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("nested using loans require verified alias compatibility"), error::getMessage);
    }

    @Test
    void malformedResourcesAndAliasesAreRejected() {
        rejectsParse("using request.claim() as view {}", "requires a zero-argument resource.borrow()");
        rejectsParse("using request.borrow(1) as view {}", "requires a zero-argument resource.borrow()");
        rejectsParse("using request.borrow() view {}", "requires 'as <view>'");
        rejectsParse("using request.borrow() as _ {}", "needs a named, usable view binding");
        rejectsParse("using request.borrow() as view;", "expected '{'");
    }

    @Test
    void ordinaryUsingNamedFunctionCallStaysAnExpression() {
        Ast.FunctionDecl function = onlyFunction(method("using(1);"));
        assertInstanceOf(Ast.ExprStmt.class, function.body().getFirst());
    }

    @Test
    void scopedAsDelimiterDoesNotConsumeOrdinaryCastExpressions() {
        Ast.FunctionDecl scoped = onlyFunction(method(
                "using request.borrow() as view { const path = view.path(); }"));
        Ast.UsingBorrowStmt borrow = assertInstanceOf(
                Ast.UsingBorrowStmt.class, scoped.body().getFirst());
        assertInstanceOf(Ast.CallExpr.class, borrow.resource());
        Ast.FunctionDecl cast = onlyFunction(method(
                "const copy = source as String;"));
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, cast.body().getFirst());
        assertInstanceOf(Ast.CastExpr.class, binding.initializer());
    }

    @Test
    void contextualUsingDoesNotStealOrdinaryIdentifierExpressions() {
        Ast.FunctionDecl normal = onlyFunction(method("using + 1;"));
        assertInstanceOf(Ast.ExprStmt.class, normal.body().getFirst());
    }

    @Test
    void evenApparentlySafeBorrowCannotRunBeforeRuntimeProvenanceGate() {
        String source = method("""
                using request.borrow() as view {
                    view.method();
                }
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(
                "using request.borrow() as view requires verified transport-loan"),
                failure::getMessage);
    }
}
