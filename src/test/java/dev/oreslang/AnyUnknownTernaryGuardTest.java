package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AnyUnknownTernaryGuardTest {
    @Test
    void lowercaseAnyIsReservedAndIntegerGuardNarrowsTernaryTrueBranch() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc foo(any v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class any as
                end
                """));
    }

    @Test
    void unguardedAnyCannotFlowToConcreteType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc foo(any v): int {
                          return v;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("return value"));
    }

    @Test
    void unknownRequiresValidationBeforeAnyOrConcreteUse() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc foo(unknown v): any {
                          return v;
                        }
                        """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc foo(unknown v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                """)));
    }

    @Test
    void integerGuardExecutesUnderRuntimeAndEvaluatesOneBranch() throws Exception {
        String output = run("""
                fnc foo(any v): int {
                  return Integer.is_int(v) ? v : 0;
                }

                pub fnc main(): void {
                  stdio.stdout.write(foo(42));
                  stdio.stdout.write(foo("x"));
                  return;
                }
                """);

        assertEquals("420", output);
    }

    @Test
    void opaqueTypesCannotCrossAsyncBoundaryWithoutNarrowing() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub async fnc bad(any v): void {
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("async task boundary")
                || failure.getMessage().contains("task-safe")
                || failure.getMessage().contains("any/unknown"));
    }

    @Test
    void typeofValueComparisonIsNotAcceptedAsDynamicTypeTest() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc foo(any v): int {
                  return typeof v == int ? v : 0;
                }
                """));
    }

    @Test
    void legacyCapitalizedSpellingsRemainCompatibleTypeAliases() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc a(Any v): int {
                  return Integer.is_int(v) ? v : 0;
                }

                pub fnc b(Unknown v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "any-guard.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
