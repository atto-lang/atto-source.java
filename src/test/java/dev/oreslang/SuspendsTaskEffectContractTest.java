package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ReturnCleanupChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 'suspends' describes a no-spawn execution effect, while Task/async create
 * independent scheduled completions. Existing Task source execution is gated.
 */
final class SuspendsTaskEffectContractTest {
    private static Ast.Expr initializer(String source) {
        Ast.Program program = Parser.parse(
                "pub fnc main(): void { let value = " + source + "; return; }");
        Ast.FunctionDecl main = program.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(f -> f.name().equals("main"))
                .findFirst().orElseThrow();
        return assertInstanceOf(Ast.BindingStmt.class,
                main.body().getFirst()).initializer();
    }

    @Test
    void suspendsLambdaHasExplicitNoSpawnAstIdentity() {
        for (String form : new String[]{
                "(suspends |int a| -> a)(5)",
                "(suspend |int a| -> a)(5)",
                "(suspends (int a) -> { return a; })(5)",
                "(suspend (int a) -> a)(5)"
        }) {
            Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, initializer(form));
            Ast.SuspendsLambdaExpr suspends = assertInstanceOf(
                    Ast.SuspendsLambdaExpr.class, call.callee());
            assertEquals(1, suspends.lambda().parameters().size());
        }
    }

    @Test
    void oldCallableNamedSuspendRemainsUnchanged() {
        Ast.CallExpr ordinary = assertInstanceOf(Ast.CallExpr.class, initializer("suspend(5)"));
        assertEquals("suspend",
                assertInstanceOf(Ast.NameExpr.class, ordinary.callee()).name());
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc suspend(int x): int { return x; }
                pub fnc main(): void { let int x = suspend(5); return; }
                """));
    }

    @Test
    void suspendsTaskModifierCannotCreateAmbiguousExtraTaskLayer() {
        for (String invalid : new String[]{
                "(suspends task |int a| -> a)(5)",
                "(async suspends |int a| -> a)(5)"
        }) {
            assertThrows(IllegalArgumentException.class, () -> initializer(invalid));
        }
    }

    @Test
    void suspendsLambdaCannotBeLoweredAsPlainSynchronousLambda() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                            let value = (suspends |int a| -> a)(5);
                            return;
                        }
                        """));
        assertTrue(error.getMessage().contains("suspends lambda"), error::getMessage);
    }

    @Test
    void ordinaryLambdaCannotInheritSuspendsDeclarationEffect() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc get_blocking(): int { return 1; }
                        suspends fnc outer(): int {
                            let invoke = |int a| -> get_blocking();
                            return 1;
                        }
                        """));
        assertTrue(error.getMessage().contains("requires an async or suspends caller"),
                error::getMessage);
    }

    @Test
    void ordinaryLambdaCannotInheritAsyncDeclarationEffect() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc compute(): int { return 2; }
                        async fnc outer(): int {
                            let invoke = |int a| -> await compute();
                            return 1;
                        }
                        """));
        assertTrue(error.getMessage().contains("await requires an async or suspends caller"),
                error::getMessage);
    }

    @Test
    void synchronousHelperCannotAwaitEvenThoughRootMainCan() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc compute(): int { return 2; }
                        fnc helper(): int { return await compute(); }
                        """));
        assertTrue(failure.getMessage().contains("await requires an async or suspends caller"),
                failure::getMessage);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                async fnc compute(): int { return 2; }
                pub fnc main(): void {
                    let int result = await compute();
                    return;
                }
                """));
    }

    @Test
    void suspendsFunctionCanAwaitWithoutSpawningNewTask() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                async fnc compute(): int { return 2; }
                suspends fnc bridge(): int {
                    return await compute();
                }
                async fnc caller(): int {
                    return bridge();
                }
                """));
    }

    @Test
    void normalSuspendsFunctionRemainsCallableFromRootMain() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                suspends fnc get_blocking(): int { return 12; }
                pub fnc main(): void {
                    let int x = get_blocking();
                    return;
                }
                """));
    }

    @Test
    void awaitAndSuspendsTaskCompositionParsesButExecutionStaysGated() {
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class, initializer("""
                await (async task |int n| -> task {
                    return n;
                })(5)
                """));
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, awaited.expression());
        Ast.TaskLambdaExpr outer = assertInstanceOf(Ast.TaskLambdaExpr.class, call.callee());
        assertInstanceOf(Ast.TaskExpr.class, outer.lambda().expressionBody());
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc work_blocking(): int { return 42; }
                        pub fnc main(): void {
                            let Task<int> result = task {
                                return work_blocking();
                            };
                            return;
                        }
                        """));
    }

    @Test
    void suspendsLambdaHasOwnFinallyReturnScope() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            let value = (suspends |int a| -> {
                                try { return a; }
                                catch (err) { return 0; }
                                finally { return 3; }
                            })(5);
                            return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("return is not allowed inside finally"),
                error::getMessage);
    }
}
