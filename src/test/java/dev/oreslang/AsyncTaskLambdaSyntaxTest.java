package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ReturnCleanupChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source contract only. Task/Future lambda lowering and capture ownership
 * remain gated until continuations and Task<T> / Future<T> typing are complete.
 */
final class AsyncTaskLambdaSyntaxTest {
    private static Ast.Expr initializer(String source) {
        Ast.Program program = Parser.parse(
                "pub fnc main(): void { let result = " + source + "; return; }");
        Ast.FunctionDecl main = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst().orElseThrow();
        return assertInstanceOf(Ast.BindingStmt.class, main.body().getFirst()).initializer();
    }

    @Test
    void asyncTaskIifeHasOneTaskBodyAndNormalArgumentCall() {
        Ast.CallExpr called = assertInstanceOf(Ast.CallExpr.class, initializer("""
                (async task |int a, string b| -> {
                    return compute(a, b);
                })(5, "xyz")
                """));
        assertEquals(2, called.arguments().size());
        Ast.TaskLambdaExpr task = assertInstanceOf(Ast.TaskLambdaExpr.class, called.callee());
        assertEquals(2, task.lambda().parameters().size());
        assertEquals("int", task.lambda().parameters().get(0).type().name());
        assertEquals("a", task.lambda().parameters().get(0).name());
        assertEquals("string", task.lambda().parameters().get(1).type().name());
        assertEquals("b", task.lambda().parameters().get(1).name());
        assertNull(task.lambda().expressionBody());
        assertEquals(1, task.lambda().blockBody().size());
        assertInstanceOf(Ast.ReturnStmt.class, task.lambda().blockBody().getFirst());
    }

    @Test
    void taskLambdaSupportsShortExpressionBodyWithoutExtraNb() {
        Ast.CallExpr called = assertInstanceOf(Ast.CallExpr.class, initializer("""
                (async task |int a, string b| -> compute(a, b))(5, "xyz")
                """));
        Ast.TaskLambdaExpr task = assertInstanceOf(Ast.TaskLambdaExpr.class, called.callee());
        assertNull(task.lambda().blockBody());
        assertInstanceOf(Ast.CallExpr.class, task.lambda().expressionBody());
    }

    @Test
    void awaitOfTaskLambdaIifeJoinsSingleTask() {
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, initializer("""
                await (async task |int a, string b| -> {
                    return a;
                })(5, "xyz")
                """));
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, awaited.expression());
        assertInstanceOf(Ast.TaskLambdaExpr.class, call.callee());
    }

    @Test
    void ordinaryAsyncLambdaRetainsSeparateFutureAndNestedTask() {
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, initializer("""
                await (async |int a, string b| -> nb {
                    return a;
                })(5, "xyz")
                """));
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, awaited.expression());
        Ast.AsyncLambdaExpr wrapper = assertInstanceOf(Ast.AsyncLambdaExpr.class, call.callee());
        assertInstanceOf(Ast.TaskExpr.class, wrapper.lambda().expressionBody());
        // Once lowered, this outer await unwraps Future<Task<int>>,
        // producing Task<int>, NOT the underlying int.
    }

    @Test
    void ordinaryAsyncLambdaAcceptsBracedBodyAsFutureProducingCallable() {
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, initializer("""
                (async |int a| -> { return a; })(5)
                """));
        Ast.AsyncLambdaExpr async = assertInstanceOf(Ast.AsyncLambdaExpr.class, call.callee());
        assertNotNull(async.lambda().blockBody());
    }

    @Test
    void malformedAsyncTaskModifiersFailClosed() {
        for (String expression : new String[]{
                "(async task foo)(5)",
                "(async foo)(5)",
                "(async task |int a| compute(a))(5)"
        }) {
            assertThrows(IllegalArgumentException.class, () -> initializer(expression));
        }
    }

    @Test
    void unloweredAsyncFormsCannotBypassTypeAdmission() {
        for (String expression : new String[]{
                "(async task |int a| -> { return a; })(5)",
                "(async |int a| -> nb { return a; })(5)"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub fnc main(): void { let value = " + expression + "; return; }"));
            assertTrue(error.getMessage().contains("lambda"), error::getMessage);
        }
    }

    @Test
    void asyncTaskReturnInsideFinallyIsNeverAllowed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            let task = (async task |int a| -> {
                                try {
                                    return a;
                                } catch (err) {
                                    return 0;
                                } finally {
                                    return 3;
                                }
                            })(5);
                            return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("return is not allowed inside finally"), error::getMessage);
    }

    @Test
    void nestedTaskInOrdinaryAsyncLambdaStillHasOwnCheckedFinally() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            let task = (async |int a| -> nb {
                                try {
                                    return a;
                                } catch (err) {
                                    return 0;
                                } finally {
                                    return 3;
                                }
                            })(5);
                            return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("return is not allowed inside finally"), error::getMessage);
    }
}
