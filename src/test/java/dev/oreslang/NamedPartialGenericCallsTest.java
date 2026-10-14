package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NamedPartialGenericCallsTest {
    private static final String PICK = """
            define module app
              fnc pick<A, B, C>(A first, B second, C third): C {
                return third;
              }
              fnc use(): void {
                %s
                return;
              }
            end
            """;

    @Test
    void namedGenericArgumentsAreOrderIndependentAndMissingOnesInfer() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PICK.formatted("""
                val bool a = pick<C=bool, A=int>(7, "x", true);
                val bool b = pick<A=int, C=bool>(7, "x", true);
                val bool c = pick<int, C=bool>(7, "x", true);
                val bool d = pick<int, _, bool>(7, "x", true);
                val bool e = pick<int, String, bool>(7, "x", true);
                val bool f = pick(7, "x", true);
                """))));
    }

    @Test
    void rejectsUnknownOrRepeatedGenericNames() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PICK.formatted(
                        "pick<Q=bool>(7, \"x\", true);"))));
        assertTrue(unknown.getMessage().contains("no generic parameter named"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PICK.formatted(
                        "pick<A=int, A=bool>(7, \"x\", true);"))));
        assertTrue(duplicate.getMessage().contains("duplicate generic argument"));

        IllegalArgumentException positionalDuplicate = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PICK.formatted(
                        "pick<int, A=string>(7, \"x\", true);"))));
        assertTrue(positionalDuplicate.getMessage().contains("duplicate generic argument"));
    }

    @Test
    void rejectsPositionalAfterNamedAndTooManyArguments() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(PICK.formatted(
                "pick<A=int, string, C=bool>(7, \"x\", true);")));

        IllegalArgumentException excessive = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PICK.formatted(
                        "pick<int, string, bool, int>(7, \"x\", true);"))));
        assertTrue(excessive.getMessage().contains("expects at most 3 explicit type argument"));
    }

    @Test
    void explicitArgumentsCannotBeOverriddenByInference() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(PICK.formatted(
                        "pick<A=string>(7, \"x\", true);"))));
    }

    @Test
    void inferenceHolesDoNotSilentlyBindUnusedParameters() {
        String source = """
                define module app
                  fnc unused<A, B>(A value): A { return value; }
                  fnc use(): int { return unused<A=int, B=_>(7); }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("cannot infer generic parameter"));
        assertTrue(failure.getMessage().contains("B"));
    }

    @Test
    void comparisonExpressionsRemainUnambiguous() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc compare(int a, int b, int c): bool {
                    return a < b && b > (c);
                  }
                end
                """)));
    }

    @Test
    void namedGenericBindingsCannotBypassMoveChecking() {
        String source = """
                define module app
                  define class Resource as
                    pub val int id = 7;
                  end

                  fnc sink<T, U>(T value, U marker): void { return; }
                  fnc bad(): void {
                    let Resource resource = new Resource();
                    sink<U=int, T=Resource>(resource, 42);
                    stdio.println(resource.id);
                    return;
                  }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("use of moved value"),
                failure.getMessage());
    }
}
