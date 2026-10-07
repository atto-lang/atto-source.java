package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for right-hand-side lambda declarations and nlex boundaries. */
final class RhsLambdaScopeTest {
    private static void check(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(program);
    }

    @Test
    void typedBlockLambdasPreserveDeclaredReturnType() {
        Ast.Program program = Parser.parse("""
                pub fnc example(): void {
                    const create = || -> int {
                        return 1;
                    };
                    const nested = |int value| -> Tuple[int, int] {
                        return tuple (value, value);
                    };
                    return;
                }
                """);
        Ast.FunctionDecl function =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.LambdaExpr create =
                (Ast.LambdaExpr) ((Ast.BindingStmt) function.body().get(0)).initializer();
        Ast.LambdaExpr nested =
                (Ast.LambdaExpr) ((Ast.BindingStmt) function.body().get(1)).initializer();
        assertEquals("int", create.returnType().name());
        assertEquals("Tuple", nested.returnType().name());
        assertEquals(2, nested.returnType().arguments().size());
    }

    @Test
    void ordinaryLambdaCanCaptureLocalsWithinNlexCallable() {
        assertDoesNotThrow(() -> check("""
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const f = || -> int {
                        return xyz;
                    };
                    return f();
                }
                """));
    }

    @Test
    void nestedExplicitNlexLambdaCannotCaptureContainingLocal() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub nlex fnc foo(): int {
                    const xyz = 5;
                    const f = nlex || -> int {
                        return xyz;
                    };
                    return f();
                }
                """));
    }

    @Test
    void typedReturnMustMatchAndExplicitlyReturnFromBlock() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc example(): void {
                    const f = || -> int {
                        return "not an int";
                    };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                pub fnc example(): void {
                    const f = || -> int {
                        const v = 1;
                    };
                }
                """));
    }

    @Test
    void expressionBodiedLambdasRemainUnchanged() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub fnc example(): void {
                    const f = |value| -> value + 1;
                    const g = |value| ->
                        value + 2;
                }
                """));
    }
}
