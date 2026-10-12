package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed source boundaries until sealed handle_state and RW scheduler
 * lowering are compiled end-to-end. Do not treat lexing as codegen support.
 */
final class ActorStateBoundaryKeywordTest {
    @Test
    void recognizesOnlyExactHyphenatedReservedKeywords() {
        List<Token> tokens = new Lexer(
                "critical-read critical-write transform-pipe nb-select").scan();
        assertEquals(List.of(Token.Type.CRITICAL_READ, Token.Type.CRITICAL_WRITE,
                Token.Type.TRANSFORM_PIPE, Token.Type.NB_SELECT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());

        tokens = new Lexer("a-b a -> b critical - read critical-reader").scan();
        assertEquals(List.of(Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.IDENT, Token.Type.ARROW, Token.Type.IDENT,
                Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.CRITICAL, Token.Type.MINUS, Token.Type.IDENT, Token.Type.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    void stateIsNotAnOrdinaryActorField() {
        for (String declaration : List.of("let int state = 3;", "final state: int = 3;")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define actor Foo as " + declaration + " end"));
            assertTrue(failure.getMessage().contains("reserved runtime-owned storage"),
                    failure.getMessage());
        }
        assertDoesNotThrow(() -> Parser.parse("""
                define class Bar as
                    let int state = 3;
                end
                """));
    }

    @Test
    void directActorStateReadsAndWritesAreCompilerRejected() {
        for (String statement : List.of("val i = self.state.v;",
                "self.state.v = 3;", "val i = (self).state.v;")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define actor Foo as receive(): void { "
                            + statement + " } end"));
            assertTrue(failure.getMessage().contains("self.state is not accessible"),
                    failure.getMessage());
        }
    }

    @Test
    void unsupportedActorStateAndReadWriteCallablesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Foo as
                    state { v: int = 0; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc critical-write update(): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define transform-pipe actor Foo as
                end
                """));
    }
}
