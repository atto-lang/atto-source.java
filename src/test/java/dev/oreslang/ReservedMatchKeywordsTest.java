package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ReservedMatchKeywordsTest {
    @Test
    void overIsReservedAndStructuralKeepsContextualCompatibility() {
        var tokens = new Lexer("match account over on structural { id: string }").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.lexeme().equals("over") && t.type() == Token.Type.OVER));
        assertTrue(tokens.stream().anyMatch(t -> t.lexeme().equals("structural") && t.type() == Token.Type.IDENT));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "fnc bad(): void { val over = 1; return; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc good(): void { val structural = 1; return; }"));
    }

    @Test
    void canonicalMatchAndCallableStructuralModifiersStillWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                structural fnc classify(): string {
                  const account = infer struct { id: "acct_42", active: true };
                  match account over
                    on structural { id: string, active: bool } a -> {
                      return a.id;
                    }
                    on _ -> { return "unknown"; }
                  end
                }

                @structural
                fnc annotated(string value): string {
                  return value;
                }
                """)));
    }

    @Test
    void structuralParameterSpellingsRemainAccepted() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc typeFirst(structural string value): void { return; }
                fnc nameFirst(value structural string): void { return; }
                """));
    }
}
