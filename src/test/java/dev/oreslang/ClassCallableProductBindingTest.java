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
    void tripleNamedConstructorPreservesAllThreeFormalSlots()
            throws Exception {
        // Java guest counterpart of LLVM's native Triple named-ABI fixtures.
        String code = """
                define class Trio as
                    constructor(int first, int second, int third) {
                        stdio.stdout.write(first + second + third);
                    }
                end
                pub routine main(): void {
                    new Trio(third: 3, first: 19, second: 20);
                    new Trio(19, third: 3, second: 20);
                    return;
                }
                """;
        assertEquals("4242", checkedExecution(code));
    }

    @Test
    void namedConstructorArithmeticBindsBeforeGuestExecution()
            throws Exception {
        // Match the LLVM native field-initializer arithmetic fixture's
        // source expressions while testing Java's explicit constructor ABI.
        String code = """
                define class Pair as
                    constructor(int first, int second) {
                        stdio.stdout.write((first + 1) + (second * 2));
                    }
                end
                pub routine main(): void {
                    new Pair(second: 7 * 2, first: 10 + 3);
                    return;
                }
                """;
        assertEquals("42", checkedExecution(code));
    }

    @Test
    void namedTripleConstructorRejectsPositionalSlotCollision() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Trio as
                            constructor(int first, int second, int third) {
                                return;
                            }
                        end
                        pub routine main(): void {
                            new Trio(19, third: 3, first: 20);
                            return;
                        }
                        """)));
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
                    pub encode(int a, int b): int {
                        return a * 100 + b;
                    }
                end

                async fnc forwarded(): int {
                    const codec = new Codec();
                    return codec.encode(
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
                            new Box<int>(value: 1);
                            return;
                        }
                        """)));
        assertTrue(
                generic.getMessage().contains("generic")
                        || generic.getMessage().contains("source-to-ABI"),
                generic.getMessage());
    }

    @Test
    void genericLabeledMethodCallsRemainFailClosedAtOwnershipLowering() {
        var typed = TypeChecker.check(Parser.parse("""
                define class Codec as
                    pub identity<T>(T value): T {
                        return value;
                    }
                end

                pub routine main(): void {
                    const codec = new Codec();
                    stdio.stdout.write(codec.identity(value: 42));
                    return;
                }
                """));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(typed));
        assertTrue(
                failure.getMessage().contains("generic")
                        || failure.getMessage().contains("source-to-ABI"),
                failure.getMessage());
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
                                    "class-callable-product-binding.atto")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
