package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LogicalWordOperatorsTest {
    @Test
    void lexicalWordsAreReservedButBuiltinFunctionNamesRemainDistinct() {
        var tokens = new Lexer("and or xor And Or Xor && || ^^").scan();
        assertEquals(Token.Type.AND, tokens.get(0).type());
        assertEquals(Token.Type.OR, tokens.get(1).type());
        assertEquals(Token.Type.XOR, tokens.get(2).type());
        assertEquals(Token.Type.IDENT, tokens.get(3).type());
        assertEquals(Token.Type.IDENT, tokens.get(4).type());
        assertEquals(Token.Type.IDENT, tokens.get(5).type());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc and(): bool { return true; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc or(): bool { return false; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc xor(): bool { return false; }"));
    }

    @Test
    void infixAliasesPreservePrecedenceShortCircuitAndNestedBuiltinCalls() throws Exception {
        String program = """
                pub fnc main(): void {
                  stdio.println(true and false or true);
                  stdio.println(true && false || true);
                  stdio.println(false or true and false);
                  stdio.println(false || true && false);
                  stdio.println(true and true ^^ false);
                  stdio.println(true && true ^^ false);
                  stdio.println(false and [true][99]);
                  stdio.println(true or [false][99]);
                  stdio.println(false && [true][99]);
                  stdio.println(true || [false][99]);
                  stdio.println(Or(Xor(true, And(true, true, true)), true));
                  stdio.println(BooleanOps.Xor(false, true));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertDoesNotThrow(() -> OresCompiler.compileForBuild(
                program, BuildOptions.executable(Map.of())));
        assertEquals("""
                true
                true
                false
                false
                true
                true
                false
                true
                false
                true
                true
                true
                """, run(program));
    }

    @Test
    void xorKeywordSharesBooleanPrecedenceAndExactlyOnceEvaluationWithCaretPair() throws Exception {
        String code = """
                pub fnc main(): void {
                  stdio.println(true xor false);
                  stdio.println(true ^^ false);
                  stdio.println(true xor true xor true);
                  stdio.println(true ^^ true ^^ true);
                  stdio.println(true or true xor true);
                  stdio.println(true || true ^^ true);
                  stdio.println(true xor true and false);
                  stdio.println(true ^^ true && false);
                  stdio.println(false and [true][99] xor true);
                  stdio.println(false && [true][99] ^^ true);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(code)));
        assertDoesNotThrow(() -> OresCompiler.compileForBuild(
                code, BuildOptions.executable(Map.of())));
        assertEquals("""
                true
                true
                true
                true
                true
                true
                true
                true
                true
                true
                """, run(code));
    }

    @Test
    void xorEagerlyEvaluatesItsRightOperandAndRejectsNonBooleanTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "fnc bad(): bool { return true xor 1; }")));
        assertThrows(org.graalvm.polyglot.PolyglotException.class,
                () -> run("""
                        pub fnc main(): void {
                          stdio.println(true xor [false][99]);
                        }
                        """));
    }

    @Test
    void wordOperatorsRequireBooleanOperandsAndCannotBypassTypeChecks() {
        for (String expression : new String[]{
                "true and 1",
                "false or 0",
                "true and 1 or false",
                "false or true and 1",
                "true and false or 3"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "pub fnc main(): bool { return " + expression + "; }")),
                    expression);
        }
    }

    @Test
    void chainedAndOrAndTernaryHaveStableAssociativity() throws Exception {
        assertEquals("""
                true
                false
                true
                true
                """, run("""
                pub fnc main(): void {
                  stdio.println(false or false or true);
                  stdio.println(true and true and false);
                  stdio.println(false or true ? true : false);
                  stdio.println(false or true and true);
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "word-logical-operators.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
