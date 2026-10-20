package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.NamedCallBindingPlan;
import dev.oreslang.types.ReturnCleanupChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-stage contract for named value arguments plus the single final
 * anonymous trailing-closure exception plus the first executable required-call
 * lowering for statically resolved direct/module functions.
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
    void cleanupAnalysisTraversesSupportedStagedCallInsteadOfSkippingIt() {
        Ast.Program program = Parser.parse("""
                pub fnc work(int value): int { return value; }
                pub fnc main(): void {
                    work(value: 1);
                    return;
                }
                """);
        assertDoesNotThrow(() -> ReturnCleanupChecker.check(program));
    }

    @Test
    void capabilityCheckerRequiresATypeCheckedNamedCallPlan() {
        Ast.Program parsed = Parser.parse("""
                pub fnc work(int value): int { return value; }
                pub fnc main(): void {
                    work(value: 1);
                    return;
                }
                """);
        assertThrows(
                IllegalStateException.class,
                () -> CapabilityChecker.check(parsed, IsolatePolicy.developer()));

        Ast.Program checked = TypeChecker.checkTypes(parsed);
        assertDoesNotThrow(
                () -> CapabilityChecker.check(checked, IsolatePolicy.developer()));
    }

    @Test
    void requiredNamedDirectFunctionIsExecutableButDynamicMemberRemainsClosed() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc work(int value): int { return value; }
                pub fnc main(): void {
                    work(value: 1);
                    return;
                }
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                            pub run(int value): int { return value; }
                        end
                        pub fnc main(): void {
                            val Box b = new Box();
                            b.run(value: 1);
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("direct or module function")
                        || failure.getMessage().contains("instance/static"),
                failure::toString);
    }

    @Test
    void capabilityChecksTheSelectedOverloadByNameAndArity() {
        Ast.Program program = TypeChecker.checkTypes(Parser.parse("""
                pub fnc work(): int {
                    return 0;
                }
                pub fnc work(int value): int {
                    val id = process.context_id;
                    return value;
                }
                pub fnc main(): void {
                    work(value: 1);
                    return;
                }
                """));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.untrustedActor()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO")
                        || denied.getMessage().contains("process.context_id"),
                denied::toString);
    }

    @Test
    void runtimeEvaluatesNamedArgumentsInSourceOrderThenPermutesValues() throws Exception {
        assertEquals("BA12", run("""
                pub fnc mark(string label, int value): int {
                    stdio.stdout.write(label);
                    return value;
                }
                pub fnc combine(int first, int second): int {
                    return first * 10 + second;
                }
                pub routine main(): void {
                    val int result = combine(
                        second: mark("B", 2),
                        first: mark("A", 1)
                    );
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    @Test
    void runtimeBindsFinalAnonymousClosureOnlyAfterNamedValues() throws Exception {
        assertEquals("4", run("""
                pub fnc apply(int value, Fnc<int, int> body): int {
                    return body(value);
                }
                pub routine main(): void {
                    val int result = apply(
                        value: 3,
                        fnc(int x) { return x + 1; }
                    );
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "named-call.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

}
