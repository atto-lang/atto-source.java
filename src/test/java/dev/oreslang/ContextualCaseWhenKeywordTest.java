package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ContextualCaseWhenKeywordTest {
    @Test
    void caseAndWhenLexAsOrdinaryIdentifiers() {
        var tokens = new Lexer("case when").scan();
        assertEquals(Token.Type.IDENT, tokens.get(0).type());
        assertEquals("case", tokens.get(0).lexeme());
        assertEquals(Token.Type.IDENT, tokens.get(1).type());
        assertEquals("when", tokens.get(1).lexeme());
    }

    @Test
    void caseAndWhenRemainLegalVariableAndParameterNames() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc add(int when, int case): int {
                  val int total = when + case;
                  return total;
                }

                fnc locals(): int {
                  val int when = 4;
                  val int case = 5;
                  return when + case;
                }
                """)));
    }

    @Test
    void parserStillRecognizesContextualSelectSwitchAndMatchUses() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc dispatch(int value): void {
                  do select {
                    when readch input: {
                    }
                    case writech output, value: {
                    }
                  }

                  switch value
                    case 1 -> {
                      return;
                    }
                    default -> {
                      return;
                    }
                  end
                }

                fnc classify(int value): int {
                  return match value over
                    is int n when n > 0 -> {
                      return n;
                    }
                    else -> {
                      return 0;
                    }
                  end;
                }
                """));
    }
}
