package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class BangExceptionKeywordLexerTest {
    @Test
    void canonicalTrapBangDoesNotFallThroughLegacyOptionLowering() {
        // trap! promises Result<T,Error>, which the legacy trap Option<T>
        // cannot implement. The parser preserves the canonical spelling and
        // the type checker fails before native guest execution.
        var canonical = Parser.parse("fnc main():int { return trap! 7; }");
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(canonical));
        assertTrue(rejected.getMessage().contains("TRAP_BANG_RESULT_LOWERING_UNVERIFIED"));

        // Bare migration spelling remains a distinct legacy path. It can
        // reject for its own type reasons but must not be misclassified as
        // the canonical Result-returning operator.
        var legacy = Parser.parse("fnc main():int { return trap 7; }");
        try {
            TypeChecker.checkTypes(legacy);
        } catch (IllegalArgumentException error) {
            assertFalse(error.getMessage().contains("TRAP_BANG_RESULT_LOWERING_UNVERIFIED"));
        }
    }

    @Test
    void trapBangFunctionModifierCannotSilentlyAdoptLegacyOptionAbi() {
        var rejected = assertThrows(RuntimeException.class,
                () -> Parser.parse("trap! fnc main():int { return 7; }"));
        assertTrue(rejected.getMessage().contains("TRAP_BANG_RESULT_LOWERING_UNVERIFIED"));
    }

    @Test
    void canonicalExceptionalStatementsFailClosedUntilTypedGuestUnwindExists() {
        for (String keyword : List.of("throw!", "raise!", "panic!")) {
            String source = "fnc main():int { " + keyword + " new Error(); }";
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> Parser.parse(source));
            assertTrue(rejected.getMessage().contains("typed exceptional effect"),
                    keyword + " must not silently execute as a host exception: "
                            + rejected.getMessage());
        }
    }

    @Test
    void contiguousExceptionKeywordsAreAtomicWithoutChangingTheirMeaning() {
        List<Token> tokens = new Lexer("trap! call; throw! err; raise! err; panic! err").scan();
        assertEquals(List.of(Token.Type.TRAP, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.THROW, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.RAISE, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.PANIC, Token.Type.IDENT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
        assertEquals(List.of("trap!", "throw!", "raise!", "panic!"),
                tokens.stream().filter(t -> t.type() == Token.Type.TRAP ||
                        t.type() == Token.Type.THROW || t.type() == Token.Type.RAISE ||
                        t.type() == Token.Type.PANIC).map(Token::lexeme).toList());
    }

    @Test
    void whitespaceInequalityCommentsAndIdentifiersStaySeparate() {
        List<Token> tokens = new Lexer("trap !x; trap!eq throw!=; trapped! // panic!").scan();
        assertEquals(List.of(Token.Type.TRAP, Token.Type.BANG, Token.Type.IDENT,
                Token.Type.SEMICOLON, Token.Type.TRAP, Token.Type.NEQ,
                Token.Type.THROW, Token.Type.BANG_EQUAL, Token.Type.SEMICOLON,
                Token.Type.IDENT, Token.Type.BANG, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    void canonicalEighteenLexicalCasesDoNotConfuseOtherBangOperators() {
        // Mirrors the source-only 18-case specification PR #146.
        // This test establishes lexical spelling, not checked guest unwind.
        String[][] cases = {
                {"trap! parse_id(\"42\")", "trap!"},
                {"trap!parse_id(\"42\")", "trap!"},
                {"throw! Error(\"bad\")", "throw!"},
                {"raise! Error(\"bad\")", "raise!"},
                {"panic! Error(\"bad\")", "panic!"},
                {"throw!Error(\"bad\")", "throw!"},
                {"trap parse_id(\"42\")", ""},
                {"throw Error(\"bad\")", ""},
                {"raise Error(\"bad\")", ""},
                {"panic Error(\"bad\")", ""},
                {"trap !ready", ""},
                {"trap!=next", ""},
                {"trap!eq next", ""},
                {"trapped! parse_id()", ""},
                {"copy! owned", ""},
                {"\"trap! throw! raise! panic!\"", ""},
                {"// trap! throw! raise! panic!\n42", ""},
                {"/* trap! throw! raise! panic! */ 42", ""},
        };
        assertEquals(18, cases.length);
        for (String[] testCase : cases) {
            List<String> actual = new Lexer(testCase[0]).scan().stream()
                .filter(t -> t.type() == Token.Type.TRAP || t.type() == Token.Type.THROW
                    || t.type() == Token.Type.RAISE || t.type() == Token.Type.PANIC)
                .map(Token::lexeme)
                .filter(s -> s.endsWith("!"))
                .toList();
            List<String> expected = testCase[1].isEmpty()
                ? List.of() : List.of(testCase[1]);
            assertEquals(expected, actual, "exception-bang lexical collision: " + testCase[0]);
        }
    }
}
