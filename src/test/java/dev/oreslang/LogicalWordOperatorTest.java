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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LogicalWordOperatorTest {
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts", "logical-word-operators-v1", name),
                StandardCharsets.UTF_8);
    }

    @Test
    void keywordsAreReservedAndCaseSensitive() {
        List<Token> tokens = new Lexer("and or xor AND Or Xor").scan();
        assertEquals(Token.Type.AND, tokens.get(0).type());
        assertEquals(Token.Type.OR, tokens.get(1).type());
        assertEquals(Token.Type.XOR, tokens.get(2).type());
        assertEquals(Token.Type.IDENT, tokens.get(3).type());
        assertEquals(Token.Type.IDENT, tokens.get(4).type());
        assertEquals(Token.Type.IDENT, tokens.get(5).type());
    }

    @Test
    void keywordRecognitionUsesWholeCaseSensitiveTokensAndIgnoresCommentsAndStrings() {
        List<Token> tokens = new Lexer(
                "and or xor candy android origin xor_value \"and or xor\" // and or xor\n"
        ).scan();
        assertEquals(List.of(
                Token.Type.AND, Token.Type.OR, Token.Type.XOR,
                Token.Type.IDENT, Token.Type.IDENT, Token.Type.IDENT, Token.Type.IDENT,
                Token.Type.STRING, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals("and or xor", tokens.get(7).lexeme());
    }

    @Test
    void operatorsAreValidInLocalsReturnsConditionsAndCallArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc accept(bool value): bool {
                  return value;
                }

                fnc combine(bool enabled, bool ready, bool forced): bool {
                  val result: bool = enabled and ready;
                  if (result or forced xor false) {
                    return accept(result and forced);
                  } fi
                  return result;
                }
                """)));
    }

    @Test
    void precedenceIsAndThenXorThenOr() {
        Ast.Expr expression = new Parser(new Lexer(
                "false or true xor true and false").scan()).parseExpression();
        Ast.BinaryExpr or = assertInstanceOf(Ast.BinaryExpr.class, expression);
        assertEquals("||", or.operator());
        Ast.BinaryExpr xor = assertInstanceOf(Ast.BinaryExpr.class, or.right());
        assertEquals("^^", xor.operator());
        Ast.BinaryExpr and = assertInstanceOf(Ast.BinaryExpr.class, xor.right());
        assertEquals("&&", and.operator());
    }

    @Test
    void truthTablesAndPrecedenceExecuteAsGuestSource() throws Exception {
        String source = fixture("truth-table.atto");
        TypeChecker.check(Parser.parse(source));
        assertEquals(List.of(
                "false", "false", "false", "true",
                "false", "true", "true", "true",
                "false", "true", "true", "false",
                "true"), run(source, "truth-table.atto"));
    }

    @Test
    void andOrShortCircuitButXorAlwaysEvaluatesRight() throws Exception {
        String source = fixture("short-circuit.atto");
        TypeChecker.check(Parser.parse(source));
        assertEquals(List.of("false", "true", "rhs", "true"),
                run(source, "short-circuit.atto"));
    }

    @Test
    void sameByteScalarParityFixtureRuns() throws Exception {
        String program = fixture("scalar-parity.atto");
        TypeChecker.check(Parser.parse(program));
        Source source = Source.newBuilder(OresLanguage.ID, program, "scalar-parity.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42, context.eval(source).asInt());
        }
    }

    @Test
    void eachOperatorRequiresBooleanOperandsAndKeywordsCannotBeNames() throws Exception {
        for (String name : List.of("reject-int-and.atto", "reject-int-or.atto",
                "reject-int-xor.atto")) {
            String source = fixture(name);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), name);
            assertTrue(error.getMessage().contains("logical operand"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("reject-word-as-name.atto")));
    }

    private static List<String> run(String program, String fileName) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, fileName)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
