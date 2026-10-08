package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TupleCoreReconciliationTest {
    @Test
    void parenthesizedProductTypesAreEquivalentToCanonicalTupleTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Pair = (string, int);
                type Nested = (Tuple[string, int], bool);

                fnc pair(): Pair {
                  return ("foo", 5);
                }

                fnc nested(): Nested {
                  return (pair(), true);
                }

                fnc project(Nested value): int {
                  return value[0][1];
                }

                pub fnc main(): void {
                  const value: Tuple[string, int] = pair();
                  const (key, n) = value;
                  const result: int = project(nested());
                  return;
                }
                """)));
    }

    @Test
    void groupedAndFunctionTypesKeepTheirOriginalMeaning() {
        assertDoesNotThrow(() -> Parser.parse("""
                type Scalar = (int);
                type Callback = (int, string) => bool;
                type Pair = (int, string);
                """));
        IllegalArgumentException singleton = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("type Invalid = (int,);"));
        assertTrue(singleton.getMessage().contains("singleton tuple type"), singleton.getMessage());
    }

    @Test
    void inlineAndExplicitTupleConstructorsAndFixedSpreadExecute() throws Exception {
        String program = """
                fnc pair(): (string, int) {
                  return ("foo", 5);
                }

                fnc consume(Tuple[string, int] value): int {
                  return value[1];
                }

                fnc add(int left, int right): int {
                  return left + right;
                }

                pub routine main(): void {
                  const fromNew = new Tuple[string, int]("bar", 8);
                  const inferred = new Tuple("baz", 9);
                  const (key, number) = pair();
                  stdio.stdout.write(key);
                  stdio.stdout.write(consume(fromNew));
                  stdio.stdout.write(inferred[1]);
                  stdio.stdout.write(add(...(19, 23)));
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("foo8942", run(program));
    }

    @Test
    void tupleConstructorRejectsWrongArityTypesAndArgumentKinds() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          const bad = new Tuple[int, string](5);
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          const bad = new Tuple[int, string]("wrong", 5);
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          const bad = new Tuple();
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          const bad = new Tuple(left: 5, right: 6);
                          return;
                        }
                        """)));
    }

    @Test
    void tupleIndexIsReadOnlyBoundsCheckedAndStaticallyTyped() {
        IllegalArgumentException mutation = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          let pair = (5, "five");
                          pair[0] = 6;
                          return;
                        }
                        """)));
        assertTrue(mutation.getMessage().contains("immutable"), mutation.getMessage());

        IllegalArgumentException bounds = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          const pair = (5, "five");
                          const bad = pair[2];
                          return;
                        }
                        """)));
        assertTrue(bounds.getMessage().contains("out of bounds"), bounds.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          const pair = (5, "five");
                          const wrong: int = pair[1];
                          return;
                        }
                        """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  const pair = (5, "five");
                  const n: int = pair[0];
                  const word: string = pair[1];
                  return;
                }
                """)));
    }

    @Test
    void namedTupleLiteralsAreStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                  const bad = (x: "bar", y: 5);
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-core-reconciliation.ores")
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
