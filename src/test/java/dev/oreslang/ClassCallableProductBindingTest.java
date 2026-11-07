package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClassCallableProductBindingTest {
    @Test
    void instanceMethodNamedArgumentsEvaluateInSourceOrderAndBindInAbiOrder()
            throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                define class Codec as
                    pub encode(int a, int b): int {
                        return a * 100 + b;
                    }
                end

                pub routine main(): void {
                    const codec = new Codec();
                    stdio.stdout.write(
                            codec.encode(b: second(), a: first()));
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void staticFunctionRecordSpreadUsesSourceFieldOrderBeforeAbiPermutation()
            throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                define class Codec as
                    pub static fnc encode(int a, int b): int {
                        return a * 100 + b;
                    }
                end

                pub routine main(): void {
                    stdio.stdout.write(
                            Codec.encode(
                                    ...record{b: second(), a: first()}));
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void instanceMethodTupleSpreadRetainsProjectionOrder()
            throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                define class Codec as
                    pub encode(int a, int b): int {
                        return a * 100 + b;
                    }
                end

                pub routine main(): void {
                    const codec = new Codec();
                    stdio.stdout.write(
                            codec.encode(...(first(), second())));
                    return;
                }
                """;

        assertEquals("AB1923", checkedExecution(code));
    }

    @Test
    void instanceMethodOverloadSelectionUsesExpandedTupleArity()
            throws Exception {
        String code = """
                define class Codec as
                    pub choose(int a): int {
                        return a;
                    }

                    pub choose(int a, int b): int {
                        return a + b;
                    }
                end

                pub routine main(): void {
                    const codec = new Codec();
                    stdio.stdout.write(codec.choose(...(19, 23)));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void explicitConstructorNamedArgumentsEvaluateOnceBeforeAbiPermutation()
            throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                define class Pair as
                    constructor(int a, int b) {
                        stdio.stdout.write(a);
                        stdio.stdout.write(b);
                    }
                end

                pub routine main(): void {
                    new Pair(b: second(), a: first());
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void explicitConstructorRecordSpreadUsesTheSameCheckedBinder()
            throws Exception {
        String code = """
                define class Pair as
                    constructor(int a, int b) {
                        stdio.stdout.write(a);
                        stdio.stdout.write(b);
                    }
                end

                pub routine main(): void {
                    new Pair(...record{b: 23, a: 19});
                    return;
                }
                """;

        assertEquals("1923", checkedExecution(code));
    }

    @Test
    void suspendedInstanceMethodNamedArgumentsKeepLexicalOrder()
            throws Exception {
        String code = """
                async fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                async fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                define class Codec as
                    pub static fnc encode(int a, int b): int {
                        return a * 100 + b;
                    }
                end

                async fnc forwarded(): int {
                    // Static: no receiver borrow is live across the awaits.
                    return Codec.encode(
                            b: await second(),
                            a: await first());
                }

                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void suspendedConstructorRecordFieldsEvaluateOnceBeforeAbiPermutation()
            throws Exception {
        String code = """
                async fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                async fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                define class Pair as
                    constructor(int a, int b) {
                        stdio.stdout.write(a);
                        stdio.stdout.write(b);
                    }
                end

                async fnc build(): int {
                    new Pair(
                            ...record{
                                b: await second(),
                                a: await first()
                            });
                    return 7;
                }

                pub routine main(): void {
                    stdio.stdout.write(await build());
                    return;
                }
                """;

        assertEquals("BA19237", checkedExecution(code));
    }

    @Test
    void synthesizedAndGenericConstructorProductSyntaxRemainFailClosed() {
        IllegalArgumentException synthesized = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Plain as
                        end

                        pub routine main(): void {
                            new Plain(value: 1);
                            return;
                        }
                        """)));
        assertTrue(
                synthesized.getMessage().contains("explicit constructor")
                        || synthesized.getMessage().contains("synthesized"),
                synthesized.getMessage());

        IllegalArgumentException generic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box<T> as
                            constructor(T value) {
                            }
                        end

                        pub routine main(): void {
                            new Box<>(value: 1);
                            return;
                        }
                        """)));
        // Explicit class type arguments are supported; only <> inference
        // still reads raw arguments positionally and stays fail-closed.
        assertTrue(
                generic.getMessage().contains("inferred <>"),
                generic.getMessage());
    }

    @Test
    void genericLabeledMethodAndStaticCallsBindThroughTheCheckedPlan()
            throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): string {
                    stdio.stdout.write("B");
                    return "x";
                }

                define class Codec as
                    pub pick<L, R>(L left, R right): L {
                        return left;
                    }

                    pub static fnc keep<L, R>(L left, R right): L {
                        return left;
                    }
                end

                pub routine main(): void {
                    const codec = new Codec();
                    stdio.stdout.write(codec.pick(right: "x", left: 19));
                    stdio.stdout.write(Codec.keep<R=string>(right: second(), left: 19));
                    return;
                }
                """;

        assertEquals("19B19", checkedExecution(code));
    }

    @Test
    void methodRecordSpreadCannotCollideWithExplicitNamedArgument() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Codec as
                            pub encode(int a, int b): int {
                                return a * 100 + b;
                            }
                        end

                        pub routine main(): void {
                            const codec = new Codec();
                            stdio.stdout.write(
                                    codec.encode(
                                            ...record{a: 19},
                                            a: 23));
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    private static String checkedExecution(String code) throws Exception {
        var parsed = Parser.parse(code);
        var typed = TypeChecker.check(parsed);
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(
                    Source.newBuilder(
                                    OresLanguage.ID,
                                    code,
                                    "class-callable-product-binding.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
