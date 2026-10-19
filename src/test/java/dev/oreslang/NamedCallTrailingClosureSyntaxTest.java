package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NamedCallBindingPlan;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-stage contract for named value arguments plus the single final
 * anonymous trailing-closure exception. No executable named-call lowering is
 * claimed here.
 */
final class NamedCallTrailingClosureSyntaxTest {
    private static Ast.Expr firstExpression(String expression) {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    %s;
                    return;
                }
                """.formatted(expression));
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.ExprStmt stmt = assertInstanceOf(Ast.ExprStmt.class, main.body().getFirst());
        return stmt.expression();
    }

    @Test
    void namedArgumentsPreserveLexicalCallItemOrder() {
        Ast.StagedCallExpr call = assertInstanceOf(Ast.StagedCallExpr.class,
                firstExpression("work(second: effect2(), first: effect1())"));
        assertEquals(2, call.arguments().size());
        assertEquals("second", call.arguments().get(0).label());
        assertEquals("first", call.arguments().get(1).label());
        assertFalse(call.arguments().get(0).trailingClosure());
        assertFalse(call.arguments().get(1).trailingClosure());
    }

    @Test
    void finalAnonymousFncAfterNamedArgumentsGetsExplicitOriginMarker() {
        Ast.StagedCallExpr call = assertInstanceOf(Ast.StagedCallExpr.class,
                firstExpression("""
                        group.compute(
                            region: motion,
                            layout: #aosoa,
                            fnc(chunk) { return; }
                        )
                        """));
        assertEquals(3, call.arguments().size());
        Ast.CallArgument callback = call.arguments().get(2);
        assertNull(callback.label());
        assertTrue(callback.trailingClosure());
        assertInstanceOf(Ast.LambdaExpr.class, callback.value());
    }

    @Test
    void parsedComputeShapeBindsOnlyToTheRemainingFinalFncFormal() {
        Ast.StagedCallExpr call = assertInstanceOf(Ast.StagedCallExpr.class,
                firstExpression("""
                        group.compute(
                            layout: #aosoa,
                            region: motion,
                            fnc(chunk) { return; }
                        )
                        """));
        var formals = java.util.List.of(
                new Ast.Param(Ast.TypeRef.simple("Region"), "region"),
                new Ast.Param(Ast.TypeRef.simple("Symbol"), "layout"),
                new Ast.Param(
                        Ast.TypeRef.functionType(
                                java.util.List.of(Ast.TypeRef.simple("Chunk")),
                                Ast.TypeRef.simple("void")),
                        "body"));
        NamedCallBindingPlan.Plan plan =
                NamedCallBindingPlan.bindRequired(formals, call.arguments());
        assertEquals(java.util.List.of(1, 0, 2), plan.formalIndexBySourceItem());
        assertEquals(2, plan.trailingClosureFormalIndex());
    }

    @Test
    void finalPipeClosureUsesTheSameTrailingClosureMarker() {
        Ast.StagedCallExpr call = assertInstanceOf(Ast.StagedCallExpr.class,
                firstExpression("work(value: 3, |chunk| -> { return chunk; })"));
        assertEquals(2, call.arguments().size());
        assertTrue(call.arguments().get(1).trailingClosure());
        assertInstanceOf(Ast.LambdaExpr.class, call.arguments().get(1).value());
    }

    @Test
    void positionalOnlyCallsRetainLegacyCallExpr() {
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class,
                firstExpression("work(1, fnc(chunk) { return chunk; })"));
        assertEquals(2, call.arguments().size());
        assertInstanceOf(Ast.LambdaExpr.class, call.arguments().get(1));
    }

    @Test
    void namedConstructorIsStagedButCannotUseTrailingClosure() {
        assertInstanceOf(Ast.StagedNewExpr.class,
                firstExpression("new Box(value: 1)"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> firstExpression("new Box(value: 1, fnc(chunk) { return; })"));
        assertTrue(failure.getMessage().contains("closure")
                        || failure.getMessage().contains("named"),
                failure::toString);
    }

    @Test
    void rejectsOrdinaryPositionalArgumentAfterNamedArguments() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> firstExpression("work(value: 1, 2)"));
        assertTrue(failure.getMessage().contains("closure"), failure::toString);
    }

    @Test
    void rejectsMoreItemsAfterTheTrailingClosure() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> firstExpression("""
                        work(value: 1,
                             fnc(chunk) { return chunk; },
                             other: 2)
                        """));
        assertTrue(failure.getMessage().contains("final"), failure::toString);
    }

    @Test
    void rejectsNamedSpreadUntilTypedSpreadLoweringExists() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> firstExpression("work(values: ...xs)"));
        assertTrue(failure.getMessage().contains("named spread"), failure::toString);
    }

    @Test
    void duplicateLabelsAreRejectedByTheAstBoundary() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> firstExpression("work(value: 1, value: 2)"));
        assertTrue(failure.getMessage().contains("duplicate"), failure::toString);
    }

    @Test
    void compilerTypeAdmissionFailsClosedBeforeAnyRuntimeFallback() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc work(int value): int { return value; }
                        pub fnc main(): void {
                            work(value: 1);
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("named/trailing-closure"),
                failure::toString);
    }
}
