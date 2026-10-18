package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task/suspends effect boundaries. These source-level assertions deliberately
 * do not certify Task continuation execution: the Task compiler gate remains
 * closed until safe captures, cancellation and heap frames are implemented.
 */
final class TaskSuspendsInteropTest {
    private static Ast.FunctionDecl named(String source, String name) {
        return Parser.parse(source).modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(f -> f.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void suspendsFunctionHasOneDirectResultNotTaskOrFuture() {
        String source = """
                suspends fnc read_value(): int { return 7; }
                pub fnc main(): void {
                    let int value = read_value();
                    return;
                }
                """;
        Ast.FunctionDecl declared = named(source, "read_value");
        assertTrue(declared.suspends());
        assertFalse(declared.async());
        assertEquals("int", declared.returnType().name());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void ordinaryCallableCannotCallSuspendsWithoutDeclaringEffect() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc read_value(): int { return 7; }
                        fnc plain(): int { return read_value(); }
                        """));
        assertTrue(failure.getMessage().contains("suspends"), failure::getMessage);
    }

    @Test
    void suspensionEffectDoesNotSilentlyTransferThroughPlainLambda() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        suspends fnc reader(): int { return 7; }
                        suspends fnc outer(): int {
                            let fn = |int n| -> reader();
                            return fn(1);
                        }
                        """));
        assertTrue(failure.getMessage().contains("suspends"), failure::getMessage);
    }

    @Test
    void taskBodyMaySyntacticallyAwaitAndCallSuspendsWithoutWrappingReturn() {
        String source = """
                suspends fnc read_value(): int { return 7; }
                fnc launch(): void {
                    let Task<int> work = nb task {
                        let int value = read_value();
                        return value;
                    };
                    work.cancel();
                    return;
                }
                """;
        Ast.FunctionDecl outer = named(source, "launch");
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, outer.body().getFirst());
        Ast.TaskExpr task = assertInstanceOf(
                Ast.TaskExpr.class, binding.initializer());
        assertEquals(2, task.body().size());
        assertInstanceOf(Ast.ReturnStmt.class, task.body().get(1));
        IllegalArgumentException gate = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(gate.getMessage().contains("Task blocks"), gate::getMessage);
    }

    @Test
    void allTaskSpellingVariantsAreDistinctFromSuspendsEffect() {
        String source = """
                pub fnc main(): void {
                    let Task<int> a = task { return 1; };
                    let Task<int> b = nb { return 2; };
                    let Task<int> c = nb task { return 3; };
                    do task { await a; };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void suspendsLambdaIsNotAsyncTaskAndRemainsFailClosed() {
        String source = """
                pub fnc main(): void {
                    let int result = (suspends |int value| -> value)(5);
                    return;
                }
                """;
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, named(source, "main").body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class,
                binding.initializer());
        assertInstanceOf(Ast.SuspendsLambdaExpr.class, call.callee());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("suspends lambda"), failure::getMessage);
    }

    @Test
    void contradictoryEffectsDoNotLaunchMoreTasks() {
        for (String source : new String[] {
                "async suspends fnc bad(): int { return 1; }",
                "suspends async fnc bad(): int { return 1; }",
                "pub fnc main(): void { let Task<int> a = suspends task { return 1; }; return; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        }
    }

    @Test
    void namedMainInsideModuleDoesNotInheritRootTaskSuspensionAuthority() {
        String source = """
                define module Utility as
                    async fnc number(): int { return 7; }
                    fnc main(): int {
                        return await number();
                    }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("await requires"), failure::getMessage);
    }

    @Test
    void rootMainRetainsItsExistingVmTaskSuspensionPrivilege() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                async fnc number(): int { return 7; }
                pub fnc main(): void {
                    let int n = await number();
                    return;
                }
                """));
    }

}
