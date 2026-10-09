package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.MacroExpander;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;

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
        assertTrue(ex.getMessage().contains("file prelude"), ex.getMessage());
    }

    @Test
    void rejectsMacrosHiddenInEndDelimitedModuleScopes() {
        String source = """
                define module app as
                  macro_rules! hidden { ($v:expr) => { $v }; }
                  fnc run(): int { return hidden!(1); }
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(ex.getMessage().contains("file prelude"), ex.getMessage());
    }

    @Test
    void allowsMacrosAfterImportsButBeforeDefinitions() {
        String source = """
                import fnc add from "./math";
                macro_rules! identity { ($value:expr) => { $value }; }
                define module app as
                  fnc run(): int { return identity!(1); }
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
    }

    @Test
    void generatedSelfAndSuperCannotImplicitlyCaptureActorState() {
        for (String forbidden : List.of("self", "super")) {
            String source = "macro_rules! unsafe { ($v:expr) => { "
                    + forbidden + ".state + $v }; }";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()));
            assertTrue(ex.getMessage().contains("expression macro"), ex.getMessage());
        }
    }

    @Test
    void generatedEndAndIfKeywordsCannotEscapeLexicalExpressions() {
        for (String forbidden : List.of("end", "fi", "if", "else", "return", "let")) {
            String source = "macro_rules! escape { ($v:expr) => { "
                    + forbidden + " $v }; }";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()));
            assertTrue(ex.getMessage().contains("expression macro"), ex.getMessage());
        }
    }

    @Test
    void generatedAssignmentIsNotAcceptedAsAnExpressionMacro() {
        String source = "macro_rules! assign { ($v:ident) => { $v = 42 }; }";
        assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(new Lexer(source).scan()));
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
    void fuzzNestedExpressionCapturesRemainGroupedAndParseable() {
        // Reproducible property test: varying operands, nesting, and whitespace.
        Random random = new Random(0x4F5245534C414E47L);
        String definition = "macro_rules! combine { ($left:expr, $right:expr) => { $left + $right }; }\n";
        for (int iteration = 0; iteration < 160; iteration++) {
            int a = random.nextInt(1000);
            int b = random.nextInt(1000);
            int c = random.nextInt(1000);
            int d = random.nextInt(1000);
            // Create balanced extra group levels without changing the expression.
            int depth = random.nextInt(6);
            String left = "(".repeat(depth) + "(" + a + " + " + b + ")" + ")".repeat(depth);
            String right = "(" + c + " * " + d + ")";
            String source = definition + "define module app as\n"
                    + "  fnc run(): int { return combine!(" + left + ", " + right + "); }\n"
                    + "end\n";
            List<Token> expanded = MacroExpander.expand(new Lexer(source).scan()).tokens();
            assertEquals(Token.Type.EOF, expanded.getLast().type(), "iteration=" + iteration);
            assertTrue(expanded.stream().noneMatch(token -> token.type() == Token.Type.DOLLAR
                    || token.lexeme().equals("combine")), "iteration=" + iteration);
            int caseNumber = iteration;
            assertDoesNotThrow(() -> Parser.parse(source), () -> "iteration=" + caseNumber);
        }
    }

    @Test
    void fuzzMismatchedMacroDelimitersFailClosed() {
        Random random = new Random(0xD311B17EL);
        char[] open = {'(', '[', '{'};
        char[] close = {')', ']', '}'};
        for (int iteration = 0; iteration < 180; iteration++) {
            int depth = 1 + random.nextInt(24);
            int[] levels = new int[depth];
            StringBuilder argument = new StringBuilder();
            for (int level = 0; level < depth; level++) {
                levels[level] = random.nextInt(open.length);
                argument.append(open[levels[level]]);
            }
            argument.append("7");
            int innermost = levels[depth - 1];
            argument.append(close[(innermost + 1 + random.nextInt(2)) % close.length]);
            for (int level = depth - 2; level >= 0; level--) {
                argument.append(close[levels[level]]);
            }
            String source = """
                    macro_rules! identity { ($value:tt) => { $value }; }
                    define module app as
                      fnc run(): int { return identity!(%s); }
                    end
                    """.formatted(argument);
            int caseNumber = iteration;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()),
                    () -> "mismatched delimiters unexpectedly accepted: iteration=" + caseNumber);
            assertTrue(failure.getMessage().contains("macro"),
                    () -> "iteration=" + caseNumber + " unexpected failure: " + failure);
        }
    }

    @Test
    void fuzzAotAndJitGenerationFingerprintsAndFormatting() {
        Random random = new Random(0xA07C0DEF00DL);
        for (int iteration = 0; iteration < 120; iteration++) {
            int value = random.nextInt(1_000_000);
            String aot = "@expand(aot)\nmacro_rules! shift { ($v:expr) => { $v + "
                    + value + " }; }\n";
            String formatted = "@expand(aot) /* formatting noise */ \n"
                    + "macro_rules! shift { ( $v : expr ) => { $v  +  "
                    + value + " }; }\n";
            String changed = aot.replace("+ " + value, "+ " + (value + 1));
            MacroExpander.Snapshot initial = MacroExpander.expand(new Lexer(aot).scan()).snapshot();
            assertEquals(initial, MacroExpander.expand(new Lexer(formatted).scan(), initial).snapshot(),
                    "iteration=" + iteration);
            int caseNumber = iteration;
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(changed).scan(), initial),
                    () -> "AOT edit was allowed: iteration=" + caseNumber);

            String jit = aot.replace("@expand(aot)", "@expand(jit)");
            String jitChanged = changed.replace("@expand(aot)", "@expand(jit)");
            MacroExpander.Snapshot jitInitial = MacroExpander.expand(new Lexer(jit).scan()).snapshot();
            MacroExpander.Snapshot jitUpdated =
                    MacroExpander.expand(new Lexer(jitChanged).scan(), jitInitial).snapshot();
            assertNotEquals(jitInitial, jitUpdated, "iteration=" + iteration);
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(aot).scan(), jitInitial),
                    () -> "mode switch was allowed: iteration=" + caseNumber);
        }
    }

    @Test
    void fuzzQuotedPayloadsCannotInjectMacroSyntax() {
        Random random = new Random(0x5EED5AFE1234L);
        String[] fragments = {
                "a", "9", " ", "!", "(", ")", "{", "}", "[", "]", ",", ":",
                ";", "$", "@", "macro_rules!", "identity!(42)", "\\n", "\\t",
                "\\\"", "\\\\", "\u03A9", "\u2603"
        };
        String definition = "macro_rules! identity { ($value:expr) => { $value }; }\n";
        for (int iteration = 0; iteration < 160; iteration++) {
            StringBuilder quotedContents = new StringBuilder();
            for (int part = 0, parts = random.nextInt(25); part < parts; part++) {
                quotedContents.append(fragments[random.nextInt(fragments.length)]);
            }
            String quoted = "\"" + quotedContents + "\"";
            String expected = new Lexer(quoted).scan().getFirst().lexeme();
            String source = definition + "define module app as\n"
                    + "  fnc run(): String { return identity!(" + quoted + "); }\n"
                    + "end\n";
            List<Token> expanded = MacroExpander.expand(new Lexer(source).scan()).tokens();
            List<Token> strings = expanded.stream()
                    .filter(token -> token.type() == Token.Type.STRING).toList();
            assertEquals(1, strings.size(), "iteration=" + iteration);
            assertEquals(expected, strings.getFirst().lexeme(), "iteration=" + iteration);
            int caseNumber = iteration;
            assertDoesNotThrow(() -> Parser.parse(source), () -> "iteration=" + caseNumber);
        }
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
