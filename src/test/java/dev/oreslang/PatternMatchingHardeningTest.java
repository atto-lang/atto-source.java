package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PatternMatchingHardeningTest {
    @Test
    void bracedSwitchSupportsGroupedCasesCatchAllFirstAndArmSeparators() {
        var typed = TypeChecker.check(Parser.parse("""
                fnc choose(string v): string {
                  switch (v) {
                    _ { return "star"; },
                    case "foo", "car", "mar" { return "bar"; };
                  }
                  return "fallback";
                }
                fnc chooseBare(string v): string {
                  switch v {
                    case "foo" { return "bar"; }
                    default { return "star"; }
                  }
                  return "fallback";
                }
                """));
        assertNotNull(typed);
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(string v): void {
                  switch v {
                    default { return; }
                    _ { return; }
                  }
                }
                """));
        IllegalArgumentException duplicated = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(string v): void {
                  switch v {
                    case "foo", "foo" { return; }
                  }
                }
                """)));
        assertTrue(duplicated.getMessage().contains("duplicate switch case"));
    }

    @Test
    void bracedMatchAcceptsNominalIsAsAndTupleBindings() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc consume(int x): void {
                  match x over {
                    on is MessagePatterns.Reset as m { return; }
                    on MessagePatterns.Delta as m { return; }
                    on MessagePatterns.Tuple as (m1, m2) { return; }
                    on MessagePatterns.Reset m { return; }
                    on _ { return; }
                  }
                }
                fnc consumeEnd(int x): void {
                  match x over
                    on is MessagePatterns.Reset as m { return; }
                    on MessagePatterns.Delta m { return; }
                    on _ { return; }
                  end
                }
                """));
    }

    @Test
    void fallbackFirstDoesNotShadowSpecificSwitchCasesAtRuntime() throws Exception {
        String program = """
                fnc choose(string v): string {
                  switch v {
                    _ { return "star"; },
                    case "foo", "car", "mar" { return "bar"; };
                  }
                  return "unreachable";
                }
                pub fnc main(): void {
                  stdio.println(choose("foo"));
                  stdio.println(choose("else"));
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "switch-fallback-first.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("bar\nstar", output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void matchAndSwitchUseContextualArmKeywordsAndSlimArrows() {
        var tokens = new Lexer("is matches match when case default first switch on").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IS));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCHES));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCH));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("when")));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.DEFAULT));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("case")));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("on")));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("first")));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc contextualNames(): int {
                  val int on = 1;
                  val int case = 2;
                  val int when = 3;
                  return on + case + when;
                }
                """)));

        assertDoesNotThrow(() -> Parser.parse("""
                fnc contextualArms(int value): void {
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

                fnc guarded(int value): int {
                  match value over
                    on int n when n > 0 -> {
                      return n;
                    }
                    on _ -> {
                      return 0;
                    }
                  end
                }
                """));

        IllegalArgumentException fatMatch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> value): void {
                  match value over
                    on Some(v) => { return; }
                    on None -> { return; }
                  end
                }
                """));
        assertTrue(fatMatch.getMessage().contains("slim arrow"));

        IllegalArgumentException fatSwitch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int value): void {
                  switch value
                    case 1 => { return; }
                    default -> { return; }
                  end
                }
                """));
        assertTrue(fatSwitch.getMessage().contains("slim arrow"));
    }

    @Test
    void isNarrowsAndBindsInsideIfFi() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define class Animal as
                    end

                    define class Dog extends Animal as
                    end

                    fnc acceptDog(Dog dog): int {
                      return 7;
                    }

                    fnc classify(Animal animal): int {
                      if animal is type Dog dog then
                        return acceptDog(dog);
                      else
                        return 0;
                      fi
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void matchesCanDestructureSumTypesInsideIfFi() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    fnc valueOrZero(Option<int> value): int {
                      if value matches Some(inner) then
                        return inner;
                      else
                        return 0;
                      fi
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void orderedMatchUsesFirstMatchAndRejectsProvablyDeadLaterArms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc valueOrZero(Option<int> value): int {
                  match value over
                    on Some(inner) -> { return inner; }
                    on None -> { return 0; }
                  end
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  match animal over
                    on Dog dog -> { return 1; }
                    on Animal any -> { return 2; }
                  end
                }
                """)));

        IllegalArgumentException unreachable = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  match animal over
                    on Animal any -> { return 2; }
                    on Dog dog -> { return 1; }
                  end
                }
                """)));
        assertTrue(unreachable.getMessage().contains("unreachable match arm"));
    }

    @Test
    void orderedGuardsMayOverlapAndWildcardIsTheFallback() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int n): int {
                  match n over
                    on int x when x >= 0 -> { return 1; }
                    on int x when x <= 10 -> { return 2; }
                    on _ -> { return 3; }
                  end
                }
                """)));

        // 'on _' is a default alias: even if authored first it cannot
        // shadow an explicit type arm.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc good(int n): int {
                  match n over
                    on _ -> { return 1; }
                    on 7 -> { return 7; }
                  end
                }
                """)));

        // An ordinary named binder remains ordered and can shadow arms.
        IllegalArgumentException dead = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(int n): int {
                  match n over
                    on anything -> { return 1; }
                    on int x -> { return x; }
                  end
                }
                """)));
        assertTrue(dead.getMessage().contains("unreachable match arm"));
    }

    @Test
    void canonicalMatchRequiresOnAndExhaustivenessWhileLegacyMatchRemainsAccepted() {
        // Compatibility: the pre-over statement grammar remains accepted so
        // existing Oreslang does not break merely because canonical match
        // gained explicit "over" / "on" boundaries.
        assertDoesNotThrow(() -> Parser.parse("""
                fnc legacy(Option<int> value): int {
                  match value
                    Some(inner) -> { return inner; }
                  end
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> value): int {
                  match value over
                    Some(inner) -> { return inner; }
                  end
                }
                """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc incomplete(Option<int> value): int {
                  match value over
                    on Some(inner) -> { return inner; }
                  end
                }
                """)));
        assertTrue(error.getMessage().contains("non-exhaustive match"));
    }

    @Test
    void switchIsConstantDispatchAndRejectsDuplicateCases() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int value): int {
                  switch value
                    case 1 -> { return 10; }
                    case 2, 3 -> { return 20; }
                    default -> { return 0; }
                  end
                }
                """)));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc classify(int value): int {
                  switch value
                    case 1 -> { return 10; }
                    case 1 -> { return 20; }
                    default -> { return 0; }
                  end
                }
                """)));
        assertTrue(duplicate.getMessage().contains("duplicate switch case"));
    }

    @Test
    void castsAreCheckedAndOptionalWithoutJavaHostTypeSemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc checked(Animal animal): Dog {
                  return animal as Dog;
                }

                fnc optional(Animal animal): Option<Dog> {
                  return animal as? Dog;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Dog as
                end

                fnc impossible(int value): Dog {
                  return value as Dog;
                }
                """)));
    }

    @Test
    void runtimeExecutesNativeSemanticTypeAndPatternRelations() throws Exception {
        String program = """
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  if animal is type Dog dog then
                    return 7;
                  else
                    return 0;
                  fi
                }

                fnc fromOption(Option<int> value): int {
                  match value over
                    on Some(inner) -> { return inner; }
                    on None -> { return 0; }
                  end
                }

                fnc fromSwitch(int value): int {
                  switch value
                    case 1 -> { return 11; }
                    case 2 -> { return 22; }
                    default -> { return 0; }
                  end
                }

                pub fnc main(): void {
                  val Animal animal = new Dog();
                  stdio.println(classify(animal));
                  stdio.println(fromOption(Some(42)));
                  stdio.println(fromSwitch(2));
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pattern-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("7"));
        assertTrue(text.contains("42"));
        assertTrue(text.contains("22"));
    }

    @Test
    void refinementAliasCannotDuplicateMoveOnlyOwnership() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define class Animal as
                    end

                    define class Dog extends Animal as
                    end

                    fnc consume(Dog dog): void {
                      return;
                    }

                    fnc bad(Animal animal): void {
                      if animal is type Dog dog then
                        consume(dog);
                        consume(dog);
                      fi
                      return;
                    }
                    """));
            OwnershipChecker.check(typed);
        });
        assertTrue(error.getMessage().contains("moved value"));
    }

    @Test
    void defaultIsCanonicalMatchCatchAllWithExplicitBindingAndFallbackPriority() {
        var typed = TypeChecker.check(Parser.parse("""
                fnc decide(int n): int {
                  match n over {
                    default as remaining { return remaining; }
                    on 7 { return 42; }
                  }
                  return -1;
                }
                fnc aliasUnderscore(int n): int {
                  match n over {
                    on _ as remaining { return remaining; }
                    on 7 { return 42; }
                  }
                  return -1;
                }
                fnc aliasKeyword(int n): int {
                  match n over {
                    on default { return 0; }
                    on 7 { return 42; }
                  }
                  return -1;
                }
                """));
        assertNotNull(typed);
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc duplicate(int n): int {
                  match n over {
                    default { return 0; }
                    on _ { return 1; }
                  }
                  return 2;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc guardedDefault(int n): int {
                  match n over {
                    default when n > 0 { return 1; }
                  }
                  return 0;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc unbound(int n): int {
                  match n over {
                    default { return m; }
                  }
                  return 0;
                }
                """)));
    }

    @Test
    void wildcardCannotBeDeclaredAsClassTypeOrOrdinaryValueName() {
        for (String code : new String[] {
                "define class _ as end",
                "define class default as end",
                "type _ = int;",
                "fnc test(): void { val _ = 5; }",
                "define module _ as end",
                "define actor _ as end",
                "fnc _(): void { return; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(code), code);
        }
        assertDoesNotThrow(() -> Parser.parse("""
                fnc ok(int n): int {
                  const [head, _] = [n, 0];
                  return head;
                }
                """));
    }

    @Test
    void defaultFirstForMatchIsExecutedOnlyAfterSpecificArms() throws Exception {
        String program = """
                fnc decide(int n): int {
                  match n over {
                    default as remaining { return remaining; }
                    on 7 { return 42; }
                  }
                  return -1;
                }
                pub fnc main(): void {
                  stdio.println(decide(7));
                  stdio.println(decide(9));
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "match-default-first.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42\n9", output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }


    @Test
    void switchCaseDuplicatesAreComparedAfterScalarConstantFolding() {
        for (String caseBody : new String[] {
                "case 2 { return 2; } case 1 + 1 { return 3; }",
                "case -1 { return 2; } case 0 - 1 { return 3; }"
        }) {
            String source = "fnc f(int v): int { switch v { " + caseBody
                    + " default { return 0; } } return 0; }";
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), source);
            assertTrue(error.getMessage().contains("duplicate switch case"), error.getMessage());
        }
        IllegalArgumentException strings = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc f(string v): int {
                  switch v {
                    case "foo" { return 1; }
                    case "f" + "oo" { return 2; }
                    default { return 0; }
                  }
                  return 0;
                }
                """)));
        assertTrue(strings.getMessage().contains("duplicate switch case"), strings.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid([int, int] v): int {
                  switch v {
                    case [1, 2] { return 1; }
                    default { return 0; }
                  }
                  return 0;
                }
                """)));
    }

    @Test
    void largeNegativeI64SwitchConstantsRemainDistinctAboveDoublePrecision() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int n): int {
                  switch n {
                    case -9007199254740992 { return 1; }
                    case -9007199254740993 { return 2; }
                    default { return 0; }
                  }
                  return -1;
                }
                """)));
    }

    @Test
    void asyncAndSyncSwitchAgreeOnFallbackSemantics() throws Exception {
        String program = """
                async fnc answer(): string {
                  return "car";
                }
                fnc direct(string value): int {
                  switch value {
                    default { return 0; }
                    case "foo", "car" { return 42; }
                  }
                  return -1;
                }
                pub async fnc main(): void {
                  stdio.println(direct("car"));
                  switch (await answer()) {
                    default { stdio.println(0); }
                    case "foo", "car" { stdio.println(42); }
                  }
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "switch-async-equality.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42\n42", output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }


    @Test
    void typedMatchContractsParseInPositionalAndNamedForms() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc example(int n): bool {
                  match<bool> n over {
                    default as m { return false; }
                    on 7 { return true; }
                  }
                  match<ret=bool> n over {
                    on 7 { return true; }
                    default { return false; }
                  }
                  do match<bool> n over {
                    on 7 { return true; }
                    default { return false; }
                  }
                  do match<ret=bool> n over {
                    default { return false; }
                    on 7 { return true; }
                  }
                }
                """));
        for (String header : new String[] {
                "match<foo=bool>", "match<ret=int, extra=bool>",
                "match<ret=>", "do match<ret=bool"
        }) {
            String source = "fnc f(int n): bool { " + header
                    + " n over { default { return true; } } }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void typedDoMatchUsesContainingReturnContractAndEnforcesAllBranches() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc f(int n): bool {
                  do match<ret=bool> n over {
                    default { return false; }
                    on 7 { return true; }
                  }
                }
                """)));
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc wrong(int n): bool {
                  do match<ret=bool> n over {
                    default { return 42; }
                    on 7 { return true; }
                  }
                }
                """)));
        assertTrue(wrong.getMessage().contains("return"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc missing(int n): bool {
                  do match<ret=bool> n over {
                    default { stdio.println(n); }
                    on 7 { return true; }
                  }
                }
                """)));
        assertTrue(missing.getMessage().contains("must return"));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc outer(int n): int {
                  do match<ret=bool> n over {
                    default { return false; }
                  }
                }
                """)));
    }

    @Test
    void valueMatchContractsFailClosedUntilArmLocalReturnLowering() {
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc f(int n): bool {
                  match<bool> n over {
                    default { return false; }
                    on 7 { return true; }
                  }
                }
                """)));
        assertTrue(unsupported.getMessage().contains("arm-local return/result lowering"));
    }

}
