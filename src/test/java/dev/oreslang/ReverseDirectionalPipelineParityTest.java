package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Exact target direction: <| is value application, rpipe is a continuous
 * right-to-left connection; << / >> never create a stream pump.
 *
 * Scalar tests execute genuine Java/Graal guest code. Continuous pipe/rpipe
 * tests are required compiler rejections, not runtime transport evidence.
 */
final class ReverseDirectionalPipelineParityTest {
    @Test
    void tokenizesReverseValueWithoutBreakingComparisonsAndLambdaDelimiters() {
        var tokens = new Lexer("double <| plus <| 18; a < b; |x| -> x;").scan();
        assertEquals(2, tokens.stream().filter(t ->
                t.type() == Token.Type.PIPE_REVERSE).count());
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.LT));
        assertEquals(2, tokens.stream().filter(t -> t.type() == Token.Type.PIPE).count());
    }

    @Test
    void executesReverseAndForwardWithTheSame42AndEffects() throws Exception {
        var program = """
                fnc add_three(int x): int {
                    stdio.stdout.write("A");
                    return x + 3;
                }
                fnc twice(int x): int {
                    stdio.stdout.write("B");
                    return x * 2;
                }
                pub routine main(): void {
                    stdio.stdout.write(twice <| add_three <| 18);
                    stdio.stdout.write("|");
                    stdio.stdout.write(18 |> add_three |> twice);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("AB42|AB42", run(program));
    }

    @Test
    void reverseStageMayProvideExtraArgumentButSourceIsFirst() throws Exception {
        String program = """
                fnc add(int source, int extra): int { return source + extra; }
                pub routine main(): void {
                    stdio.stdout.write(add(2) <| 40);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("42",run(program));
    }

    @Test
    void reversePipelineReusesExistingCheckedCallAstAndTypeChecker() {
        Ast.Program program = Parser.parse("""
                fnc plus_three(int x): int { return x + 3; }
                fnc twice(int x): int { return x * 2; }
                fnc main(): int { return twice <| plus_three <| 18; }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().get(2);
        Ast.CallExpr outer = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.ReturnStmt) fn.body().getFirst()).value());
        assertEquals("twice", ((Ast.NameExpr) outer.callee()).name());
        Ast.CallExpr inner = assertInstanceOf(Ast.CallExpr.class,
                outer.arguments().getFirst());
        assertEquals("plus_three", ((Ast.NameExpr) inner.callee()).name());
    }

    @Test
    void rejectsMixedFunctionalDirectionAndNonCallableStages() {
        for (String expression : new String[]{
                "2 |> twice <| 18",
                "twice <| 18 |> twice",
                "42 <| 2",
                "twice <| ;"
        }) {
            var source = """
                fnc twice(int n): int { return n * 2; }
                fnc main(): int { return %s; }
                """.formatted(expression);
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source),
                    expression);
        }
    }

    @Test
    void rejectsContinuousRpipeAndInlineStreamUntilCheckedRuntimeExists() {
        String source = """
            fnc main(): int {
                return 42 rpipe 41;
            }
            """;
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(exception.getMessage().contains("E_PIPE_BACKEND_UNSUPPORTED"),
                exception::toString);
        assertTrue(exception.getMessage().contains("right-to-left"),
                exception::toString);

        // A declaration-first Stream<T> capture must never become an
        // ordinary scalar expression or an implicit write when no PipeHandle
        // ownership, cancellation and supervisor errors have been lowered.
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): int {
                    (let results: Stream<int> = Stream.buffered<int>(64))
                        rpipe transform rpipe readable;
                    return 0;
                }
                """));
    }

    @Test
    void reverseResultAndOptionCombinatorsPreserveTaggedValuesAndErrors() throws Exception {
        String program = """
                fnc twice(int n): int { return n * 2; }
                fnc annotate(String error): String { return error + "!"; }
                pub routine main(): void {
                    stdio.println(Option.map(twice) <| Some(3));
                    stdio.println(Option.map(twice) <| None);
                    stdio.println(Result.map(twice) <| Ok(2));
                    stdio.println(Result.map(twice) <| Err("bad"));
                    stdio.println(Result.map_err(annotate) <| Err("bad"));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("Some(6)\nNone\nOk(4)\nErr(bad)\nErr(bad!)",
                run(program).trim());
    }

    @Test
    void reverseResultAndThenCannotChangeCheckedErrorDomain() {
        String program = """
                fnc bad_next(int n): Result<int,int> { return Err(5); }
                fnc wrong(Result<int,String> input): Result<int,String> {
                    return Result.and_then(bad_next) <| input;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void functionalReversePreservesTypeErrorsRatherThanImplicitResultFlattening() {
        var source = """
                fnc consume(int value): int { return value + 1; }
                fnc checked(): Result<int,String> { return Err("bad"); }
                fnc main(): int { return consume <| checked(); }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID,code,"reverse-pipe-parity.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
