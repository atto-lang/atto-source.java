package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes real AttoLang guest source through the Java/Graal language engine.
 * An interpreter-backed Truffle Context is NOT guest-code AOT evidence.
 */
final class AttoEqualityGuestParityTest {
    private static final Path FIXTURE =
            Path.of("tests/fixtures/erl-otp-aot-parity/attolang-equality.atto");
    private static final Path ORACLE =
            Path.of("tests/fixtures/erl-otp-aot-parity/attolang-equality.expected");

    @Test
    void canonicalAttoEqualityAndBangEqAliasExecuteAsGuestSource() throws Exception {
        String program = Files.readString(FIXTURE, StandardCharsets.UTF_8);
        String expected = Files.readString(ORACLE, StandardCharsets.UTF_8);
        assertFalse(program.contains(" == "), "do not put legacy C operators in canonical .atto fixture");
        assertFalse(program.contains(" != "), "do not put legacy C operators in canonical .atto fixture");
        assertTrue(program.contains(" eq "));
        assertTrue(program.contains(" neq "));
        assertTrue(program.contains(" !eq "));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "attolang-equality.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void lexerAndParserKeepBangEqContiguousAndDistinctFromUnaryNot() {
        Ast.Expr expr = new Parser(new Lexer("left !eq right").scan()).parseExpression();
        Ast.BinaryExpr binary = assertInstanceOf(Ast.BinaryExpr.class, expr);
        assertEquals("neq", binary.operator());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(int left, int right): bool {
                    return left ! eq right;
                }
                """));
    }
}
