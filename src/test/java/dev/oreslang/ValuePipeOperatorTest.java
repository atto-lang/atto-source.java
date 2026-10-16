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

final class ValuePipeOperatorTest {
    @Test
    void tokenizesPipeWithoutBreakingLambdaOrBitwiseOr() {
        var tokens = new Lexer("a |> f | b || c").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.PIPE_FORWARD));
        assertEquals(3, tokens.stream().filter(t -> t.type() == Token.Type.PIPE).count());
    }

    @Test
    void lowersLeftAssociativeNamedFunctionCalls() {
        Ast.Program program = Parser.parse("""
                fnc double(int n): int { return n * 2; }
                fnc plus(int n, int k): int { return n + k; }
                fnc main(): int { return 3 |> double |> plus(1); }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(2);
        Ast.ReturnStmt ret = (Ast.ReturnStmt) fn.body().getFirst();
        Ast.CallExpr outer = assertInstanceOf(Ast.CallExpr.class, ret.value());
        assertEquals("plus", ((Ast.NameExpr) outer.callee()).name());
        assertEquals(2, outer.arguments().size());
        assertInstanceOf(Ast.CallExpr.class, outer.arguments().getFirst());
    }

    @Test
    void rejectsNonCallableStages() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): int { return 1 |> 2; }
                """));
    }

    @Test
    void rejectsMapErrOnOptionAtTypeCheck() {
        Ast.Program bad = Parser.parse("""
                fnc f(int n): int { return n + 1; }
                fnc bad(Option<int> value): Option<int> {
                  return value |> Result.map_err(f);
                }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(bad));
        assertTrue(error.getMessage().contains("unknown Option member"), error::toString);
    }

    @Test
    void preservesLeftFirstArgumentOrderAndRejectsDynamicQualifiedStages() {
        Ast.Program program = Parser.parse("""
                fnc make_input(): int { return 1; }
                fnc make_argument(): int { return 2; }
                fnc combine(int left, int right): int { return left + right; }
                fnc main(): int { return make_input() |> combine(make_argument()); }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().get(3);
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.ReturnStmt) fn.body().getFirst()).value());
        assertEquals("make_input",
                ((Ast.NameExpr) ((Ast.CallExpr) call.arguments().getFirst()).callee()).name());
        assertEquals("make_argument",
                ((Ast.NameExpr) ((Ast.CallExpr) call.arguments().get(1)).callee()).name());
        assertDoesNotThrow(() -> TypeChecker.check(program));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc main(): int { return 1 |> object.method(2); }
                """));
    }

    @Test
    void rejectsFirstClassCombinatorExtractionWithUninferredGenerics() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc bad(Option<int> input): void {
                      val callback = input.map;
                      return;
                    }
                    """)));
        assertTrue(failure.getMessage().contains("first-class extraction"),
                failure::toString);
    }

    @Test
    void rejectsPipelineAboveBoundedStageLimit() {
        String input = "fnc plus(int n): int { return n + 1; } "
                + "fnc main(): int { return 1" + " |> plus".repeat(129) + "; }";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(input));
        assertTrue(failure.getMessage().contains("pipeline exceeds 128 stages"),
                failure::toString);
    }

    @Test
    void rejectsErrorTypeMutationByAndThen() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc badNext(int n): Result<int,int> { return Err(5); }
                fnc broken(Result<int,String> input): Result<int,String> {
                  return input |> Result.and_then(badNext);
                }
                """)));
        assertTrue(failure.getMessage().contains("invariant"), failure::toString);
    }

    @Test
    void rejectsNonBooleanFilterAndNonOptionFlatMap() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(int n): int { return n + 1; }
                fnc broken(Option<int> input): Option<int> {
                  return input |> Option.filter(invalid);
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(int n): int { return n + 1; }
                fnc broken(Option<int> input): Option<int> {
                  return input |> Option.and_then(invalid);
                }
                """)));
    }

    @Test
    void executesOptionAndResultCombinators() throws Exception {
        String program = """
                fnc double(int n): int { return n * 2; }
                fnc fail(String s): String { return s + "!"; }
                fnc maybe(int n): Option<int> { return Some(n + 1); }
                fnc checked(int n): Result<int, String> { return Ok(n + 1); }
                fnc recover_error(String s): Result<int, String> { return Ok(12); }
                fnc positive(int n): bool { return n > 0; }
                pub fnc main(): void {
                  stdio.println(3 |> double);
                  stdio.println(Some(4) |> Option.map(double) |> Option.unwrap_or(0));
                  stdio.println(Err("bad") |> Result.map_err(fail));
                  stdio.println(Ok(9) |> Result.map(double) |> Result.unwrap_or(0));
                  stdio.println(None |> Option.ok_or_else(|| -> "absent"));
                  stdio.println(Some(7) |> Option.and_then(maybe) |> Option.unwrap_or(0));
                  stdio.println(Some(-3) |> Option.filter(positive) |> Option.unwrap_or(99));
                  stdio.println(Ok(20) |> Result.and_then(checked) |> Result.unwrap_or(0));
                  stdio.println(Err("oops") |> Result.or_else(recover_error) |> Result.unwrap_or(0));
                  stdio.println(Some(Ok(42)) |> Option.transpose());
                  stdio.println(Ok(33) |> Result.ok() |> Option.unwrap_or(0));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipe.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        String actual = output.toString(StandardCharsets.UTF_8);
        assertTrue(actual.contains("6"), actual);
        assertTrue(actual.contains("8"), actual);
        assertTrue(actual.contains("18"), actual);
        assertTrue(actual.contains("bad!"), actual);
        assertTrue(actual.contains("absent"), actual);
        assertTrue(actual.contains("99"), actual);
        assertTrue(actual.contains("12"), actual);
        assertTrue(actual.contains("21"), actual);
        assertTrue(actual.contains("Ok(Some(42))"), actual);
        assertTrue(actual.contains("33"), actual);
    }
}
