package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Lambda return type and comma-body parsing remains distinct from pipeline arity. */
final class LambdaCommaPipelineGuestTest {
    private static String execute(String guest) throws Exception {
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "lambda-comma-pipe.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    @Test
    void typedInlineReturnAnnotationCannotBeConfusedWithValueBody() {
        Ast.Program program = Parser.parse("""
                fnc main(): void {
                    val typed = || => int -> 5 + 3;
                    val inferred = || -> 5 + 3;
                    val typed_fnc = fnc(x: int) => int -> x + 1;
                    return;
                }
                """);
        Ast.FunctionDecl main = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.LambdaExpr typed = (Ast.LambdaExpr)
                ((Ast.BindingStmt) main.body().get(0)).initializer();
        Ast.LambdaExpr inferred = (Ast.LambdaExpr)
                ((Ast.BindingStmt) main.body().get(1)).initializer();
        Ast.LambdaExpr anonymous = (Ast.LambdaExpr)
                ((Ast.BindingStmt) main.body().get(2)).initializer();
        assertEquals("int", typed.declaredReturnType().name());
        assertNotNull(typed.expressionBody());
        assertNull(inferred.declaredReturnType());
        assertEquals("int", anonymous.declaredReturnType().name());
        assertEquals(1, anonymous.parameters().size());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void typedInlineAnnotationAliasAndReversedOrderAreDistinguished() {
        Ast.Program parsed = Parser.parse("""
                fnc main(): void {
                    val compact = || => int -> 5;
                    val grouped = || (=> int) -> 5;
                    val with_param = |n: int| (=> int) -> n + 1;
                    return;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
        for (String invalid : new String[] {
                "|| -> int => 5",
                "|| -> void => stdio.println(5)",
                "|n: int| -> int => n + 1",
                "|| => int 5"
        }) {
            String source = "fnc main(): void { val callback = " + invalid + "; return; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), invalid);
        }
    }

    @Test
    void bothTypedAndInferredSameLineCommaLocalsExecute() throws Exception {
        assertEquals("8:8:42", execute("""
                pub fnc main(): void {
                    val inferred = || -> val int x = 5, x + 3;
                    val explicit = || => int -> val int x = 5, x + 3;
                    val scoped = || -> int ::
                        int x = 40,
                        x + 2
                    end;
                    stdio.stdout.write(inferred());
                    stdio.stdout.write(":");
                    stdio.stdout.write(explicit());
                    stdio.stdout.write(":");
                    stdio.stdout.write(scoped());
                    return;
                }
                """));
    }

    @Test
    void voidBraceCommaAndInlineLambdaBodiesHandleTuplePipelines() throws Exception {
        assertEquals("11:11:11", execute("""
                fnc foo(int n): Tuple[int, int] {
                    return tuple(n, n + 1);
                }
                pub fnc main(): void {
                    val with_return = || -> void {
                        return 5 |> foo |> |x, y| -> stdio.stdout.write(x + y);
                    };
                    val comma = || -> void ::
                        5 |> foo |> |x, y| -> stdio.stdout.write(x + y)
                    end;
                    val inline = || => void -> 5 |> foo |> |x, y| -> stdio.stdout.write(x + y);
                    with_return();
                    stdio.stdout.write(":");
                    comma();
                    stdio.stdout.write(":");
                    inline();
                    return;
                }
                """));
    }

    @Test
    void originalTwoArgumentStdioPipelineExampleChecksAllLambdaBodyForms() throws Exception {
        String guest = """
                fnc foo(int n): Tuple[int, int] { return tuple(n, n + 1); }
                pub fnc main(): void {
                    val braces = || -> void {
                        return 5 |> foo |> |x, y| -> stdio.println(x, y);
                    };
                    val comma = || -> void ::
                        5 |> foo |> |x, y| -> stdio.println(x, y)
                    end;
                    val inline = || => void ->
                        5 |> foo |> |x, y| -> stdio.println(x, y);
                    braces();
                    comma();
                    inline();
                    return;
                }
                """;
        String output = execute(guest);
        assertEquals(3L, output.lines().count(), "each lambda must run once");
        for (String line : output.lines().toList()) {
            assertTrue(line.contains("5") && line.contains("6"),
                    "tuple destructuring lost a printed operand: " + line);
        }
    }

    @Test
    void typedInlineCommaLambdaAsPipelineStagePreservesOuterPipeline() throws Exception {
        assertEquals("11:42", execute("""
                fnc increment(int n): int { return n + 1; }
                pub fnc main(): void {
                    stdio.stdout.write(4
                        |> |n| => int -> val int x = n + 1, x * 2
                        |> increment);
                    stdio.stdout.write(":");
                    stdio.stdout.write(20
                        |> |n| -> int :: int x = n + 1, x * 2 end);
                    return;
                }
                """));
    }

    @Test
    void typedLambdaReturnTypesAreEnforcedInAllBodyStyles() {
        for (String lambda : new String[] {
                "|| => int -> true",
                "|| => bool -> 5",
                "|| => int -> val int x = 5, true",
                "|| -> int :: bool value = true, value end",
                "|n| => bool -> n + 1"
        }) {
            String guest = lambda.startsWith("|n|")
                    ? "fnc main(): int { return 5 |> " + lambda + "; }"
                    : "fnc main(): void { val callback = " + lambda + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), lambda);
        }
    }

    @Test
    void twoParameterPipelineLambdaRequiresTypedTupleNotScalar() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc main(): int {
                    return 5 |> |x, y| => int -> x + y;
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc main(): int {
                    return tuple(5, 7) |> |x, y| => int -> x + y;
                }
                """)));
    }

