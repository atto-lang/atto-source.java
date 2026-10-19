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
 * Named value arguments plus the single final anonymous trailing-closure
 * exception. Required-arity calls to statically resolved source functions and
 * methods are executable; constructors, dynamic/imported targets and generic
 * callable inference remain staged/fail-closed.
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
    void cleanupAnalysisTraversesStagedCallBeforeSemanticBinding() {
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
    void capabilityCheckerRequiresTheCompilerBindingProofWhenInvokedDirectly() {
        Ast.Program program = Parser.parse("""
                pub fnc work(int value): int { return value; }
                pub fnc main(): void {
                    work(value: 1);
                    return;
                }
                """);
        IllegalStateException unverified = assertThrows(
                IllegalStateException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(unverified.getMessage().contains("compiler-verified"),
                unverified::toString);

        Ast.Program checked = TypeChecker.checkTypes(program);
        assertDoesNotThrow(
                () -> CapabilityChecker.check(checked, IsolatePolicy.developer()));
    }

    @Test
    void compilerAdmitsRequiredNamedCallAfterStaticBindingProof() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc work(int value): int { return value; }
                pub fnc main(): void {
                    work(value: 1);
                    return;
                }
                """)));
    }

    @Test
    void runtimeEvaluatesNamedArgumentsInSourceOrderThenPermutesAbiSlots() throws Exception {
        String output = run("""
                pub fnc mark(String value): String {
                    stdio.stdout.write(value);
                    return value;
                }

                pub fnc join(String first, String second): String {
                    return first + second;
                }

                pub routine main(): void {
                    val String joined = join(
                        second: mark("2"),
                        first: mark("1")
                    );
                    stdio.stdout.write("|");
                    stdio.stdout.write(joined);
                    return;
                }
                """);
        assertEquals("21|12", output);
    }

    @Test
    void runtimeBindsFinalAnonymousFncOnlyToFinalCallableFormal() throws Exception {
        String output = run("""
                pub fnc apply(int value, Fnc<int, int> body): int {
                    return body(value);
                }

                pub routine main(): void {
                    stdio.stdout.write(apply(
                        value: 3,
                        fnc(chunk) { return chunk + 4; }
                    ));
                    return;
                }
                """);
        assertEquals("7", output);
    }

    @Test
    void asyncNamedCallUsesTheResumablePathAndKeepsFormalBinding() throws Exception {
        String output = run("""
                pub async fnc subtract(int left, int right): int {
                    return left - right;
                }

                pub routine main(): void {
                    stdio.stdout.write(await subtract(right: 2, left: 9));
                    return;
                }
                """);
        assertEquals("7", output);
    }

    @Test
    void moduleStaticAndInstanceCallsUseTheSameCheckedPermutation() throws Exception {
        String output = run("""
                define module math
                  pub fnc sub(int left, int right): int {
                    return left - right;
                  }

                  pub define class C as
                    pub static fnc ssub(int left, int right): int {
                      return left - right;
                    }

                    pub sub(int left, int right): int {
                      return left - right;
                    }
                  end
                end

                pub routine main(): void {
                    val c = new math.C();
                    stdio.stdout.write(math.sub(right: 2, left: 7));
                    stdio.stdout.write("|");
                    stdio.stdout.write(math.C.ssub(right: 3, left: 8));
                    stdio.stdout.write("|");
                    stdio.stdout.write(c.sub(right: 4, left: 9));
                    return;
                }
                """);
        assertEquals("5|5|5", output);
    }

    @Test
    void closureCaptureAnalysisSeesMoveOnlyValuesInsideNamedArguments() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        fnc consume(Box box): void {
                          return;
                        }

                        pub fnc main(): void {
                          let box = new Box();
                          val Fnc<void> callback = fnc() {
                            consume(box: box);
                            return;
                          };
                          stdio.stdout.write(box.value);
                          return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("box")
                        || failure.getMessage().contains("capture")
                        || failure.getMessage().contains("moved"),
                failure::toString);
    }

    @Test
    void instanceNamedCallProofSurvivesValidDynamicOverrideDispatch() throws Exception {
        String output = run("""
                define class Base as
                  pub sub(int left, int right): int {
                    return left - right;
                  }
                end

                define class Child extends Base as
                  pub sub(int left, int right): int {
                    return left - right + 10;
                  }
                end

                fnc invoke(Base value): int {
                  return value.sub(right: 2, left: 7);
                }

                pub routine main(): void {
                  val child = new Child();
                  stdio.stdout.write(invoke(child));
                  return;
                }
                """);
        assertEquals("15", output);
    }

    @Test
    void genericAndDynamicNamedCallsRemainFailClosed() {
        IllegalArgumentException generic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc identity<T>(T value): T { return value; }
                        pub fnc main(): void {
                            identity(value: 1);
                            return;
                        }
                        """)));
        assertTrue(generic.getMessage().contains("generic"), generic::toString);

        IllegalArgumentException dynamic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                            val Fnc<int, int> f = fnc(int x) { return x; };
                            f(x: 1);
                            return;
                        }
                        """)));
        assertTrue(dynamic.getMessage().contains("statically resolved"),
                dynamic::toString);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "named-calls.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
