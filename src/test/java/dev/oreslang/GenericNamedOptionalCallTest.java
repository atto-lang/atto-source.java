package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generic inference must use checked formal slots, never raw call-item order. */
final class GenericNamedOptionalCallTest {
    @Test
    void namedArgumentsInferDistinctGenericsFromFormalSlots() throws Exception {
        String code = """
                fnc first<A, B>(A left, B right): A {
                    return left;
                }

                pub routine main(): void {
                    stdio.stdout.write(first(right: "x", left: 42));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void genericNamedValuesEvaluateOnceInLexicalOrder() throws Exception {
        String code = """
                fnc one(): int {
                    stdio.stdout.write("A");
                    return 19;
                }
                fnc two(): int {
                    stdio.stdout.write("B");
                    return 23;
                }
                fnc first<T>(T left, T right): T {
                    return left;
                }
                pub routine main(): void {
                    stdio.stdout.write(first(right: two(), left: one()));
                    return;
                }
                """;
        assertEquals("BA19", checkedExecution(code));
    }

    @Test
    void genericOmittedMiddleScalarUsesDefaultAfterNamedBinding() throws Exception {
        String code = """
                @DefaultArgVals(delta: 2)
                fnc inspect<T>(T seed, int delta?, int tail): int {
                    return arguments[1] + tail;
                }
                pub routine main(): void {
                    stdio.stdout.write(inspect(tail: 40, seed: "value"));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void genericOptionOmissionMaterializesNone() throws Exception {
        String code = """
                fnc absent<T>(T seed, T? value?): bool {
                    return value.is_none();
                }
                pub routine main(): void {
                    stdio.stdout.write(absent(42));
                    return;
                }
                """;
        assertEquals("true", checkedExecution(code));
    }

    @Test
    void genericTupleSpreadThenNamedTailKeepsProjectionMapping() throws Exception {
        String code = """
                fnc last<A, B>(A first, B second, int third): int {
                    return third;
                }
                pub routine main(): void {
                    stdio.stdout.write(last(...(19, "x"), third: 42));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void genericClosedRecordPackMayOmitAnotherFormal() throws Exception {
        String code = """
                @DefaultArgVals(delta: 2)
                fnc calculate<T>(T value, int delta?, int endValue): int {
                    return delta + endValue;
                }
                pub routine main(): void {
                    stdio.stdout.write(
                            calculate(...record{endValue: 40, value: "x"}));
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void genericRecordPackInferenceUsesSourceFieldOrderNotCanonicalKeyOrder()
            throws Exception {
        String code = """
                fnc second<A, B>(A zed, B alpha): B {
                    return alpha;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            second(...record{zed: "x", alpha: 42}));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void omittedGenericWithoutInferenceOrExplicitBindingRejects() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc missing<T>(T? value?): bool {
                            return value.is_none();
                        }
                        pub routine main(): void {
                            stdio.stdout.write(missing());
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("infer")
                        || failure.getMessage().contains("generic"),
                failure.getMessage());
    }

    @Test
    void genericOmissionCannotSkipRequiredMiddlePositionalSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(delta: 2)
                        fnc inspect<T>(T seed, int delta?, int required): int {
                            return required;
                        }
                        pub routine main(): void {
                            stdio.stdout.write(inspect(1, 40));
                            return;
                        }
                        """)));
    }

    @Test
    void genericRequiredOptionRemainsRequiredEvenWithOptionalNeighbor() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(delta: 2)
                        fnc inspect<T>(T? required, int delta?): int {
                            return delta;
                        }
                        pub routine main(): void {
                            stdio.stdout.write(inspect<int>(delta: 40));
                            return;
                        }
                        """)));
    }

    private static String checkedExecution(String code) throws Exception {
        Ast.Program program = Parser.parse(code);
        var typed = TypeChecker.check(program);
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID, code, "generic-named-optional.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
