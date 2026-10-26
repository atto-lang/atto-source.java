package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
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

final class NamedArgumentAndRecordSpreadTest {
    @Test
    void namedArgumentsBindByNameButEvaluateInLexicalOrder() throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                fnc encode(int a, int b): int {
                    return a * 100 + b;
                }

                pub routine main(): void {
                    stdio.stdout.write(encode(b: second(), a: first()));
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void positionalPrefixMayPrecedeNamedTail() throws Exception {
        String code = """
                fnc calculate(int base, int multiplier, int offset): int {
                    return base * multiplier + offset;
                }

                pub routine main(): void {
                    stdio.stdout.write(calculate(10, offset: 2, multiplier: 4));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void duplicateUnknownMissingAndPositionalAfterNamedReject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            stdio.stdout.write(add(a: 1, a: 2));
                            return;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            stdio.stdout.write(add(a: 1, 2));
                            return;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            stdio.stdout.write(add(a: 1, nope: 2));
                            return;
                        }
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }
                        pub routine main(): void {
                            stdio.stdout.write(add(a: 1));
                            return;
                        }
                        """)));
    }

    @Test
    void inlineReadonlyRecordSpreadBindsFieldsByName() throws Exception {
        String code = """
                fnc encode(int a, int b): int {
                    return a * 100 + b;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            encode(...record{b: 23, a: 19}));
                    return;
                }
                """;

        assertEquals("1923", checkedExecution(code));
    }

    @Test
    void recordSpreadKeepsFieldEffectsInSourceOrderBeforeAbiPermutation()
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

                fnc encode(int a, int b): int {
                    return a * 100 + b;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            encode(...record{b: second(), a: first()}));
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void splitRecordPacksAndPositionalPrefixUseOneTotalBinder()
            throws Exception {
        String code = """
                fnc calculate(int base, int multiplier, int offset): int {
                    return base * multiplier + offset;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            calculate(
                                    10,
                                    ...record{offset: 2},
                                    ...record{multiplier: 4}));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void recordPackCannotCollideWithAlreadyBoundPositionalSlot() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc calculate(int base, int multiplier, int offset): int {
                            return base * multiplier + offset;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(
                                    calculate(
                                            10,
                                            ...record{base: 2, multiplier: 4}));
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void structsAndWidenedRecordValuesAreNotImplicitArgumentPacks() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int a, int b): int { return a + b; }

                        pub routine main(): void {
                            const pack = infer struct{a: 19, b: 23};
                            stdio.stdout.write(add(...pack));
                            return;
                        }
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc make(): record{a: int, b: int} {
                            return record{a: 19, b: 23};
                        }

                        fnc add(int a, int b): int { return a + b; }

                        pub routine main(): void {
                            const pack = make();
                            stdio.stdout.write(add(...pack));
                            return;
                        }
                        """)));
    }

    @Test
    void moveOnlyRecordFieldsCannotBeProjectedBySpread() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Cell as
                            pub let int value = 1;
                        end

                        fnc consume(Cell cell, int count): int {
                            return count;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(
                                    consume(...record{count: 42, cell: new Cell()}));
                            return;
                        }
                        """)));
    }

    @Test
    void genericNamedCallsRemainFailClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc identity<T>(T value): T {
                            return value;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(identity(value: 42));
                            return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("generic")
                        || failure.getMessage().contains("named"),
                failure.getMessage());
    }

    @Test
    void asyncDirectNamedArgumentsPreserveSourceOrder() throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                fnc second(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                fnc encode(int a, int b): int {
                    return a * 100 + b;
                }

                async fnc forwarded(): int {
                    return encode(b: second(), a: first());
                }

                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void suspendedRecordFieldsEvaluateOnceBeforeNamedAbiReorder()
            throws Exception {
        String code = """
                fnc encode(int a, int b): int {
                    return a * 100 + b;
                }

                async fnc get_b(): int {
                    stdio.stdout.write("B");
                    return 23;
                }

                async fnc get_a(): int {
                    stdio.stdout.write("A");
                    return 19;
                }

                async fnc forwarded(): int {
                    return encode(
                            ...record{b: await get_b(), a: await get_a()});
                }

                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
                    return;
                }
                """;

        assertEquals("BA1923", checkedExecution(code));
    }

    @Test
    void namedArgumentCannotHideRestrictedCapabilityUse() {
        var program = Parser.parse("""
                fnc consume(int value): int {
                    return value;
                }

                pub routine main(): void {
                    consume(value: network.connect("127.0.0.1", 80));
                    return;
                }
                """);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(
                        program,
                        IsolatePolicy.strictFaas()));
        assertTrue(
                failure.getMessage().contains("NETWORK")
                        || failure.getMessage().contains("network"),
                failure.getMessage());
    }

    @Test
    void ownershipCheckerIndependentlyRejectsForgedNamedCollisions() {
        String code = """
                fnc calculate(int base, int multiplier, int offset): int {
                    return base * multiplier + offset;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            calculate(
                                    10,
                                    ...record{base: 2, multiplier: 4}));
                    return;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(code)));
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
                                    "named-argument-record-spread.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
