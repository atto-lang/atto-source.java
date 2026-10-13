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

    private static final String REBUILDABLE_ONE = """
            @expand(rebuildable)
            macro_rules! adjust {
              ($v:expr) => { ($v + 1) };
            }
            define module app as
              pub fnc run(): int {
                return adjust!(41);
              }
            end
            """;

    private static final String REBUILDABLE_TWO = REBUILDABLE_ONE.replace("($v + 1)", "($v + 2)");

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
    void controlFlowWhileTokenCannotBeHiddenInUnusedExpressionTemplate() {
        // Macro definitions are validated even when not invoked. A new
        // statement keyword must not silently bypass expression-only hygiene.
        for (String body : new String[] {
                "while",
                "while (true) do 1; done"
        }) {
            String source = "macro_rules! forbidden { () => { " + body
                    + " }; }\\ndefine module app as end";
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()),
                    body);
            assertTrue(rejected.getMessage().contains("only expression macro bodies"),
                    () -> "unexpected admission diagnostic: " + rejected.getMessage());
        }
    }

    @Test
    void macroCallsInsideWhileConditionAndBackedgePreserveBothFeatures() {
        // Regression for the simultaneous pre-1.0 macro and heap-owned
        // while-loop integration. Expanded expressions must be parsed before
        // the shared ForStmt/backedge continuation is type-checked.
        String source = """
                macro_rules! increment {
                  ($value:expr) => { ($value + 1) };
                }
                define module app as
                  pub fnc run(): int {
                    let int value = 0;
                    while (value < increment!(2)) do
                      value = increment!(value);
                    done
                    return value;
                  }
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        List<Token> expanded = MacroExpander.expand(new Lexer(source).scan()).tokens();
        assertTrue(expanded.stream().anyMatch(t -> t.type() == Token.Type.WHILE),
                "while token must remain a first-class control-flow keyword");
        assertFalse(expanded.stream().anyMatch(t -> t.lexeme().equals("increment")),
                "macro invocations must be removed before guest compilation");
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
    void rebuildableMacroCanChangeOnlyByRecompilingANewGeneration() {
        Parser.MacroParseResult first = Parser.parseWithMacroSnapshot(REBUILDABLE_ONE, null);
        Parser.MacroParseResult next = assertDoesNotThrow(
                () -> Parser.parseWithMacroSnapshot(REBUILDABLE_TWO, first.snapshot()));
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
                  ($a:expr, $b:expr) => { ($a + $b) };
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
        List<Token> original = new Lexer("@expand_policy(foo) fnc unrelated(): void {}").scan();
        assertEquals(original, MacroExpander.expand(original).tokens());
    }

    @Test
    void rejectsMisplacedOrMalformedExpansionPolicyInsteadOfSilentlyIgnoringIt() {
        for (String source : List.of(
                "@expand(aot) fnc run(): int { return 1; }",
                "@expand(rebuildable) define module app as end",
                "@expand aot macro_rules! m { () => { 1 }; }",
                "@expand(aot, rebuildable) macro_rules! m { () => { 1 }; }")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()), source);
            assertTrue(ex.getMessage().contains("@expand is reserved"), ex.getMessage());
        }
    }

    @Test
    void bareTokenTreesCannotInjectStatementsOrAnnotations() {
        String prefix = "macro_rules! single { ($x:tt) => { $x }; }\n";
        for (String bad : List.of("return", "end", ";", "@", "$", "let", "if", "=")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(prefix + "single!(" + bad + ")").scan()), bad);
            assertTrue(ex.getMessage().contains("unsafe bare token tree"), ex.getMessage());
        }
    }

    @Test
    void macroTemplatesCannotSynthesizeAnnotationsOrActorEffects() {
        for (String operation : List.of("@ $x", "await $x", "spawn $x",
                "select $x", "async $x", "trap $x", "rt borrow $x")) {
            String source = "macro_rules! danger { ($x:expr) => { "
                    + operation + " }; }";
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()), operation);
            assertTrue(rejected.getMessage().contains("only expression macro bodies"),
                    rejected.getMessage());
        }
    }

    @Test
    void groupedTokenTreesCannotSmuggleControlFlowOrAnnotationSyntax() {
        String prefix = "macro_rules! single { ($x:tt) => { $x }; }\n";
        for (String bad : List.of("(return 42)", "{let x = 3;}", "(@unsafe)", "[defer 1]")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(prefix + "single!(" + bad + ")").scan()), bad);
            assertTrue(ex.getMessage().contains("token-tree expression capture"), ex.getMessage());
        }
        assertDoesNotThrow(() -> MacroExpander.expand(
                new Lexer(prefix + "single!((1, 2))").scan()));
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
    void expressionCapturesMustBeCompleteSyntacticallyValidExpressions() {
        String definition = "macro_rules! id { ($x:expr) => { $x }; }\n";
        for (String fragment : List.of("1 +", "1; 2", "return 2",
                "let x = 1", "1 2", "@FromJson(\"key\")", "1 = 2")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(
                            new Lexer(definition + "id!(" + fragment + ")").scan()),
                    fragment);
            assertNotNull(failure.getMessage(), fragment);
        }
        for (String expression : List.of("1 + 2", "(3 * 4)", "foo(1, 2)",
                "1 < 3", "a ? b : c")) {
            assertDoesNotThrow(() -> MacroExpander.expand(
                    new Lexer(definition + "id!(" + expression + ")").scan()),
                    expression);
        }
    }

    @Test
    void nestedExpressionMacroCallsMustBeExpandedAndThenValidated() {
        String definitions = """
                macro_rules! unit { ($x:expr) => { ($x) }; }
                macro_rules! twice { ($x:expr) => { $x * 2 }; }
                """;
        assertDoesNotThrow(() -> MacroExpander.expand(
                new Lexer(definitions + "twice!(unit!(21))").scan()));
        assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(
                        new Lexer(definitions + "twice!(unit!(1; 2))").scan()));
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
    void rejectsUnusedCapturesBeforeTheyCanEraseUncheckedSource() {
        String definition = """
                macro_rules! first { ($a:expr, $b:expr) => { $a }; }
                define module app as
                  fnc run(): int { return first!(42, rt borrow mut self.state); }
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(definition));
        assertTrue(ex.getMessage().contains("unused macro capture $b"), ex.getMessage());

        for (String malformed : List.of("this is not an expression", "@Unsafe", "return 2",
                "rt take self.state")) {
            String source = """
                    macro_rules! swallow { ($hidden:expr) => { 42 }; }
                    define module app as
                      fnc run(): int { return swallow!(%s); }
                    end
                    """.formatted(malformed);
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(source).scan()), malformed);
            assertTrue(rejected.getMessage().contains("unused macro capture"),
                    rejected.getMessage());
        }
    }

    @Test
    void aotFingerprintDistinguishesMalformedUnicodeCodeUnits() {
        String prefix = "macro_rules! unicode { ($v:expr) => { \"";
        String suffix = "\" + $v }; }";
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
    void boundsMacroDelimiterNestingWithoutUnboundedScannerWork() {
        String definition = "macro_rules! identity { ($value:tt) => { $value }; }\n";
        String tooDeep = "(".repeat(140) + "7" + ")".repeat(140);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(new Lexer(definition + "identity!(" + tooDeep + ")").scan()));
        assertTrue(rejected.getMessage().contains("macro delimiter nesting depth exceeded"),
                rejected.getMessage());

        String ordinary = "(".repeat(20) + "7" + ")".repeat(20);
        assertDoesNotThrow(() -> MacroExpander.expand(
                new Lexer(definition + "identity!(" + ordinary + ")").scan()));
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
    void fuzzAotAndRebuildableGenerationFingerprintsAndFormatting() {
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

            String rebuildable = aot.replace("@expand(aot)", "@expand(rebuildable)");
            String rebuildableChanged = changed.replace("@expand(aot)", "@expand(rebuildable)");
            MacroExpander.Snapshot rebuildableInitial = MacroExpander.expand(new Lexer(rebuildable).scan()).snapshot();
            MacroExpander.Snapshot rebuildableUpdated =
                    MacroExpander.expand(new Lexer(rebuildableChanged).scan(), rebuildableInitial).snapshot();
            assertNotEquals(rebuildableInitial, rebuildableUpdated, "iteration=" + iteration);
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(aot).scan(), rebuildableInitial),
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
    void acceptsEmptyParameterPatternsAndAllInvocationDelimiters() {
        String source = """
                macro_rules! forty_two { () => { 42 }; }
                forty_two!() + forty_two!{} + forty_two![]
                """;
        MacroExpander.Result expanded = MacroExpander.expand(new Lexer(source).scan());
        assertEquals(3, expanded.tokens().stream()
                .filter(t -> t.type() == Token.Type.INT && t.lexeme().equals("42")).count());
        assertEquals(1, expanded.snapshot().definitions().size());
        assertFalse(expanded.tokens().stream().anyMatch(t -> t.lexeme().equals("forty_two")));
    }

    @Test
    void identCaptureAcceptsOneNameButNotExpressionsOrKeywordTokens() {
        String definition = "macro_rules! passthrough { ($item:ident) => { $item }; }\n";
        List<Token> valid = MacroExpander.expand(
                new Lexer(definition + "passthrough!(some_name)").scan()).tokens();
        assertTrue(valid.stream().anyMatch(t -> t.type() == Token.Type.IDENT
                && t.lexeme().equals("some_name")));
        for (String argument : List.of("123", "some_name + 1", "(some_name)", "return")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(
                            new Lexer(definition + "passthrough!(" + argument + ")").scan()),
                    argument);
        }
    }

    @Test
    void tokenTreeCapturePreservesGroupedNestedCommaAndRejectsLooseTrees() {
        String definition = "macro_rules! tree { ($value:tt) => { $value }; }\n";
        for (String argument : List.of("abc", "123", "(1, (2, 3))", "[1, (2, 3)]", "{ 1, [2, 3] }")) {
            List<Token> expected = new Lexer(argument).scan();
            List<Token> actual = MacroExpander.expand(
                    new Lexer(definition + "tree!(" + argument + ")").scan()).tokens();
            assertEquals(expected.stream().map(t -> t.type() + ":" + t.lexeme()).toList(),
                    actual.stream().map(t -> t.type() + ":" + t.lexeme()).toList(), argument);
        }
        for (String argument : List.of("1 + 2", "(1), (2)", "one two", "(1, 2) + 3")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(
                            new Lexer(definition + "tree!(" + argument + ")").scan()),
                    argument);
        }
    }

    @Test
    void rejectsMissingAndTrailingMacroArguments() {
        String definition = "macro_rules! add { ($a:expr, $b:expr) => { $a + $b }; }\n";
        for (String arguments : List.of("", "1", "1,", ",1", ",", "1,,2", "1,2,3")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(
                            new Lexer(definition + "add!(" + arguments + ")").scan()), arguments);
            assertTrue(failure.getMessage().contains("macro"), arguments);
        }
    }

    @Test
    void rejectsMalformedMacroPatternsAndUnknownCaptureIdentifiers() {
        String[] definitions = {
                "macro_rules! m { ($a:string) => { $a }; }",
                "macro_rules! m { ($a:expr, $a:expr) => { $a }; }",
                "macro_rules! m { ($a:expr,) => { $a }; }",
                "macro_rules! m { ($a:expr) => { $missing }; }",
                "macro_rules! m { ($a:expr) => { $ }; }",
                "macro_rules! m { ($a:expr) => { }; }"
        };
        for (String definition : definitions) {
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(definition).scan()), definition);
        }
    }

    @Test
    void fuzzTruncatedMacroDefinitionsFailWithoutUncheckedIndexErrors() {
        String definition = "macro_rules! truncate { ($x:expr) => { ($x + 1) }; }";
        int checked = 0;
        for (int cut = "macro_rules!".length(); cut < definition.length() - 1; cut++) {
            String truncated = definition.substring(0, cut);
            int cutPoint = cut;
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(new Lexer(truncated).scan()),
                    () -> "accepted truncated definition at cut " + cutPoint);
            checked++;
        }
        assertTrue(checked > 25);
    }

    @Test
    void rejectsUnknownExpansionModeButDoesNotConsumeUnrelatedAnnotations() {
        String definition = "macro_rules! identity { ($x:expr) => { $x }; }";
        for (String mode : List.of("gpu", "AOT", "runtime", "jit")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExpander.expand(
                            new Lexer("@expand(" + mode + ")\n" + definition).scan()), mode);
        }
        List<Token> ordinary = new Lexer("@expand_policy(foo) fnc ordinary(): void {}").scan();
        assertEquals(ordinary, MacroExpander.expand(ordinary).tokens());
    }

    @Test
    void enforcesDefinitionQuotaAtTwoHundredFiftySix() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            source.append("macro_rules! m").append(i)
                    .append(" { ($x:expr) => { $x }; }\n");
        }
        MacroExpander.Result atLimit = MacroExpander.expand(new Lexer(source.toString()).scan());
        assertEquals(256, atLimit.snapshot().definitions().size());
        source.append("macro_rules! overflow { ($x:expr) => { $x }; }\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(new Lexer(source.toString()).scan()));
        assertTrue(failure.getMessage().contains("too many macro definitions"), failure.getMessage());
    }

    @Test
    void expansionTokenBudgetRejectsLargeAmplification() {
        String wideTemplate = "1 + ".repeat(900) + "1";
        String source = "macro_rules! wide { () => { " + wideTemplate + " }; }\n"
                + "wide!();\n".repeat(50);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MacroExpander.expand(new Lexer(source).scan()));
        assertTrue(failure.getMessage().contains("token budget"), failure.getMessage());
    }

    @Test
    void snapshotAndOutputAreImmutableAndCapturesRetainSourceLocation() {
        String source = "macro_rules! step { ($v:expr) => { $v + 1 }; }\nstep!(73)";
        List<Token> input = new Lexer(source).scan();
        MacroExpander.Result result = MacroExpander.expand(input);
        Token captured = result.tokens().stream()
                .filter(t -> t.type() == Token.Type.INT && t.lexeme().equals("73"))
                .findFirst().orElseThrow();
        Token emitted = result.tokens().stream()
                .filter(t -> t.type() == Token.Type.INT && t.lexeme().equals("1"))
                .findFirst().orElseThrow();
        assertEquals(2, captured.line());
        assertEquals(2, emitted.line());
        assertEquals(7, captured.column());
        assertEquals(1, emitted.column());
        assertThrows(UnsupportedOperationException.class, () -> result.tokens().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> result.snapshot().definitions().clear());
        assertTrue(input.stream().anyMatch(t -> t.lexeme().equals("macro_rules")));
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
