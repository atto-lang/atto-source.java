package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class IfnotUnlessTest {
    @Test
    void guestExecutionPreservesElseElifAndBracedForms() {
        assertEquals("1234", run("""
                pub fnc main(): void {
                  ifnot false then stdio.stdout.write(1); else stdio.stdout.write(9); fi
                  unless true then stdio.stdout.write(9); else stdio.stdout.write(2); fi
                  unless false { stdio.stdout.write(3); fi
                  ifnot true { stdio.stdout.write(9); } elif true { stdio.stdout.write(4); fi
                  return;
                }
                """));
    }

    @Test
    void nestedKeywordsAndOptionalSemicolonKeepTheCorrectFi() {
        assertEquals("56", run("""
                pub fnc main(): void {
                  ifnot false; then
                    unless false then stdio.stdout.write(5); fi
                  fi
                  unless true then stdio.stdout.write(9);
                  else
                    ifnot false then stdio.stdout.write(6); fi
                  fi
                  return;
                }
                """));
    }

    @Test
    void lexerReservesOnlyWholeWords() {
        assertEquals(List.of(Token.Type.IFNOT, Token.Type.UNLESS, Token.Type.EOF),
                new Lexer("ifnot unless").scan().stream().map(Token::type).toList());
        assertEquals(List.of(Token.Type.IDENT, Token.Type.IDENT, Token.Type.IDENT,
                        Token.Type.STRING, Token.Type.EOF),
                new Lexer("ifnot2 unless_case unlessX \"unless\"")
                        .scan().stream().map(Token::type).toList());
    }

    @Test
    void conditionsMustBeBoolean() {
        for (String word : List.of("ifnot", "unless")) {
            String source = "pub fnc main(): void { " + word
                    + " 123 then return; fi return; }";
            assertThrows(RuntimeException.class,
                    () -> TypeChecker.check(Parser.parse(source)), word);
        }
    }

    @Test
    void invertedBindingsCannotLeakIntoNonmatchingArms() {
        for (String word : List.of("ifnot", "unless")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "fnc f(): void { " + word
                        + " let Some(value) = candidate then return; fi return; }"));
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "fnc f(): void { " + word
                        + " 1 as let value when value > 0 then return; fi return; }"));
        }
    }

    private static String run(String source) {
        var parsed = Parser.parseWithWarnings(source);
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        OwnershipChecker.check(TypeChecker.check(parsed.program()));
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID).out(output).allowAllAccess(false).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
