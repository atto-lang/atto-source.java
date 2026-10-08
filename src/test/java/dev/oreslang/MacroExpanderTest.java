package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.MacroExpander;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class MacroExpanderTest {
    private static final String AOT = """
            @expand(aot)
            macro_rules! increment {
              ($value:expr) => { ($value + 1) };
            }
            define module app as
              pub fnc run(): int {
                return increment!(41);
              }
            end
            """;

    private static final String JIT_ONE = """
            @expand(jit)
            macro_rules! adjust {
              ($v:expr) => { ($v + 1) };
            }
            define module app as
              pub fnc run(): int {
                return adjust!(41);
              }
            end
            """;

    private static final String JIT_TWO = JIT_ONE.replace("($v + 1)", "($v + 2)");

    @Test
    void expandsBeforeAstParsingAndPreservesOrdinaryLanguageChecks() {
        assertDoesNotThrow(() -> Parser.parse(AOT));
        MacroExpander.Result result = MacroExpander.expand(new Lexer(AOT).scan());
        assertEquals(MacroExpander.Mode.AOT, result.snapshot().definitions().get("increment").mode());
        assertTrue(result.tokens().stream().anyMatch(t -> t.type() == Token.Type.INT
                && t.lexeme().equals("41")));
        assertFalse(result.tokens().stream().anyMatch(t -> t.lexeme().equals("macro_rules")
                || t.lexeme().equals("increment")));
    }

    @Test
    void defaultsToAotWithoutAnAnnotation() {
        String source = AOT.replace("@expand(aot)\n", "");
        assertDoesNotThrow(() -> Parser.parse(source));
        assertEquals(MacroExpander.Mode.AOT,
                MacroExpander.expand(new Lexer(source).scan())
                        .snapshot().definitions().get("increment").mode());
    }

    @Test
    void jitMacroCanChangeOnlyByRecompilingANewGeneration() {
        Parser.MacroParseResult first = Parser.parseWithMacroSnapshot(JIT_ONE, null);
        Parser.MacroParseResult next = assertDoesNotThrow(
                () -> Parser.parseWithMacroSnapshot(JIT_TWO, first.snapshot()));
        assertNotEquals(first.snapshot().definitions().get("adjust").sha256(),
                next.snapshot().definitions().get("adjust").sha256());
    }

    @Test
    void aotMacroRejectsChangesOrRemovalAcrossGenerations() {
        Parser.MacroParseResult first = Parser.parseWithMacroSnapshot(AOT, null);
        assertDoesNotThrow(() -> Parser.parseWithMacroSnapshot(AOT, first.snapshot()));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parseWithMacroSnapshot(AOT.replace("($value + 1)", "($value + 2)"),
                        first.snapshot()));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parseWithMacroSnapshot("define module app as end", first.snapshot()));
    }

    @Test
    void rejectsFreeIdentifiersInsteadOfAllowingAccidentalCapture() {
        String source = """
                macro_rules! steal {
                  ($v:expr) => { (outer_state + $v) };
                }
                define module app as
                  fnc run(): int { return steal!(1); }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("introduced identifiers"));
    }

    @Test
    void rejectsLexicalDeclarationsInTemplatesUntilHygienicBindingExists() {
        String source = """
                macro_rules! unsafe_scope {
                  ($v:expr) => { let local = $v; };
                }
                define module app as
                  fnc run(): int { return unsafe_scope!(1); }
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test
    void rejectsDuplicateCaptureUseSoArgumentsCannotSilentlyRunTwice() {
        String source = """
                macro_rules! twice {
                  ($v:expr) => { ($v + $v) };
                }
                define module app as end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test
    void nestedArgumentsAndMultipleCapturesExpandWithoutSplittingNestedCommas() {
        String source = """
                macro_rules! left {
                  ($a:expr, $b:expr) => { ($a) };
                }
                define module app as
                  pub fnc run(): int {
                    return left!((40 + 2), (99 + 1));
                  }
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
    }

    @Test
    void rejectsMismatchedArityAndNonIdentifierArguments() {
        String source = """
                macro_rules! id {
                  ($x:ident) => { $x };
                }
                define module app as
                  fnc run(): int { return id!(41); }
                end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source.replace("id!(41)", "id!(a, b)")));
    }

    @Test
    void detectsRecursiveExpansionWithoutAStackOverflow() {
        String source = """
                macro_rules! repeat {
                  ($x:expr) => { repeat!($x) };
                }
                define module app as
                  fnc run(): int { return repeat!(1); }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("depth exceeded"));
    }

    @Test
    void unknownAndDuplicateMacroDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc run(): int { return missing!(1); }"));
        String duplicate = """
                macro_rules! one { ($x:expr) => { $x }; }
                macro_rules! one { ($x:expr) => { $x }; }
                define module app as end
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(duplicate));
    }

    @Test
    void scalarAndStringArgumentsRetainLexemeValues() {
        String source = """
                macro_rules! identity { ($value:expr) => { $value }; }
                define module app as
                  fnc name(): String {
                    return identity!("safe");
                  }
                end
                """;
        List<Token> expanded = MacroExpander.expand(new Lexer(source).scan()).tokens();
        assertTrue(expanded.stream().anyMatch(t -> t.type() == Token.Type.STRING
                && t.lexeme().equals("safe")));
    }
}
