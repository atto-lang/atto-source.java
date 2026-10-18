package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit: an immediately invoked ordinary lambda may return Task<T>.
 * These are parser-only tests until capture ownership and Task lowering exist.
 */
final class TaskLambdaIifeSyntaxTest {
    private static Ast.Expr initializer(String expression) {
        Ast.Program program = Parser.parse(
                "pub fnc main(): void { let result = " + expression + "; return; }");
        Ast.FunctionDecl main = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst().orElseThrow();
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, main.body().getFirst());
        return binding.initializer();
    }

    @Test
    void expressionLambdaIifeReturnsSingleTaskLayer() {
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, initializer("""
                (|int a, string b| -> nb {
                    return a;
                })(5, "xyz")
                """));
        assertEquals(2, call.arguments().size());
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, call.callee());
        assertEquals("int", lambda.parameters().get(0).type().name());
        assertEquals("a", lambda.parameters().get(0).name());
        assertEquals("string", lambda.parameters().get(1).type().name());
        assertEquals("b", lambda.parameters().get(1).name());
        assertNull(lambda.blockBody());
        Ast.TaskExpr task = assertInstanceOf(Ast.TaskExpr.class, lambda.expressionBody());
        Ast.ReturnStmt result = assertInstanceOf(Ast.ReturnStmt.class, task.body().getFirst());
        assertInstanceOf(Ast.NameExpr.class, result.value());
    }

    @Test
    void blockLambdaIifeReturnsTaskExplicitly() {
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, initializer("""
                (|int a, string b| -> {
                    return nb {
                        return a;
                    };
                })(5, "xyz")
                """));
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, call.callee());
        assertNull(lambda.expressionBody());
        Ast.ReturnStmt statement = assertInstanceOf(Ast.ReturnStmt.class, lambda.blockBody().getFirst());
        assertInstanceOf(Ast.TaskExpr.class, statement.value());
    }

    @Test
    void outerAwaitIsARealTaskJoinNotAFireAndContinue() {
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, initializer("""
                await (|int a, string b| -> nb {
                    return a;
                })(5, "xyz")
                """));
        Ast.CallExpr called = assertInstanceOf(Ast.CallExpr.class, awaited.expression());
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, called.callee());
        assertInstanceOf(Ast.TaskExpr.class, lambda.expressionBody());
    }

    @Test
    void asyncLambdaWrapsNbWithExplicitFutureTaskNestingInAst() {
        for (String form : new String[]{
                "async |int a, string b| -> nb { return a; }",
                "async |int a, string b| -> { return nb { return a; }; }"
        }) {
            Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class,
                    initializer("(" + form + ")(5, \"xyz\")"));
            Ast.AsyncLambdaExpr outer = assertInstanceOf(Ast.AsyncLambdaExpr.class, call.callee());
            if (outer.lambda().expressionBody() != null) {
                assertInstanceOf(Ast.TaskExpr.class, outer.lambda().expressionBody());
            } else {
                Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class,
                        outer.lambda().blockBody().getFirst());
                assertInstanceOf(Ast.TaskExpr.class, returned.value());
            }
        }
    }

    @Test
    void neitherIifeFormMayBypassRuntimeAdmission() {
        for (String form : new String[]{
                "(|int a, string b| -> nb { return a; })(5, \"xyz\")",
                "(|int a, string b| -> { return nb { return a; }; })(5, \"xyz\")"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub fnc main(): void { let Task<int> task = " + form + "; return; }"));
        }
    }
}
