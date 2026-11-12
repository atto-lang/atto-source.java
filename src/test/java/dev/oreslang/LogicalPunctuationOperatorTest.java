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

/** Compiler-backed boolean punctuation contract: no word/single-pipe aliases. */
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

    @Test
    void fullTruthTablesAndPrecedenceCompileAndExecute() throws Exception {
        String source = Files.readString(Path.of("contracts", "logical-punctuation-v1",
                "scalar-parity.atto"), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> run(source));
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
                    (false ^^ false) == false &&
                    (false ^^ true) == true &&
                    (true ^^ false) == true &&
                    (true ^^ true) == false &&
                    (true ^^ true ^^ true) == true &&
                    (false || true ^^ true && false) == true);
                  return;
                }
                """));
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
                fnc lhs(): bool { stdio.println("left"); return true; }
                fnc rhs(): bool { stdio.println("right"); return false; }
                pub fnc main(): void {
                  stdio.println(lhs() ^^ rhs());
                  return;
                }
                """);
        assertEquals("left\nright\ntrue\n", order);
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run("pub fnc main(): void { stdio.println(false ^^ [true][99]); return; }"));
        assertTrue(failure.getMessage().contains("99"), failure::getMessage);
    }

    @Test
    void typesMustBeBoolAndSinglePunctuationIsNotBoolean() {
        for (String expression : List.of(
                "1 && true", "false || 1", "true ^^ 1",
                "true | false", "true & false",
                "true | true ^^ false")) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "fnc invalid(): bool { return " + expression + "; }")), expression);
        }
        for (String source : List.of(
                "fnc bad(): bool { return true ^ false; }",
                "fnc bad(): bool { return true ^ ^ false; }",
                "fnc bad(): void { if true, false then return; fi }",
                "fnc bad(): void { while true, false do break; done }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
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
                  val xor: bool = a ^^ b;
                  return and || or && xor;
                }
                """)));
    }
}