    @Test
    void independentInlineLambdaLocalScopesDoNotLeak() throws Exception {
        assertEquals("16", execute("""
                pub fnc main(): void {
                    val first = || -> val int x = 5, x + 3;
                    val second = || -> val int x = 10, x - 2;
                    stdio.stdout.write(first() + second());
                    return;
                }
                """));
    }

    @Test
    void typedCommaLambdasRejectMissingReturnAndWrongVoidResult() {
        for (String invalid : new String[] {
                "|| -> int :: int x = 5 end",
                "|| -> int :: end",
                "|| -> void :: 5 end",
                "|| -> bool :: int x = 5, x + 3 end",
                "|| => void -> 5",
                "|| => int -> stdio.println(5)"
        }) {
            String guest = "fnc main(): void { val callback = " + invalid
                    + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), invalid);
        }
    }

    @Test
    void inlineCommaLocalsCannotMutateReadonlyOrReferenceFutureBinding() {
        for (String invalid : new String[] {
                "|| -> val int x = 5, x = 7",
                "|| => int -> val int x = y, val int y = 5, x",
                "|| -> val int x = 5, val int x = 6, x"
        }) {
            String guest = "fnc main(): void { val callback = " + invalid
                    + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), invalid);
        }
    }

    @Test
    void nonLexicalLambdaCannotCaptureOuterLocalViaTypedInlineResult() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc main(): int {
                            val int secret = 41;
                            val callback = nlex || => int -> secret + 1;
                            return callback();
                        }
                        """)));
    }

    @Test
    void typedCommaLambdaReturnCanPipelineThroughNamedCallable() throws Exception {
        assertEquals("42", execute("""
                fnc add_one(int n): int { return n + 1; }
                pub fnc main(): void {
                    val callback = || -> int ::
                        int x = 20,
                        x |> add_one |> |n| => int -> n * 2
                    end;
                    stdio.stdout.write(callback());
                    return;
                }
                """));
    }

    @Test
    void nestedArgumentCommasRemainArgumentsAndMalformedCommaBodiesFail() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc sum(int a, int b): int { return a + b; }
                fnc main(): int {
                    val callback = || -> val int x = sum(5, 7), x + 1;
                    return callback();
                }
                """)));
        for (String lambda : new String[] {
                "|| => int -> val int x = 5,",
                "|| => int -> val int x = 5,, x",
                "|| -> int :: int x = 5, end",
                "|| -> int :: , 5 end",
                "|| -> int",
                "|| -> void 5"
        }) {
            String source = "fnc main(): void { val callback = " + lambda
                    + "; return; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), lambda);
        }
    }
}
