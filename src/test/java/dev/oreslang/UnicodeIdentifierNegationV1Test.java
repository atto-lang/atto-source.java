package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class UnicodeIdentifierNegationV1Test {
    @Test void lexerPreservesSourceSpellingAndDistinctNegationToken() {
        var tokens = new Lexer("Φ π ε ρ Ω ω 価格 價格 変数 𠮷 x𠮷2 〇 ¬ ! != !eq").scan();
        for (int i = 0; i < 12; i++) assertEquals(Token.Type.IDENT, tokens.get(i).type());
        assertEquals("𠮷", tokens.get(9).lexeme());
        assertEquals("x𠮷2", tokens.get(10).lexeme());
        assertEquals(Token.Type.UNICODE_NOT, tokens.get(12).type());
        assertEquals("¬", tokens.get(12).lexeme());
        assertEquals(Token.Type.BANG, tokens.get(13).type());
        assertEquals(Token.Type.BANG_EQUAL, tokens.get(14).type());
        assertEquals(Token.Type.NEQ, tokens.get(15).type());
        assertEquals(Token.Type.BANG, new Lexer("!eq𠮷").scan().get(0).type());
        assertEquals("eq𠮷", new Lexer("!eq𠮷").scan().get(1).lexeme());
        assertThrows(IllegalArgumentException.class, () -> new Lexer("ρ\u202e").scan());
    }

    @Test void guestExecutesUnicodeVariablesAndBooleanNegation() throws Exception {
        String program = """
                fnc 合計(int 数量, int 價格): int {
                    let int 結果 = 数量 * 價格;
                    let int 价格 = 2;
                    let int 𠮷 = 3;
                    let int Φ = 5;
                    let int φ = 6;
                    return 結果 + 价格 + 𠮷 + Φ + φ;
                }
                pub fnc main(): int {
                    if ¬false && ¬¬true && ¬(true && false) then
                        return 合計(3, 7);
                    else
                        return 0;
                    fi
                }
                """;
        TypeChecker.check(Parser.parse(program));
        Source source = Source.newBuilder(OresLanguage.ID, program, "unicode-parity.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(37, context.eval(source).asInt());
        }
    }

    @Test void invalidBooleanAndUnboundNameDoNotCompile() {
        for (String source : new String[]{
                "pub fnc main(): bool { return ¬1; }",
                "pub fnc main(): int { return 未定義; }",
                "pub fnc main(bool x): bool { return x ¬ true; }",
                "pub fnc main(bool x): bool { return x ¬= true; }",
                "pub fnc main(bool x): bool { return x¬; }"}) {
            assertThrows(RuntimeException.class, () -> TypeChecker.check(Parser.parse(source)), source);
        }
    }
}
