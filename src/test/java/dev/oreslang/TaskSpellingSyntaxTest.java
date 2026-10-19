package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** `task {}` is a Task expression; only `await` joins it. */
final class TaskSpellingSyntaxTest {
    private static Ast.FunctionDecl main(String source) {
        Ast.Program p = Parser.parse("pub fnc main(): void { " + source + " return; }");
        return p.modules().stream().flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(f -> f.name().equals("main")).findFirst().orElseThrow();
    }

    @Test
    void taskAndLegacyNbHaveIdenticalAstAndReturnHandles() {
        for (String keyword : new String[]{"task", "nb", "nb task"}) {
            Ast.FunctionDecl function = main("let Task<int> job = "
                    + keyword + " { return 7; };");
            Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class,
                    function.body().getFirst());
            Ast.TaskExpr expression = assertInstanceOf(Ast.TaskExpr.class, binding.initializer());
            assertInstanceOf(Ast.ReturnStmt.class, expression.body().getFirst());
        }
    }

    @Test
    void doTaskAndDoNbDiscardTheSameHandleWithoutChangingBody() {
        for (String keyword : new String[]{"task", "nb"}) {
            Ast.FunctionDecl fn = main("do " + keyword + " { let int x = 3; };");
            Ast.TaskBlockStmt detached = assertInstanceOf(Ast.TaskBlockStmt.class,
                    fn.body().getFirst());
            assertInstanceOf(Ast.BindingStmt.class, detached.body().getFirst());
        }
    }

    @Test
    void awaitTaskJoinsOneTaskAndNothingElse() {
        Ast.FunctionDecl fn = main("let int result = await task { return 7; };");
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class,
                fn.body().getFirst());
        Ast.AwaitExpr await = assertInstanceOf(Ast.AwaitExpr.class, binding.initializer());
        assertInstanceOf(Ast.TaskExpr.class, await.expression());
    }

    @Test
    void taskInsideTaskLambdaIsASecondRealTaskNotImplicitlyFlattened() {
        Ast.FunctionDecl fn = main("""
                let Task<Task<int>> nested = (async task |int n| -> task {
                    return n;
                })(5);
                """);
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class,
                fn.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, binding.initializer());
        Ast.TaskLambdaExpr lambda = assertInstanceOf(Ast.TaskLambdaExpr.class, call.callee());
        assertInstanceOf(Ast.TaskExpr.class, lambda.lambda().expressionBody());
    }

    @Test
    void nbTaskIsAnAliasAndImmediateAwaitJoinsOneTask() {
        Ast.FunctionDecl fn = main("let int answer = await nb task { return 7; };");
        Ast.BindingStmt binding = assertInstanceOf(Ast.BindingStmt.class, fn.body().getFirst());
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, binding.initializer());
        assertInstanceOf(Ast.TaskExpr.class, awaited.expression());
    }

    @Test
    void bareNbTaskRequiresExplicitHandleOrDiscard() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> main("nb task { return 7; };"));
        assertTrue(failure.getMessage().contains("do task"), failure::getMessage);
    }

    @Test
    void bareTaskStatementMustExplicitlyDiscardItsHandle() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> main("task { return 7; };"));
        assertTrue(failure.getMessage().contains("do task"), failure::getMessage);
    }

    @Test
    void taskCancellationAndPostCleanupJoinParseButRemainRuntimeGated() {
        String source = """
                pub fnc main(): void {
                    let Task<int> job = nb task { return 7; };
                    job.cancel();
                    await job.get_done();
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        // Parsing these member names does not mean Task execution, actor
        // supervision or physical quiescence has been compiled.
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void taskIdentifierRemainsLegalCallableName() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc task(): int { return 3; }
                pub fnc main(): void { let int answer = task(); return; }
                """));
    }
}
