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
    void preservesUnrelatedAnnotationsWithSimilarNames() {
        List<Token> original = new Lexer("@expand(foo) fnc unrelated(): void {}").scan();
        assertEquals(original, MacroExpander.expand(original).tokens());
    }

    @Test
    void largeOrdinarySourcesDoNotExhaustTheMacroOnlyTokenBudget() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 60_000; i++) large.append("x ");
        List<Token> original = new Lexer(large.toString()).scan();
        assertEquals(original, MacroExpander.expand(original).tokens());
    }

    @Test
    void expressionFragmentsAreGroupedBeforeOperatorPrecedenceIsParsed() {
        String source = """
                macro_rules! multiply { ($x:expr) => { $x * 2 }; }
                define module app as
                  fnc run(): int { return multiply!(1 + 2); }
                end
                """;
        List<Token> expanded = MacroExpander.expand(new Lexer(source).scan()).tokens();
        String serialized = expanded.stream().map(Token::lexeme).reduce("", (a, b) -> a + " " + b);
        assertTrue(serialized.contains("( 1 + 2 ) * 2"), serialized);
        assertDoesNotThrow(() -> Parser.parse(source));
    }

    @Test
    void rejectsLexicallyNestedMacroDefinitionsWithoutHidingCode() {
        String source = """
                define module app as
                  fnc run(): int {
                    macro_rules! inside { ($x:expr) => { $x }; }
                    return inside!(1);
                  }
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(ex.getMessage().contains("file scope"), ex.getMessage());
    }

    @Test
    void rejectsPrematureEofTokensAndDoesNotHideSuffix() {
        List<Token> tokens = List.of(
                new Token(Token.Type.IDENT, "first", 1, 1),
                new Token(Token.Type.EOF, "", 1, 6),
                new Token(Token.Type.IDENT, "hidden", 1, 7),
                new Token(Token.Type.EOF, "", 1, 13));
        assertThrows(IllegalArgumentException.class, () -> MacroExpander.expand(tokens));
    }

    @Test
    void macroFingerprintsIgnoreFormattingButRejectSemanticChange() {
        String one = "macro_rules! constant { ($x:expr) => { $x + 1 }; }";
        String formatted = "macro_rules! constant {\n($x:expr)=>{$x + 1};\n}";
        String changed = "macro_rules! constant { ($x:expr) => { $x + 2 }; }";
        MacroExpander.Snapshot initial = MacroExpander.expand(new Lexer(one).scan()).snapshot();
        assertEquals(initial, MacroExpander.expand(new Lexer(formatted).scan(), initial).snapshot());
        assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(new Lexer(changed).scan(), initial));
    }

    @Test
    void doesNotExecuteOrDuplicateArgumentsNotReferencedByTemplate() {
        String source = """
                macro_rules! first { ($a:expr, $b:expr) => { $a }; }
                define module app as
                  fnc run(): int { return first!(42, 1 + 2); }
                end
                """;
        List<Token> expanded = MacroExpander.expand(new Lexer(source).scan()).tokens();
        assertEquals(1, expanded.stream().filter(t -> t.type() == Token.Type.INT
                && t.lexeme().equals("42")).count());
    }
    @Test
    void aotFingerprintDistinguishesMalformedUnicodeCodeUnits() {
        String prefix = "macro_rules! unicode { ($v:expr) => { \"";
        String suffix = "\" }; }";
        String initialSource = prefix + (char) 0xD800 + suffix;
        String modifiedSource = prefix + (char) 0xD801 + suffix;
        MacroExpander.Snapshot previous =
                MacroExpander.expand(new Lexer(initialSource).scan()).snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(new Lexer(modifiedSource).scan(), previous));
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
