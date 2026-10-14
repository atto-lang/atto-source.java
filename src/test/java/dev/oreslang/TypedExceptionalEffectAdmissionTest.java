package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
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
    void raiseAndPanicParseButRejectNonActorAndScalarGuestValues() {
        for (String effect : List.of("raise", "panic")) {
            String source = "pub fnc main(): int { " + effect + " 1; return 0; }";
            assertDoesNotThrow(() -> Parser.parse(source));
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(source));
            assertTrue(failure.getMessage().contains("actor execution domain"),
                    failure::getMessage);
        }
    }

    @Test
    void recoverRemainsGatedPendingActorHookLowering() {
        String source = "pub fnc main(): int { recover 1; return 0; }";
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains("not yet enabled"));
    }

    @Test
    void typedThrowParsesButRequiresCompilerCheckedCatchAndGuestError() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class TaskError extends Error as
                    pub val int code;
                end
                pub fnc main(): int {
                    try {
                        throw new TaskError(7);
                    } catch (e) {
                        return 7;
                    }
                    return 0;
                }
                """));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): int {
                            try {
                                throw 1;
                            } catch (e) {
                                return 2;
                            }
                            return 0;
                        }
                        """));
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
