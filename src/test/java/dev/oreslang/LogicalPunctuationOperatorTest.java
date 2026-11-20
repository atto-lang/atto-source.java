package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backed boolean punctuation and canonical integer XOR contract. */
final class LogicalPunctuationOperatorTest {
    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "logical-punctuation.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Do not treat a guest program that merely launches as a passing truth-table fixture. */
    private static int runMainInt(String program) throws Exception {
        Source source = Source.newBuilder(OresLanguage.ID, program, "logical-punctuation-exit.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(source).asInt();
        }
    }

    @Test
    void fullTruthTablesAndPrecedenceCompileAndExecute() throws Exception {
        String source = Files.readString(Path.of("contracts", "logical-punctuation-v1",
                "scalar-parity.atto"), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals(42, runMainInt(source),
                "canonical shared .atto truth-table must return 42, not merely avoid throwing");
        assertEquals("true\n", run("""
                pub fnc main(): void {
                  stdio.println(
                    (false && false) == false &&
                    (false && true) == false &&
                    (true && false) == false &&
                    (true && true) == true &&
                    (false || false) == false &&
                    (false || true) == true &&
                    (true || false) == true &&
                    (true || true) == true &&
                    (0 ^^ 0) == 0 &&
                    (0 ^^ 1) == 1 &&
                    (1 ^^ 0) == 1 &&
                    (1 ^^ 1) == 0 &&
                    (1 ^^ 1 ^^ 1) == 1 &&
                    ((false || true) && ((1 ^^ 0) == 1)) == true);
                  return;
                }
                """));
    }

    @Test
    void parserEncodesPrecedenceAndAssociativityWithoutWordAliases() {
        var tree = new Parser(new Lexer("a | b ^^ c & d").scan()).parseExpression();
        var disjunction = assertInstanceOf(dev.oreslang.ast.Ast.BinaryExpr.class, tree);
        assertEquals("|", disjunction.operator());
        var exclusive = assertInstanceOf(dev.oreslang.ast.Ast.BinaryExpr.class, disjunction.right());
        assertEquals("^^", exclusive.operator());
        assertEquals("&", assertInstanceOf(dev.oreslang.ast.Ast.BinaryExpr.class,
                exclusive.right()).operator());

        var chain = new Parser(new Lexer("a ^^ b ^^ c").scan()).parseExpression();
        var outer = assertInstanceOf(dev.oreslang.ast.Ast.BinaryExpr.class, chain);
        assertEquals("^^", outer.operator());
        assertEquals("^^", assertInstanceOf(dev.oreslang.ast.Ast.BinaryExpr.class,
                outer.left()).operator());
    }

    @Test
    void rhsShortCircuitButXorEvaluatesBothLeftToRight() throws Exception {
        assertEquals("false\ntrue\n", run("""
                pub fnc main(): void {
                  stdio.println(false && [true][99]);
                  stdio.println(true || [false][99]);
                  return;
                }
                """));
        String order = run("""
                fnc lhs(): int { stdio.println("left"); return 6; }
                fnc rhs(): int { stdio.println("right"); return 3; }
                pub fnc main(): void {
                  stdio.println(lhs() ^^ rhs());
                  return;
                }
                """);
        assertEquals("left\nright\n5\n", order);
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run("pub fnc main(): void { stdio.println(0 ^^ [1][99]); return; }"));
        assertTrue(failure.getMessage().contains("99"), failure::getMessage);
    }

    @Test
    void typesMustBeBoolAndSinglePunctuationIsNotBoolean() {
        for (String expression : List.of(
                "1 && true", "false || 1", "true ^^ 1",
                "true | false", "true & false",
                "true | true ^^ false", "true ^^ false")) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "fnc invalid(): bool { return " + expression + "; }")), expression);
        }
        for (String source : List.of(
                "fnc bad(): bool { return true ^ false; }",
                "fnc bad(): bool { return true ^ ^ false; }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
        // Condition-only aliases stay valid (spec ch.16): ',' is && and a bare '|' is ||.
        for (String source : List.of(
                "fnc ok(): void { if true, false then return; fi }",
                "fnc ok(): void { while true, false do break; done }",
                "fnc ok(): void { if true | false then return; fi }")) {
            assertDoesNotThrow(() -> Parser.parse(source), source);
        }
    }

    @Test
    void typeFirstBooleanDeclarationsUseExplicitInequality() throws Exception {
        String sample = """
                pub fnc main(): int {
                  bool a = true;
                  bool b = false;
                  bool and = a && b;
                  bool or = a || b;
                  bool xor = a != b;
                  return (!and && or && xor) ? 42 : 7;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sample)));
        assertEquals(42, runMainInt(sample));
    }

    @Test
    void integerXorIsTypedAndExecutesAsSignedI64BitPatterns() throws Exception {
        String source = """
                pub fnc main(): int {
                  val int first = 6 ^^ 3;
                  val int negative = -1 ^^ 1;
                  val int precedence = 1 | 2 ^^ 3 & 2;
                  if first eq 5 && negative eq -2 && precedence eq 1 then
                    return 42;
                  fi
                  return 7;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals(42, runMainInt(source));
        for (String expression : List.of("true ^^ false", "1 ^^ true", "true ^^ 1", "1.5 ^^ 2")) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                    "fnc invalid(): int { return " + expression + "; }")), expression);
        }
        for (String sourceInvalid : List.of(
                "fnc bad(): int { return 6 ^ 3; }",
                "fnc bad(): int { return 6 ^ ^ 3; }",
                "fnc bad(): int { return 6 ^^^ 3; }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(sourceInvalid), sourceInvalid);
        }
    }

    @Test
    void tokensAreAdjacentAndWordsRemainOrdinaryIdentifiers() {
        List<Token> spaced = new Lexer("true ^ ^ false").scan();
        assertEquals(2, spaced.stream().filter(t -> t.type() == Token.Type.CARET).count());
        List<Token> adjacent = new Lexer("true^^false // and or xor").scan();
        assertEquals(2, adjacent.stream().filter(t -> t.type() == Token.Type.CARET).count());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(bool a, bool b): bool {
                  val and: bool = a && b;
                  val or: bool = a || b;
                  val xor: bool = a != b;
                  return and || or && xor;
                }
                """)));
    }
}
