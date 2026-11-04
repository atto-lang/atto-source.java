package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NamedGenericDeclarationAdmissionTest {
    @Test
    void genericCallNamesAreNotDefaultDeclarations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc choose<foo, bar>(foo first, bar second): foo {
                  return first;
                }
                fnc use(): string {
                  val string a = choose<foo=string, bar=int>("yes", 5);
                  val string b = choose<bar=int, foo=string>("yes", 5);
                  val string c = choose<string, bar=int>("yes", 5);
                  return a;
                }
                """)));
    }

    @Test
    void genericRoutineUsesTheSameNamedTypeArgumentRulesAsFnc() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                routine identity<foo>(foo input): foo { return input; }
                fnc use(): int { return identity<foo=int>(42); }
                """)));
    }

    @Test
    void reservedInferenceHoleCannotBeDeclaredByAnyLegacyGenericParser() {
        for (String source : List.of(
                "define class Box<_> as end",
                "define interface Box<_> as end",
                "define type Box<_> = int;",
                "define class Box as pub get<_>(int value): int { return value; } end",
                "define actor Worker<_> as end")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("invalid generic parameter"),
                    source + " produced: " + error.getMessage());
        }
    }

    @Test
    void functionGenericDeclarationsRejectInferenceHoleAndDuplicates() {
        for (String source : List.of(
                "fnc bad<_>(): void { return; }",
                "routine bad<_>(): void { return; }",
                "fnc bad<foo,foo>(): void { return; }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void positionalAfterNamedAndDuplicateNamedTypeArgumentsAreNotValid() {
        String prelude = """
                fnc choose<foo,bar>(foo first, bar second): foo { return first; }
                """;
        for (String call : List.of(
                "choose<bar=int, string>(\"yes\", 5)",
                "choose<foo=string, foo=int>(\"yes\", 5)",
                "choose<string, foo=int>(\"yes\", 5)",
                "choose<missing=int>(\"yes\", 5)")) {
            assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse(
                            prelude + "fnc use(): void { " + call + "; return; }")), call);
        }
    }

    @Test
    void runtimeNamedArgumentsMustNotSilentlyBecomePositionalUntilTheirCompilerPlanIsEnabled() {
        for (String call : List.of(
                "choose(bar: 5, foo: \"ok\")",
                "choose(foo: \"ok\", 5)",
                "choose(foo: \"ok\", foo: \"again\")")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    fnc choose(string foo, int bar): string { return foo; }
                    fnc use(): string { return %s; }
                    """.formatted(call)), call);
        }
    }
}
