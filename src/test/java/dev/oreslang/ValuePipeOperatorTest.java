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
    void acceptsAndExecutesExactly128ForwardedStages() throws Exception {
        String program = "fnc increment(int n): int { return n + 1; } "
                + "pub fnc main(): void { stdio.println(1"
                + " |> increment".repeat(128) + "); return; }";
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipe-128.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("129", output.toString(StandardCharsets.UTF_8).trim());
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
    void executesInputBeforeStageArgumentsAndCallsOnlyActiveVariant() throws Exception {
        String program = """
                fnc input(): int {
                  stdio.println("L");
                  return 2;
                }
                fnc argument(): int {
                  stdio.println("R");
                  return 3;
                }
                fnc plus(int first, int second): int { return first + second; }
                fnc notify(int n): int {
                  stdio.println("HIT");
                  return n * 2;
                }
                fnc format_error(String s): String {
                  stdio.println("E");
                  return s + "!";
                }
                pub fnc main(): void {
                  stdio.println(input() |> plus(argument()));
                  stdio.println(None |> Option.map(notify));
                  stdio.println(Some(4) |> Option.map(notify));
                  stdio.println(Err("bad") |> Result.map(notify));
                  stdio.println(Ok(2) |> Result.map(notify));
                  stdio.println(Ok(4) |> Result.map_err(format_error));
                  stdio.println(Err("bad") |> Result.map_err(format_error));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipe-order.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals(java.util.List.of(
                "L", "R", "5", "None", "HIT", "Some(8)",
                "Err(bad)", "HIT", "Ok(4)", "Ok(4)", "E", "Err(bad!)"),
                output.toString(StandardCharsets.UTF_8).lines().toList());
    }

    @Test
    void pipelineDoesNotResurrectMovedOptionPayloads() {
        IllegalArgumentException moved = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    define class Box as
                    end
                    fnc consume(Option<Box> value): int { return 1; }
                    fnc bad(): int {
                      val Option<Box> owned = None;
                      val int first = owned |> consume;
                      return owned |> consume;
                    }
                    """)));
        assertTrue(moved.getMessage().contains("moved value"), moved::toString);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                    fnc consume(Option<int> value): int { return 1; }
                    fnc good(): int {
                      val Option<int> copyable = None;
                      val int first = copyable |> consume;
                      return copyable |> consume;
                    }
                    """)));
    }

    @Test
    void infersInlineSumLambdaInputsWithoutLosingCallbackReturnTypes() throws Exception {
        String program = """
                fnc expect_string(Option<String> value): void { return; }
                pub fnc main(): void {
                  stdio.println(Some(3) |> Option.map(|n| -> n * 2) |> Option.unwrap_or(0));
                  stdio.println(Err("bad") |> Result.map_err(|e| -> e + "!"));
                  stdio.println(None |> Option.ok_or_else(|| -> "missing"));
                  stdio.println(Some(3) |> Option.filter(|n| -> n > 0) |> Option.unwrap_or(0));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipe-lambda.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals(java.util.List.of("6", "Err(bad!)", "Err(missing)", "3"),
                output.toString(StandardCharsets.UTF_8).lines().toList());
    }

    @Test
    void rejectsBadInferredInlineSumLambdaResults() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Option<int> input): Option<String> {
                  return input |> Option.map(|n| -> n + 1);
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Option<int> input): Option<int> {
                  return input |> Option.filter(|n| -> n + 1);
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Option<int> input): Option<int> {
                  return input |> Option.map(|n| -> { return n + 1; });
                }
                """)));
    }

    @Test
    void preservesErrorAndSuccessDomainsAcrossInferredResultCallbacks() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc annotate(String s): String { return s + "!"; }
                fnc double(int n): int { return n * 2; }
                fnc correct(Result<int,String> input): Result<int,String> {
                  val Result<int,String> first = input
                      |> Result.and_then(|n| -> Ok(n + 1))
                      |> Result.map_err(annotate);
                  return first
                      |> Result.or_else(|e| -> Err(e + "!"))
                      |> Result.map(double);
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(int n): int { return n + 1; }
                fnc bad(Result<int,String> input): Result<int,int> {
                  return input
                      |> Result.and_then(|n| -> Ok(n + 1))
                      |> Result.map_err(wrong);
                }
                """)), "and_then must preserve String error type, not Unknown");
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(String s): String { return s + "!"; }
                fnc bad(Result<int,String> input): Result<String,String> {
                  return input
                      |> Result.or_else(|e| -> Err(e + "!"))
                      |> Result.map(wrong);
                }
                """)), "or_else must preserve int success type, not Unknown");
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
    @Test
    void rejectsAwaitInSynchronousSumCallbacksEvenInsideAsyncCallers() {
        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    async fnc answer(): int { return 7; }
                    async fnc broken(Option<int> value): Option<int> {
                      return value |> Option.map(|n| -> await answer());
                    }
                    """)));
        assertTrue(direct.getMessage().contains("cannot suspend or await"), direct::toString);

        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    async fnc answer(): int { return 7; }
                    async fnc broken(Result<int,String> value): Result<int,String> {
                      return value |> Result.map(|n| -> n + (await answer()));
                    }
                    """)));
        assertTrue(nested.getMessage().contains("cannot suspend or await"), nested::toString);
    }

    @Test
    void rejectsNestedBorrowEscapingThroughMappedStruct() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    define class Box as
                    end
                    fnc broken(Option<Box> input): void {
                      val nested = input |> Option.map(|item| -> infer struct{view: rt borrow item});
                      return;
                    }
                    """)));
        assertTrue(failure.getMessage().contains("cannot publish a borrowed callback result"),
                failure::toString);
    }

    @Test
    void rejectsSuspendingNamedCallbackAndItsFirstClassAlias() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                suspends fnc step(int n): int { return n + 1; }
                suspends fnc valid(int n): int { return step(n); }
                """)));

        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    suspends fnc step(int n): int { return n + 1; }
                    fnc broken(Option<int> value): Option<int> {
                      return value |> Option.map(step);
                    }
                    """)));
        assertTrue(direct.getMessage().contains("first-class callable"), direct::toString);

        IllegalArgumentException aliased = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    suspends fnc step(int n): int { return n + 1; }
                    fnc broken(Result<int,String> value): Result<int,String> {
                      val local_callback = step;
                      return value |> Result.map(local_callback);
                    }
                    """)));
        assertTrue(aliased.getMessage().contains("first-class callable"), aliased::toString);
    }

    @Test
    void rejectsUnknownNestedSumCallbackOutputs() {
        // Unknown inside Option/Result is not a sound inferred result type.
        // and_then and or_else may leave only the *unchanged* Result branch
        // unknown: the newly-produced branch still needs a concrete type.
        String[] programs = {
                "fnc broken(Option<int> value): void { "
                    + "val nested = value |> Option.map(|n| -> None); return; }",
                "fnc broken(Option<int> value): void { "
                    + "val nested = value |> Option.and_then(|n| -> None); return; }",
                "fnc broken(Result<int,String> value): void { "
                    + "val nested = value |> Result.map(|n| -> None); return; }",
                "fnc broken(Result<int,String> value): void { "
                    + "val nested = value |> Result.and_then(|n| -> Ok(None)); return; }",
                "fnc broken(Result<int,String> value): void { "
                    + "val nested = value |> Result.or_else(|e| -> Err(None)); return; }"
        };
        for (String source : programs) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(failure.getMessage().contains("callback result cannot be inferred safely"),
                    failure::toString);
        }

        // Bare Ok and Err constructors have a deliberately unbound invariant
        // branch. Preserve these valid callbacks by reconstructing from the
        // typed Result receiver.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc valid(Result<int,String> value): Result<int,String> {
                  return value
                      |> Result.and_then(|n| -> Ok(n + 1))
                      |> Result.or_else(|e| -> Err(e + "!"));
                }
                """)));
    }

}
