package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TypedExceptionalEffectAdmissionTest {
    @Test
    void exceptionalEffectNamesHaveDedicatedTokens() {
        List<Token.Type> tokens = new Lexer("throw raise panic recover").scan().stream()
                .map(Token::type).toList();
        assertEquals(List.of(Token.Type.THROW, Token.Type.RAISE,
                Token.Type.PANIC, Token.Type.RECOVER, Token.Type.EOF), tokens);
    }

    @Test
    void illTypedEffectOperandsRejectBeforeSourceExecution() {
        // Effects are executable (defer-recover-unwind-v1); a non-Error
        // throw/raise/panic operand or a non-handler recover is still rejected
        // by the checker before any guest code runs.
        for (String effect : List.of("throw", "raise", "panic", "recover")) {
            String source = "pub fnc main(): int { " + effect + " 1; return 0; }";
            assertDoesNotThrow(() -> Parser.parse(source), effect);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)), effect);
            assertTrue(failure.getMessage().contains("requires an Error value")
                    || failure.getMessage().contains("|err, cursor|"), failure::getMessage);
        }
    }

    @Test
    void reservedKeywordsCannotMasqueradeAsOrdinaryIdentifiers() {
        for (String effect : List.of("throw", "raise", "panic", "recover")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc " + effect + "(): int { return 1; }"),
                    effect);
        }
    }

    @Test
    void UnrelatedFunctionNamesRemainValid() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc throwable(): int { return 42; }
                fnc recovery(): int { return throwable(); }
                """));
    }
}
