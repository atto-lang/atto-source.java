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
    void executesOptionAndResultCombinators() throws Exception {
        String program = """
                fnc double(int n): int { return n * 2; }
                fnc fail(String s): String { return s + "!"; }
                pub fnc main(): void {
                  stdio.println(3 |> double);
                  stdio.println(Some(4) |> Option.map(double) |> Option.unwrap_or(0));
                  stdio.println(Err("bad") |> Result.map_err(fail));
                  stdio.println(Ok(9) |> Result.map(double) |> Result.unwrap_or(0));
                  stdio.println(None |> Option.ok_or_else(|| -> "absent"));
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
    }
}
